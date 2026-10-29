#include "network_link_endpoint.hpp"

#include <arpa/inet.h>
#include <cerrno>
#include <chrono>
#include <climits>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <android/log.h>
#else
#include <cstdio>
#endif

namespace ravenemu {

namespace {

// ── Trama: [tipo u8][longitud u16 little-endian][payload] ──────────────────
//    Los tipos son estables en la v1; uno desconocido se ignora para poder
//    ampliar el protocolo sin romper a un par de versión anterior.
constexpr std::uint8_t kHelloFrame = 0;     // {u32 magia, u8 versión, u8 rol}
constexpr std::uint8_t kStateFrame = 1;     // {u32 (RCNT << 16) | SIOCNT}
constexpr std::uint8_t kRequestFrame = 2;   // {u8 bits, u8 orden, u32 palabra}
constexpr std::uint8_t kResponseFrame = 3;  // {u8 banderas, u8 orden, u32 palabra}

constexpr std::uint32_t kHelloMagic = 0x42475341u; // "ASGB" en little-endian
constexpr std::uint8_t kProtocolVersion = 2;
constexpr std::uint8_t kRoleParent = 1;
constexpr std::uint8_t kRoleChild = 2;

// Marca de "no me contestes" en la petición: el par relojará los bits y no
// enviará respuesta. La fase 0 de una transferencia multiprocesador manda su
// trama al hijo y lo que el hijo devuelve se descarta, así que pedirlo era
// pagar una ida y vuelta completa por transferencia sin ganar nada.
constexpr std::uint8_t kRequestNoReply = 0x80;

// Los frames caben en 9 bytes: sin Nagle el retardo de la LAN no se come el
// intercambio; con tiempo de espera de envío, un par congelado no puede
// dejar a `close_socket` esperando un `send` infinito.
constexpr int kSocketTimeoutMs = 200;
constexpr int kExchangeDeadlineMs = 500; // tope del SIO esperando una palabra
constexpr int kWaitSliceMs = 5;          // rebanada de espera del intercambio

/** Cada cuánto se escribe el resumen de la enlace en el registro del sistema. */
constexpr int kStatsPeriodMs = 10000;

[[nodiscard]] std::uint32_t read_u32(const std::uint8_t* data) noexcept {
    return std::uint32_t(data[0]) | (std::uint32_t(data[1]) << 8U)
         | (std::uint32_t(data[2]) << 16U) | (std::uint32_t(data[3]) << 24U);
}

void write_u32(std::uint8_t* data, std::uint32_t value) noexcept {
    data[0] = std::uint8_t(value & 0xffU);
    data[1] = std::uint8_t((value >> 8U) & 0xffU);
    data[2] = std::uint8_t((value >> 16U) & 0xffU);
    data[3] = std::uint8_t((value >> 24U) & 0xffU);
}

bool send_all(int fd, const std::uint8_t* data, std::size_t size) noexcept {
    while (size > 0) {
        const auto sent = ::send(fd, data, size, MSG_NOSIGNAL);
        if (sent < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (sent == 0) return false;
        data += sent;
        size -= std::size_t(sent);
    }
    return true;
}

/** Prepara un socket de datos para el tráfico interactivo del enlace. */
void prepare_socket(int fd) noexcept {
    timeval timeout{0, kSocketTimeoutMs * 1000};
    ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    ::setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    const int one = 1;
    ::setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));

    // Confirmación inmediata en el camino de vuelta. El enlace es un ping-pong
    // de 9 bytes: si el kernel del par seguarda el ACK hasta el temporizador
    // de ACK diferido, cada intercambio se paga con decenas de milisegundos
    // que no son de la red. En Linux se pide con TCP_QUICKACK; en otras
    // plataformas no existe y no pasa nada.
#ifdef TCP_QUICKACK
    ::setsockopt(fd, IPPROTO_TCP, TCP_QUICKACK, &one, sizeof(one));
#endif

