#pragma once

#include "ravenemu/link_endpoint.hpp"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <thread>

namespace ravenemu {

/**
 * Endpoint de enlace LAN por TCP: la mitad de red del "cable" entre dos
 * dispositivos, cada uno en su propio teléfono.
 *
 * Diseño, y por qué:
 *
 *   • El SIO de GBA es síncrono y de palabra completa: `exchange_word`
 *     emite la palabra propia y espera la del par como una sola ida y
 *     vuelta. En red eso es un `XFER_REQ` por TCP con espera de la
 *     respuesta; el plazo máximo (`kExchangeDeadlineMs`) vuelve a los
 *     todos-uns de GBATEK si el par no contesta.
 *
 *   • Mientras `exchange_word` espera, se sirven las peticiones entrantes
 *     (`service`) con el mismo puerto: si los dos lados fueran maestros a
 *     la vez, cada uno respondería en su espera en lugar de bloquearse
 *     mutuamente.
 *
 *   • El hilo lector nunca toca un `LinkPort`: sólo encola peticiones y
 *     actualiza atomios. Todo lo que ocurre con el puerto —`service`,
 *     `exchange_word`— corre en el hilo de emulación, que es el único que
 *     muta el dispositivo. `state_changed` sólo arma una cola de envío.
 *
 *   • Roles: el que crea la sala (servidor TCP) es el padre —ID 0, genera
 *     el reloj— y el que se une (cliente) el hijo. En el hardware lo
 *     decide el cable; aquí lo decide la capa de transporte y el saludo
 *     HELLO lo verifica: dos equipos con el mismo papel se rechazan.
 *
 *   • Un endpoint ausente o muerto se comporta como un cable desconectado:
 *     `peer_present() = false` y todo intercambio responde todos-uns.
 *
 * Límite documentado de la v1: sólo se transporta el modo
 * multiprocesador (y normal como consecuencia de él). UART, modo propósito
 * general y JOYBUS guardan registro pero no transmiten por red.
 */
class NetworkLinkEndpoint final : public LinkEndpoint {
public:
    /**
     * `parent` = sala (servidor TCP, padre del enlace multiprocesador);
     * `host` sólo lo usa el cliente, el servidor escucha en INADDR_ANY.
     * `tcp_port` es el puerto TCP de la sala.
     */
    NetworkLinkEndpoint(bool parent, std::string host, int tcp_port);
    ~NetworkLinkEndpoint() override;

    NetworkLinkEndpoint(const NetworkLinkEndpoint&) = delete;
    NetworkLinkEndpoint& operator=(const NetworkLinkEndpoint&) = delete;

    /** Abre la escucha y lanza el hilo que acepta UNA conexión. 0 = ok. */
    [[nodiscard]] int open_server();
    /** Conecta con el servidor y lanza el hilo lector. 0 = ok,
     *  -1 = error de socket/resolución, -2 = sin respuesta a tiempo. */
    [[nodiscard]] int open_client(int timeout_ms);

    /**
     * Cierra el enlace: marca muerto, despierta a quien espere, une los
     * hilos y libera los descriptores. Se puede llamar más de una vez.
     * El destructor hace exactamente esto.
     */
    void close_socket() noexcept;

    /** 0 = esperando (aceptando o con el saludo pendiente), 1 = enlazado,
     *  2 = cerrado o caído. */
    [[nodiscard]] int status() const noexcept;

    // ── LinkEndpoint ────────────────────────────────────────────────────────
    [[nodiscard]] bool attach(LinkPort& port) noexcept override;
    void detach(LinkPort& port) noexcept override;
    [[nodiscard]] bool exchange_bit(LinkPort& source, bool outgoing_high) noexcept override;
    [[nodiscard]] bool exchange_word(
        LinkPort& source, std::uint32_t outgoing, int bits,
        std::uint32_t& incoming, bool& peer_ready
    ) noexcept override;
    [[nodiscard]] bool push_word(
        LinkPort& source, std::uint32_t outgoing, int bits
    ) noexcept override;
    void service(LinkPort& port) noexcept override;
    void state_changed(std::uint32_t packed_state) noexcept override;
    [[nodiscard]] bool peer_present() const noexcept override;
    [[nodiscard]] std::uint32_t peer_state() const noexcept override;
    [[nodiscard]] bool is_parent() const noexcept override;

private:
    struct Request {
        std::uint8_t bits;
        std::uint8_t sequence;
        std::uint32_t word;
        /** El par no espera respuesta: hay que relojarla y nada más. */
        bool fire_and_forget;
    };
    struct Response {
        std::uint8_t sequence;
        bool ready;
        std::uint32_t word;
    };

