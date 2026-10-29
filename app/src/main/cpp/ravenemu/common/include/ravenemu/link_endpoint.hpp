#pragma once

#include <array>
#include <cstdint>

namespace ravenemu {

/** Extrémité matérielle série exposée à un transport indépendant de l'hôte. */
class LinkPort {
public:
    virtual ~LinkPort() = default;
    [[nodiscard]] virtual bool outgoing_bit_high() const noexcept = 0;
    virtual void clock_external_bit(bool incoming_high) noexcept = 0;
    /**
     * Estado de línea que observa el par, empaquetado como (RCNT << 16) |
     * SIOCNT en GBA. Por defecto 0: un transporte que no lo usa no miente,
     * queda el valor neutro y quien lo consulte tratará el par como ausente
     * o inerte según convenga.
     */
    [[nodiscard]] virtual std::uint32_t link_state() const noexcept { return 0; }
};

/**
 * Transport d'un link cable ou d'un périphérique série (Printer, réseau…).
 * La durée reste entièrement décidée par le SerialPort émulé.
 *
 * Ampliado para el enlace de GBA sobre red (fase N2): `exchange_word` y
 * `service` permiten que un transporte asíncrono —TCP— encaje con el SIO,
 * cuya transferencia es síncrona y de palabra completa. Tres notas:
 *
 *   • Todo lo que estos métodos hacen con un `LinkPort` ocurre en el hilo de
 *     emulación: `service` se llama desde el tick del dispositivo y
 *     `exchange_word` desde la transferencia en curso. El transporte sólo
 *     encola trabajo entrante desde su propio hilo lector.
 *   • Las implementaciones por defecto dejan el comportamiento antiguo
 *     intacto: `LocalLinkEndpoint` y el núcleo GB no necesitan nada de esto.
 *   • `exchange_word` devuelve false si el transporte no lo soporta; el
 *     llamante (SIO de GBA) tratará entonces el enlace como ausente.
 */
class LinkEndpoint {
public:
    virtual ~LinkEndpoint() = default;
    [[nodiscard]] virtual bool attach(LinkPort& port) noexcept = 0;
    virtual void detach(LinkPort& port) noexcept = 0;
    [[nodiscard]] virtual bool exchange_bit(LinkPort& source, bool outgoing_high) noexcept = 0;

    /**
     * Intercambia una trama completa al empezar una transferencia SIO: la
     * palabra propia sale entera y la del par vuelve entera, en una sola
     * ida y vuelta. `bits` es la longitud (8, 18 o 32 en la práctica) y la
     * palabra se transmite MSB primero: el bit `bits-1` es el primero.
     *
     * `peer_ready = false` significa "conectado pero sin dato del par"; el
     * llamante lo traduce a todos-uns, que es exactamente lo que GBATEK
     * prescribe para un timeout de recepción. Devuelve false sólo si el
     * transporte no implementa el intercambio por palabra.
     */
    [[nodiscard]] virtual bool exchange_word(
        LinkPort& source, std::uint32_t outgoing, int bits,
        std::uint32_t& incoming, bool& peer_ready
    ) noexcept {
        (void)source; (void)outgoing; (void)bits; (void)incoming; (void)peer_ready;
        return false;
    }

    /**
     * Empuja la trama propia al par SIN esperar su respuesta.
     *
     * Existe para la fase 0 de una transferencia multiprocesador: ahí el
     * maestro manda su trama de 18 bits y lo que devuelve el par se tira
     * (el hijo aún no tiene trama propia, porque necesita la del maestro
     * antes). Sobre TCP, esperar esa respuesta es una ida y vuelta entera
     * —unos 7-8 ms entre dos móviles por el router— por cada transferencia,
     * sin ganar nada: se paga la latencia y se acortan los fotogramas.
     *
     * El par sigue relojando los bits (lo que de verdad importa); lo único que
     * se ahorra es la respuesta que nadie iba a leer.
     *
     * Devuelve false si el transporte no lo implementa: quien llama recurre
     * entonces a `exchange_word`, con la espera de siempre.
     */
    [[nodiscard]] virtual bool push_word(
        LinkPort& source, std::uint32_t outgoing, int bits
    ) noexcept {
        (void)source; (void)outgoing; (void)bits;
        return false;
    }

    /**
     * Atiende peticiones de transferencia que el par envió y quedaron
     * encoladas, contra `port`. Se invoca con el hilo de emulación —una vez
     * por tick y también mientras `exchange_word` espera la respuesta, para
     * que dos maestros simultáneos no se bloqueen entre sí—.
     */
    virtual void service(LinkPort& port) noexcept { (void)port; }

    /**
     * Avisa al transporte de que el estado de línea local cambió (escrituras
     * de SIOCNT/RCNT en GBA) para que lo empuje al par. Sin enlace no hay
     * nadie a quien avisar: el cuerpo por defecto está vacío.
     */
    virtual void state_changed(std::uint32_t /*packed_state*/) noexcept {}

    /** ¿Responde el par en el enlace en este momento? */
    [[nodiscard]] virtual bool peer_present() const noexcept { return false; }
    /** Último estado de línea del par, empaquetado como `LinkPort::link_state`. */
    [[nodiscard]] virtual std::uint32_t peer_state() const noexcept { return 0; }
    /**
     * Papel en el enlace multiprocesador de GBA: el padre (ID 0, "parent")
     * genera el reloj; el hijo no puede iniciar transferencias. En hardware
     * lo decide el cable, en red lo decide la capa de transporte —el que
     * crea la sala es padre—, y por eso el endpoint es quien lo sabe.
     */
    [[nodiscard]] virtual bool is_parent() const noexcept { return true; }
};

/** Câble local déterministe reliant au plus deux machines dans un processus. */
class LocalLinkEndpoint final : public LinkEndpoint {
public:
    [[nodiscard]] bool attach(LinkPort& port) noexcept override {
        for (auto* existing : ports_) if (existing == &port) return true;
        for (auto& slot : ports_) {
            if (slot == nullptr) { slot = &port; return true; }
        }
        return false;
    }

    void detach(LinkPort& port) noexcept override {
        for (auto& slot : ports_) if (slot == &port) slot = nullptr;
    }

    [[nodiscard]] bool exchange_bit(LinkPort& source, bool outgoing_high) noexcept override {
        for (auto* port : ports_) {
            if (port == nullptr || port == &source) continue;
            const bool incoming = port->outgoing_bit_high();
            port->clock_external_bit(outgoing_high);
            return incoming;
        }
        return true; // ligne SIN au repos
    }

private:
    std::array<LinkPort*, 2> ports_{};
};

} // namespace ravenemu