    // Buffers grandes: el juego empuja su trama de estado unas cuantas veces por
    // fotograma y, con el valor por defecto (unos 16 KB), una ráfaga de sólo
    // 18 bytes por intercambio puede llenarlos y provocar una espera del
    // remitente. Aquí no hay nada que perder por usar más memoria.
    int buffer = 256 * 1024;
    ::setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &buffer, sizeof(buffer));
    ::setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &buffer, sizeof(buffer));

    // El enlace es corto: si el otro teléfono se apaga, want(); Connection reset
    // es la respuesta. Con el valor por defecto el enlace se quedaría colgado
    // dos horas sin que nadie se entere.
    ::setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));
#ifdef TCP_KEEPIDLE
    ::setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &one, sizeof(one));   // 1 s
#endif
#ifdef TCP_KEEPINTVL
    ::setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &one, sizeof(one));  // cada 1 s
#endif
#ifdef TCP_KEEPCNT
    const int keep_count = 3;
    ::setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &keep_count, sizeof(keep_count));
#endif
}

} // namespace

NetworkLinkEndpoint::NetworkLinkEndpoint(bool parent, std::string host, int tcp_port)
    : parent_(parent), host_(std::move(host)), tcp_port_(tcp_port) {}

NetworkLinkEndpoint::~NetworkLinkEndpoint() { close_socket(); }