    void reader_loop() noexcept;
    [[nodiscard]] bool send_frame(std::uint8_t type, const std::uint8_t* payload,
                                  std::uint16_t size) noexcept;
    [[nodiscard]] bool read_exact(std::uint8_t* data, std::size_t size) noexcept;
    void start_reader();
    void fail_link() noexcept;

    // El puntero al puerto sólo lo toman attach/detach, que en Kotlin
    // corren con `coreLock` tomado: un solo hilo lo muta.
    LinkPort* link_port_{};
    bool parent_{};
    std::string host_;
    int tcp_port_{};

    // Un solo hilo posee la cerrada de cada descriptor: el que hace
    // `exchange(-1)` es el que cierra, para no cerrar dos veces (y por
    // tanto nunca un descriptor ya reutilizado por el sistema).
    std::atomic<int> listen_fd_{-1};
    std::atomic<int> conn_fd_{-1};
    std::thread reader_;

    std::mutex send_mutex_;   // serializa las escrituras al socket
    std::mutex queue_mutex_;  // protege las dos colas siguientes
    std::condition_variable queue_cv_;
    std::deque<Request> requests_;
    std::deque<Response> responses_;

    std::atomic<bool> dead_{};
    std::atomic<bool> peer_present_{};
    std::atomic<std::uint32_t> peer_state_{};
    std::atomic<bool> resend_state_{true};
    std::atomic<std::uint8_t> next_sequence_{};

    /**
     * Pendencia de trabajo para `service`, en bits.
     *
     * `service` se llama desde `Serial::tick`, que el planificador invoca una
     * vez por instrucción de CPU: unas 280 000 veces por fotograma a 60 fps.
     * Por eso el camino de no-hay-nada-que-hacer es UNA carga atómica
     * relajada. Con un `exchange` secuencialmente consistente y un `lock` de
     * mutex por llamada, el enlace se comía 10-30 ms de cada fotograma y el
     * juego iba a 24 fps aunque el par no enviara nada.
     *
     * Los bits los arma el hilo lector al encolar y el propio endpoint al
     * tener que reenviar el estado; se limpian bajo `queue_mutex_`, en el
     * mismo orden que la cola, para que un encolado simultáneo no se pierda.
     */
    static constexpr std::uint32_t kWorkRequest = 1U;
    static constexpr std::uint32_t kWorkState   = 2U;
    std::atomic<std::uint32_t> work_{};

    // ── Contadores del enlace ───────────────────────────────────────────────
    // El enlace no se nota cuando va bien, y cuando va mal no se sabe por
    // qué: por CPU en el hilo de emulación, por latencia de la red, o por un
    // juego que haceMillions de intercambios. Estos contadores lo cuentan y se
    // escriben en el registro del sistema cada `kStatsPeriodMs`.
    //
    // Sólo los escribe el hilo de emulación, así que no necesitan átomo.
    std::uint64_t stat_exchanges_{};   // intercambios que sí esperaron respuesta
    std::uint64_t stat_pushes_{};      // tramas enviadas sin esperar
    std::uint64_t stat_wait_ns_{};     // tiempo total bloqueado esperando al par
    std::uint64_t stat_worst_ns_{};    // la espera más larga de una
    std::uint64_t stat_served_{};      // peticiones del par servidas
    std::chrono::steady_clock::time_point stat_window_{};
    void log_stats() noexcept;

    // Último estado de línea ya enviado al par: evita repetir la trama cuando
    // el juego reescribe SIOCNT sin cambiar nada de lo que el otro ve.
    std::atomic<std::uint32_t> last_state_{};
    bool state_sent_{};
};

} // namespace ravenemu