int NetworkLinkEndpoint::open_server() {
    const int listener = ::socket(AF_INET, SOCK_STREAM, 0);
    if (listener < 0) return -1;
    const int one = 1;
    ::setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons(static_cast<std::uint16_t>(tcp_port_));
    if (::bind(listener, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0
        || ::listen(listener, 1) != 0) {
        ::close(listener);
        return -1;
    }
    // SO_RCVTIMEO también limita a `accept`: sin ese tope, cerrar la sala
    // mientras el hilo espera tendría que esperar a una conexión que quizá
    // nunca llegue.
    timeval timeout{0, kSocketTimeoutMs * 1000};
    ::setsockopt(listener, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    listen_fd_.store(listener);
    start_reader();
    return 0;
}

int NetworkLinkEndpoint::open_client(int timeout_ms) {
    addrinfo hints{};
    hints.ai_family = AF_INET; // la LAN de la v1 es IPv4
    hints.ai_socktype = SOCK_STREAM;
    addrinfo* addresses = nullptr;
    const auto port_text = std::to_string(tcp_port_);
    if (::getaddrinfo(host_.c_str(), port_text.c_str(), &hints, &addresses) != 0
        || addresses == nullptr) {
        return -1;
    }
    const int fd = ::socket(addresses->ai_family, addresses->ai_socktype, addresses->ai_protocol);
    if (fd < 0) {
        ::freeaddrinfo(addresses);
        return -1;
    }
    // Conexión no bloqueante con tope: una IP que no existe puede tardar
    // segundos en el ARP y el hilo de Kotlin no debe quedar colgado.
    const int flags = ::fcntl(fd, F_GETFL, 0);
    ::fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    int result = ::connect(fd, addresses->ai_addr, addresses->ai_addrlen);
    if (result != 0 && errno == EINPROGRESS) {
        pollfd descriptor{fd, POLLOUT, 0};
        const auto polled = ::poll(&descriptor, 1, timeout_ms);
        if (polled > 0) {
            int error = 0;
            socklen_t length = sizeof(error);
            result = (::getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &length) == 0 && error == 0)
                         ? 0
                         : -1;
        } else {
            result = -2; // sin respuesta dentro del plazo
        }
    }
    ::freeaddrinfo(addresses);
    if (result != 0) {
        ::close(fd);
        return result == -2 ? -2 : -1;
    }
    ::fcntl(fd, F_SETFL, flags); // vuelve a bloqueante: sólo el hilo lee y escribe
    prepare_socket(fd);
    conn_fd_.store(fd);
    start_reader();
    return 0;
}

void NetworkLinkEndpoint::close_socket() noexcept {
    dead_.store(true);
    queue_cv_.notify_all();
    if (reader_.joinable()) reader_.join();
    // El lector cierra la conexión al salir (vía exchange); esto sólo pilla
    // los descriptores que quedaron sin hilo que los cerrara.
    const int connection = conn_fd_.exchange(-1);
    if (connection >= 0) ::close(connection);
    const int listener = listen_fd_.exchange(-1);
    if (listener >= 0) ::close(listener);
}

int NetworkLinkEndpoint::status() const noexcept {
    if (dead_) return 2;
    return peer_present_ ? 1 : 0;
}

// ── LinkEndpoint ───────────────────────────────────────────────────────────

bool NetworkLinkEndpoint::attach(LinkPort& port) noexcept {
    if (link_port_ != nullptr && link_port_ != &port) return false;
    link_port_ = &port;
    resend_state_.store(true); // el primer tick empuja el estado inicial
    work_.fetch_or(kWorkState, std::memory_order_relaxed);
    return true;
}

void NetworkLinkEndpoint::detach(LinkPort& port) noexcept {
    if (link_port_ == &port) link_port_ = nullptr;
}

bool NetworkLinkEndpoint::exchange_bit(LinkPort& source, bool outgoing_high) noexcept {
    // El SIO de GBA no usa el intercambio bit a bit (habla por palabra); se
    // delega por coherencia con el contrato de la interfaz.
    std::uint32_t incoming = 0;
    bool ready = false;
    (void)exchange_word(source, outgoing_high ? 1u : 0u, 1, incoming, ready);
    return ready ? (incoming & 1u) != 0 : true; // sin par: línea en reposo
}

bool NetworkLinkEndpoint::exchange_word(LinkPort& source, std::uint32_t outgoing, int bits,
                                        std::uint32_t& incoming, bool& peer_ready) noexcept {
    if (bits < 1 || bits > 32) return true;
    if (dead_ || !peer_present_) return true; // sin par: quien llama ya dejó FFFF

    // El orden correlaciona respuesta con intento: si este se agota y la
    // respuesta llega tarde, la siguiente transferencia la descarta en vez
    // de leer una palabra prestada.
    const auto sequence = next_sequence_.fetch_add(1);
    std::uint8_t payload[6]{std::uint8_t(bits), sequence, 0, 0, 0, 0};
    write_u32(payload + 2, outgoing);
    if (!send_frame(kRequestFrame, payload, sizeof(payload))) return true; // enlace caído

    const auto started = std::chrono::steady_clock::now();
    ++stat_exchanges_;
    const auto deadline = started + std::chrono::milliseconds(kExchangeDeadlineMs);
    bool taken = false;
    std::uint32_t word = 0;
    bool ready = false;
    while (true) {
        // Mientras esperamos la respuesta atendemos al par: si los dos
        // lados arrancaran transferencias a la vez, cada uno respondería
        // dentro de su propia espera y nadie se bloquearía.
        service(source);
        {
            std::unique_lock lock(queue_mutex_);
            while (!responses_.empty()) {
                const auto response = responses_.front();
                responses_.pop_front();
                if (response.sequence != sequence) continue; // tardía de un intento anterior
                taken = true;
                word = response.word;
                ready = response.ready;
                break;
            }
            if (taken || dead_) break;
            if (std::chrono::steady_clock::now() >= deadline) break;
            queue_cv_.wait_for(lock, std::chrono::milliseconds(kWaitSliceMs));
        }
    }
    const auto waited_ns = static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now() - started).count());
    stat_wait_ns_ += waited_ns;
    if (waited_ns > stat_worst_ns_) stat_worst_ns_ = waited_ns;
    if (taken) {
        incoming = word;
        peer_ready = ready;
    }
    return true;
}

bool NetworkLinkEndpoint::push_word(LinkPort& /*source*/, std::uint32_t outgoing,
                                    int bits) noexcept {
    if (bits < 1 || bits > 32) return true;
    if (dead_ || !peer_present_) return true;

    // Fase 0 de una transferencia multiprocesador: el maestro entrega su trama
    // y lo que el par devuelve se tira. Sobre la red, esperar esa respuesta
    // son ~7-8 ms entre dos móviles, por cada transferencia: la mitad del
    // fotograma. Mandándola sin pedir respuesta, el par sigue relojando los
    // bits —lo único que importa— y el maestro sigue sin esperar.
    const auto sequence = next_sequence_.fetch_add(1);
    std::uint8_t payload[6]{
        std::uint8_t(std::uint8_t(bits) | kRequestNoReply), sequence, 0, 0, 0, 0};
    write_u32(payload + 2, outgoing);
    ++stat_pushes_;
    (void)send_frame(kRequestFrame, payload, sizeof(payload));
    return true;
}

void NetworkLinkEndpoint::log_stats() noexcept {
    const auto now = std::chrono::steady_clock::now();
    if (stat_window_.time_since_epoch().count() == 0) {
        stat_window_ = now; // primera ventana: se empieza a contar aquí
        return;
    }
    const auto elapsed =
        std::chrono::duration_cast<std::chrono::milliseconds>(now - stat_window_);
    if (elapsed.count() < kStatsPeriodMs) return;
    stat_window_ = now;

    const auto exchanges = stat_exchanges_;
    const auto pushes = stat_pushes_;
    const auto served = stat_served_;
    const auto waited = stat_wait_ns_;
    const auto worst = stat_worst_ns_;
    stat_exchanges_ = 0; stat_pushes_ = 0; stat_served_ = 0;
    stat_wait_ns_ = 0; stat_worst_ns_ = 0;

    const double seconds = double(elapsed.count()) / 1000.0;
    const double average_ms = exchanges > 0 ? (double(waited) / 1e6) / double(exchanges) : 0.0;
    // El coste para el fotograma no es el promedio sino la suma: así es como
    // se nota en los fps.
    const double per_second_ms = (double(waited) / 1e6) / seconds;
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "AsGbaLink",
        "enlace: %llu intercambios/s (%.2f ms de espera cada uno), %llu pushed/s, "
        "%llu servidos/s, peor %.1f ms | la red se come %.0f ms de CPU por segundo",
        (unsigned long long)(exchanges / seconds), average_ms,
        (unsigned long long)(pushes / seconds), (unsigned long long)(served / seconds),
        double(worst) / 1e6, per_second_ms);
#else
    std::printf("enlace: %llu intercambios/s (%.2f ms), %llu pushed/s, %llu servidos/s, "
                "peor %.1f ms\n",
                (unsigned long long)(exchanges / seconds), average_ms,
                (unsigned long long)(pushes / seconds), (unsigned long long)(served / seconds),
                double(worst) / 1e6);
#endif
}

void NetworkLinkEndpoint::service(LinkPort& port) noexcept {
    // Camino rápido: el planificador llama a `service` una vez por instrucción
    // de CPU. Si no hay nada encolado, esto es una carga atómica relajada y
    // nada más: ni mutex, ni syscall, ni RMW. Todo lo caro queda detrás de
    // este `if`.
    const std::uint32_t pending = work_.load(std::memory_order_relaxed);
    if (pending == 0) return;

    // Resumen de la ventana anterior: sólo cuando hay tráfico, que es justo
    // cuando interesa verlo.
    log_stats();

    // Aviso de estado inicial (o de reanudación tras el saludo del par): lo
    // despacha el hilo de emulación, el único que puede leer el puerto.
    if ((pending & kWorkState) != 0 && resend_state_.exchange(false)) {
        std::uint8_t payload[4]{0, 0, 0, 0};
        write_u32(payload, port.link_state());
        (void)send_frame(kStateFrame, payload, sizeof(payload)); // el fallo lo cuenta `status()`
    }

    for (;;) {
        Request request{};
        bool taken = false;
        {
            std::lock_guard lock(queue_mutex_);
            if (!requests_.empty()) {
                request = requests_.front();
                requests_.pop_front();
                taken = true;
            } else {
                // Se limpia el bit bajo el mismo cerrojo con el que el lector
                // lo arma: si el lector encola después de este punto, vuelve
                // a poner el bit y la petición se sirve en el siguiente
                // `service`, sin quedarse colgada.
                work_.fetch_and(~kWorkRequest, std::memory_order_relaxed);
            }
        }
        if (!taken) return;

        // Mismo orden que el intercambio síncrono del SIO: por cada bit,
        // muestrear la salida propia y luego encarar la entrada del par.
        const int count = int(request.bits);
        std::uint32_t sampled = 0;
        for (int index = 0; index < count; ++index) {
            const int bit = count - 1 - index; // MSB primero
            const bool outgoing = port.outgoing_bit_high();
            const bool incoming = ((request.word >> bit) & 1U) != 0;
            sampled = (sampled << 1U) | (outgoing ? 1U : 0U);
            port.clock_external_bit(incoming);
        }
        std::uint8_t payload[6]{1 /*listo: hay puerto*/, request.sequence, 0, 0, 0, 0};
        write_u32(payload + 2, sampled);
        ++stat_served_;
        // El maestro de la fase 0 no espera nada: contestar sería gastar una
        // llamada al sistema y un paquete por transferencia sin que nadie los
        // lea.
        if (!request.fire_and_forget) {
            (void)send_frame(kResponseFrame, payload, sizeof(payload)); // idem: `status()`
        }
    }
}

void NetworkLinkEndpoint::state_changed(std::uint32_t packed_state) noexcept {
    // Sin par que escuche, el estado no se pierde: `resend_state_` lo
    // reenvía en cuanto llega el saludo del otro lado.
    if (dead_ || !peer_present_) return;
    // En modo multiprocesador el juego reescribe SIOCNT unas cuantas veces por
    // fotograma. Mandar la trama sólo cuando el estado ha cambiado ahorra
    // decenas de paquetes por segundo sin cambiar lo que el par ve: el valor
    // recibido sigue siendo el último.
    if (state_sent_ && last_state_.load(std::memory_order_relaxed) == packed_state) return;
    last_state_.store(packed_state, std::memory_order_relaxed);
    state_sent_ = true;
    std::uint8_t payload[4]{0, 0, 0, 0};
    write_u32(payload, packed_state);
    (void)send_frame(kStateFrame, payload, sizeof(payload)); // un fallo ya lo marca `fail_link`
}

bool NetworkLinkEndpoint::peer_present() const noexcept { return peer_present_; }

std::uint32_t NetworkLinkEndpoint::peer_state() const noexcept { return peer_state_; }

bool NetworkLinkEndpoint::is_parent() const noexcept { return parent_; }

// ── Hilo de red ────────────────────────────────────────────────────────────

void NetworkLinkEndpoint::start_reader() { reader_ = std::thread(&NetworkLinkEndpoint::reader_loop, this); }

void NetworkLinkEndpoint::fail_link() noexcept {
    if (!dead_.exchange(true)) queue_cv_.notify_all();
}

bool NetworkLinkEndpoint::send_frame(std::uint8_t type, const std::uint8_t* payload,
                                     std::uint16_t size) noexcept {
    if (dead_) return false;
    const int fd = conn_fd_.load();
    if (fd < 0) return false;
    // Cabecera y payload en un solo `send`: son nueve bytes y, con el juego
    // haciendo cientos de intercambios por segundo, el segundo `send` de cada
    // trama se nota en el perfil.
    std::uint8_t frame[3 + 32];
    frame[0] = type;
    frame[1] = std::uint8_t(size & 0xffU);
    frame[2] = std::uint8_t((size >> 8U) & 0xffU);
    for (std::uint16_t index = 0; index < size; ++index) frame[3 + index] = payload[index];
    std::lock_guard lock(send_mutex_);
    const bool ok = send_all(fd, frame, std::size_t(3 + size));
    if (!ok) fail_link(); // el par ya no lee (o la conexión murió): aviso ya
    return ok;
}

bool NetworkLinkEndpoint::read_exact(std::uint8_t* data, std::size_t size) noexcept {
    while (size > 0) {
        if (dead_) return false;
        const auto got = ::recv(conn_fd_.load(), data, size, 0);
        if (got == 0) return false; // el par cerró
        if (got < 0) {
            // Tiempo de espera agotado: el hilo reviva y compruebe `dead_`.
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) continue;
            return false;
        }
        data += got;
        size -= std::size_t(got);
    }
    return true;
}

void NetworkLinkEndpoint::reader_loop() noexcept {
    // Sólo quien abrió la escucha acepta: el rol (padre/hijo) lo decide el
    // saludo, no este bucle —un cliente que declarara ser padre no debe
    // intentar hacer `accept`.
    if (listen_fd_.load() >= 0) {
        // La sala acepta a UN primer invitado y deja de escuchar.
        while (!dead_) {
            const int listener = listen_fd_.load();
            if (listener < 0) return;
            sockaddr_in peer{};
            socklen_t length = sizeof(peer);
            const int connection =
                ::accept(listener, reinterpret_cast<sockaddr*>(&peer), &length);
            if (connection < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR
                    || errno == ECONNABORTED) {
                    continue;
                }
                fail_link();
                return;
            }
            const int owned = listen_fd_.exchange(-1);
            if (owned >= 0) ::close(owned);
            prepare_socket(connection);
            conn_fd_.store(connection);
            break;
        }
        if (dead_) return;
    }

    // Saludo: con él el par confirma papel y versión, y nosotros marcamos
    // que ya hay alguien al otro extremo.
    std::uint8_t hello[6]{0, 0, 0, 0, kProtocolVersion, parent_ ? kRoleParent : kRoleChild};
    write_u32(hello, kHelloMagic);
    (void)send_frame(kHelloFrame, hello, sizeof(hello)); // sin par, el saludo se pierde sin más

    std::uint8_t header[3];
    while (!dead_) {
        if (!read_exact(header, 3)) break;
        const auto type = header[0];
        const auto size = int(header[1]) | (int(header[2]) << 8);
        std::uint8_t payload[64];
        if (size > int(sizeof(payload))) break; // trama incomprensible
        if (!read_exact(payload, std::size_t(size))) break;
        if (type == kHelloFrame) {
            if (size < 6) break;
            const auto role = payload[5];
            if (read_u32(payload) != kHelloMagic || payload[4] != kProtocolVersion) break;
            if ((role == kRoleParent) == parent_) break; // dos padres o dos hijos: pareja mal formada
            peer_present_.store(true);
            resend_state_.store(true); // nuestro estado, apenas llega su saludo
            work_.fetch_or(kWorkState, std::memory_order_relaxed);
        } else if (type == kStateFrame && size >= 4) {
            peer_state_.store(read_u32(payload));
        } else if (type == kRequestFrame && size >= 6) {
            const bool no_reply = (payload[0] & kRequestNoReply) != 0;
            const Request request{
                std::uint8_t(payload[0] & 0x3f), payload[1], read_u32(payload + 2), no_reply};
            if (request.bits >= 1 && request.bits <= 32) {
                std::lock_guard lock(queue_mutex_);
                requests_.push_back(request);
                // El bit se arma dentro del mismo cerrojo con el que `service`
                // lo limpia, así que una petición nunca se pierde por una
                // carrera entre el fin de la cola y el armado del flag.
                work_.fetch_or(kWorkRequest, std::memory_order_relaxed);
            }
            queue_cv_.notify_all();
        } else if (type == kResponseFrame && size >= 6) {
            const Response response{payload[1], (payload[0] & 1) != 0, read_u32(payload + 2)};
            std::lock_guard lock(queue_mutex_);
            responses_.push_back(response);
            queue_cv_.notify_all();
        }
        // Un tipo desconocido se ignora: permite ampliar el protocolo sin
        // romper a un par de versión anterior.
    }

    peer_present_.store(false);
    fail_link();
    const int owned = conn_fd_.exchange(-1);
    if (owned >= 0) ::close(owned);
}

} // namespace ravenemu
