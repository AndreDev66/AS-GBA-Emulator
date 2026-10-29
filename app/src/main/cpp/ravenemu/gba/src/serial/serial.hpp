#pragma once

// ── SIO: controlador serie del GBA (0x120-0x12B y RCNT en 0x134) ────────────
//
// RavenEmu no traía SIO para GBA —sólo el puerto serie de Game Boy— y el
// enlace LAN entre dos dispositivos lo exige, así que este módulo se escribió
// desde cero contra GBATEK §"SIO Normal Mode" y §"SIO Multi-Player Mode".
// Resumen del hardware que gobierna todo lo que sigue:
//
//   Selección de modo (RCNT.15-14 y SIOCNT.13-12):
//     0x00 = normal 8 bit, 0x01 = normal 32 bit, 0x02 = multi 16 bit,
//     0x03 = UART; RCNT.15=1 → propósito general; RCNT.15-14=11 → JOY BUS.
//
//   Normal: SIOCNT bit0 = reloj (0 externo / 1 interno), bit1 = cadencia
//   (256 kHz o 2 MHz), bit2 = SI (estado del SO del par, sólo lectura),
//   bit3 = SO en reposo, bit7 = arranque/ocupado, bit12 = longitud,
//   bit14 = IRQ. La transferencia desplaza MSB primero y dura exactamente
//   8 o 32 pulsos. IRQ serie = bit 7 del IF.
//
//   Multi: SIOCNT bits0-1 = baudaje (9600/38400/57600/115200), bit2 =
//   terminal SI (0 padre, 1 hijo), bit3 = "todos listos", bits4-5 = ID,
//   bit6 = error, bit7 = ocupado (sólo escribible en el padre; los hijos lo
//   reflejan), bit14 = IRQ. Cada trama mide 18 bits: arranque (0), 16 de
//   datos, parada (1); el padre emite la suya, espera un retardo, y después
//   la emite el hijo; si el hijo no contesta, GBATEK manda que la entrada
//   quede en FFFFh. Al empezar, SIOMULTI0-3 se ponen a FFFFh solos y al
//   terminar contienen los datos de todas las unidades —el propio padre
//   incluido—.
//
// Decisiones que NO están en el hardware y que se anotan aquí para que nadie
// las confunda con omisiones:
//
//   • Papel padre/hijo: en el hardware lo decide el cable (nivel de SI al
//     arrancar). En LAN lo decide la capa de transporte: el que crea la sala
//     es padre (ID 0), el que se une es hijo (ID 1). Se refleja en los bits
//     2 y 4-5 de SIOCNT, que sólo se leen.
//   • Bit 3 "todos listos": equivale a "el par está conectado y su modo
//     también es multi". El estado del par llega empujado por el endpoint
//     (`state_changed`) y se consulta en cada lectura; sin enlace vale 0,
//     que es lo que un juego espera al no haber cable —y por eso mismo un
//     juego sin par nunca arranca una transferencia.
//   • Retardo entre tramas y timeout final (18 períodos cada uno): GBATEK
//     sólo dice que existen ("delays", "certain time"). Con dos unidades
//     basta con que coincidan entre los dos extremos, y como el extremo
//     receptor ejecuta exactamente esta misma máquina de estados a través
//     de `service`, coinciden por construcción.
//   • UART y propósito general: los registros se guardan y se leen, pero no
//     transmiten. Ningún juego de enlace de dos GBA usa UART (el adaptador
//     inalámbrico va por modo normal), así que queda documentado en vez de
//     implementado.
//
// Invariantes de hilo, en mayúsculas porque son lo que mantiene vivo al
// diseño: TODO lo que toca registros vive en el hilo de emulación. El
// endpoint sólo encola peticiones entrantes desde su hilo lector y jamás
// invoca este objeto; `service` (que sí toca) se llama desde `tick`, desde
// el propio `exchange_word` en espera, y de ningún otro sitio.

#include "interrupts/interrupt_controller.hpp"

#include <ravenemu/core.hpp> // SaveStateError, para el guardado instantáneo

#include <algorithm>
#include <array>
#include <limits>
#include <span>

namespace ravenemu::gba {

class Serial final : public ravenemu::LinkPort {
public:
    enum class Mode { normal, multi, uart, general_purpose, joybus };

    explicit Serial(InterruptController& interrupts) : interrupts_(interrupts) {}
    ~Serial() override { disconnect(); }
    Serial(const Serial&) = delete;
    Serial& operator=(const Serial&) = delete;

    // ── Bus ─────────────────────────────────────────────────────────────────
    // El bus delega aquí la lectura y la escritura del bloque SIO y RCNT; los
    // bytes de `Bus::io` quedan como sombra inútil (se escriben pero nadie los
    // lee), igual que ya pasa con KEYINPUT y los temporizadores.

    [[nodiscard]] int read(int offset) const noexcept {
        switch (offset) {
        case 0x120: return static_cast<int>(mode() == Mode::multi ? multi_[0] : std::uint16_t(data32_));
        case 0x122: return static_cast<int>(mode() == Mode::multi ? multi_[1] : std::uint16_t(data32_ >> 16));
        case 0x124: return static_cast<int>(mode() == Mode::multi ? multi_[2] : 0);
        case 0x126: return static_cast<int>(mode() == Mode::multi ? multi_[3] : 0);
        case 0x128: return read_siocnt();
        case 0x12a: return static_cast<int>(mode() == Mode::multi ? send_multi_ : data8_);
        case 0x134: return read_rcnt();
        default: return 0;
        }
    }

    void write(int offset, int value) noexcept {
        if (offset == 0x134) { write_rcnt(value); return; }
        switch (offset) {
        case 0x120:
            // SIOMULTI0 en multi es sólo lectura (lo escribe el propio SIO);
            // fuera de multi es la mitad baja de SIODATA32.
            if (mode() != Mode::multi) data32_ = (data32_ & 0xffff0000U) | std::uint16_t(value);
            break;
        case 0x122:
            if (mode() != Mode::multi) data32_ = (data32_ & 0x0000ffffU) | (std::uint32_t(std::uint16_t(value)) << 16U);
            break;
        case 0x128: write_siocnt(value); break;
        case 0x12a:
            // SIOMLT_SEND en multi; SIODATA8 (byte bajo) u otro en el resto.
            if (mode() == Mode::multi) send_multi_ = std::uint16_t(value);
            else data8_ = std::uint8_t(value);
            break;
        default: break; // 0x124/0x126 no existen fuera de multi
        }
    }

    // ── Machine ─────────────────────────────────────────────────────────────

    /**
     * Avanza la transferencia en marcha y atiende al par. El endpoint se
     * sirve SIEMPRE, esté o no activa una transferencia: un hijo que acaba
     * de entrar en modo multi tiene que poder responder al padre aunque su
     * propio juego no haya escrito nada todavía.
     */
    void tick(int cycles) noexcept {
        if (endpoint_) endpoint_->service(*this);
        if (!active_ || !master_) return;
        cycles_ -= cycles;
        while (active_ && master_ && cycles_ <= 0) advance();
    }

    /**
     * Ciclos hasta el próximo bit de la transferencia. Sin transferencia
     * maestra no hay evento de tiempo —un hijo se activa sólo con los
     * pulsos que llegan— y el planificador lo ignora con el mismo techo
     * que usa para temporizadores ociosos.
     */
    [[nodiscard]] int cycles_until_next_event() const noexcept {
        if (!active_ || !master_) return no_event;
        return std::max(1, cycles_);
    }

    /** Reinicio de E/S (SWI RegisterRamReset, bandera 0x20 = bloque SIO). */
    void reset() noexcept {
        rcnt_ = 0; siocnt_ = 0; data32_ = 0; data8_ = 0; send_multi_ = 0;
        multi_.fill(0); error_ = false;
        active_ = false; master_ = false; exchanged_ = false;
        phase_ = 0; bits_ = 0; position_ = 0; cycles_ = 0;
        send_ = 0; recv_ = 0; resp_ = 0;
        // El enlace físico NO se reinicia con la E/S: sigue cableado.
        push_state();
    }

    // ── Enlace ──────────────────────────────────────────────────────────────

    [[nodiscard]] bool connect(ravenemu::LinkEndpoint* endpoint) noexcept {
        if (endpoint_ == endpoint) return true;
        if (endpoint != nullptr && !endpoint->attach(*this)) return false;
        if (endpoint_) endpoint_->detach(*this);
        endpoint_ = endpoint;
        // El papel no cambia en vida del enlace; lo dice el transporte.
        parent_ = endpoint == nullptr || endpoint->is_parent();
        push_state();
        return true;
    }
    void disconnect() noexcept {
        if (endpoint_) endpoint_->detach(*this);
        endpoint_ = nullptr;
    }

    // ── LinkPort (lo que el transporte ve de este puerto) ───────────────────

    /**
     * Bit que este puerto presenta en la línea en este instante. Reglas, por
     * orden de prioridad:
     *   • Durante una transferencia padre normal, el bit propio; en hijo multi
     *     dentro de SU trama (posición 18-35) el marco de 18 bits, y fuera
     *     de ella reposo alto.
     *   • En modo multi sin transferencia, SD en alto: es la señal "estoy en
     *     multi" que GBATEK describe, y un padre que muestrea un par en otro
     *     modo recibe la trama inválida y acaba en FFFFh, como en hardware.
     *   • En modo normal sin transferencia, el nivel de SO (bit 3), que es
     *     exactamente lo que el maestro mira en su bit 2 (SI) antes de
     *     arrancar.
     */
    [[nodiscard]] bool outgoing_bit_high() const noexcept override {
        if (active_ && mode() == Mode::multi && !parent_) return child_output_bit(position_);
        if (active_ && mode() == Mode::normal && !master_) {
            return ((send_ >> (bits_ - 1 - position_)) & 1U) != 0;
        }
        if (mode() == Mode::multi) return true;
        return (siocnt_ & 8U) != 0;
    }

    /**
     * Pulsos de reloj externos. En normal los acepta sólo el esclavo
     * (arrancado con bit7=1 y reloj externo); el maestro que genera su
     * propio reloj los ignora. En multi el primer pulso abre la recepción
     * del hijo: pone ocupado, reinicia SIOMULTI a FFFFh y a partir de ahí
     * cada pulso avanza la posición dentro del esquema de 36 bits.
     */
    void clock_external_bit(bool incoming_high) noexcept override {
        if (mode() == Mode::normal) {
            if (!active_ || master_) return;
            recv_ = (recv_ << 1U) | (incoming_high ? 1U : 0U);
            ++position_;
            if (position_ >= bits_) complete_normal();
            return;
        }
        if (mode() != Mode::multi) return;
        if (!active_) {
            if (parent_) return; // el padre no recibe relojes ajenos
            begin_child_multi();
        }
        // Trama del padre: posiciones 0-17; sus datos caen en 1-16.
        if (position_ >= 1 && position_ <= 16) {
            multi_[0] = std::uint16_t((multi_[0] << 1U) | (incoming_high ? 1U : 0U));
        }
        ++position_;
        if (position_ >= 36) complete_child_multi();
    }

    /** Estado de línea observado por el par: (RCNT << 16) | SIOCNT. */
    [[nodiscard]] std::uint32_t link_state() const noexcept override {
        return (std::uint32_t(rcnt_) << 16U) | siocnt_;
    }

    // ── Guardado instantáneo ────────────────────────────────────────────────
    // 15 palabras, en el mismo orden aquí que en `GbaCore::save_state`. Una
    // transferencia a media máquina se guarda viva: el maestro retoma la fase
    // pendiente y el hijo la posición, que es lo que un juego esperando su
    // IRQ necesita para no colgarse.

    static constexpr int state_words = 15;

    [[nodiscard]] std::array<std::int32_t, state_words> export_state() const noexcept {
        const auto flags = (error_ ? 1U : 0U) | (active_ ? 2U : 0U) | (master_ ? 4U : 0U)
                        | (exchanged_ ? 8U : 0U) | (std::uint32_t(phase_) << 4U);
        return {
            std::int32_t(rcnt_), std::int32_t(siocnt_), std::int32_t(data32_),
            std::int32_t(send_multi_ | std::uint16_t(data8_) << 16U),
            std::int32_t(multi_[0]), std::int32_t(multi_[1]),
            std::int32_t(multi_[2]), std::int32_t(multi_[3]),
            std::int32_t(flags), std::int32_t(bits_), std::int32_t(position_),
            std::int32_t(cycles_), std::int32_t(send_), std::int32_t(recv_),
            std::int32_t(resp_),
        };
    }

    void import_state(std::span<const std::int32_t> state) {
        if (state.size() != state_words) throw SaveStateError("Estado instantáneo SIO corrupto");
        const auto bounded = [&state](std::size_t index, std::int32_t minimum, std::int32_t maximum) {
            const auto value = state[index];
            if (value < minimum || value > maximum) throw SaveStateError("Estado instantáneo SIO corrupto");
            return value;
        };
        rcnt_ = std::uint16_t(bounded(0, 0, 0xffff));
        siocnt_ = std::uint16_t(bounded(1, 0, 0xffff));
        data32_ = std::uint32_t(state[2]);
        // Palabra 3: send_multi_ en los 16 bits bajos y data8_ en 16-23.
        send_multi_ = std::uint16_t(bounded(3, 0, 0x00ffffff));
        data8_ = std::uint8_t(std::uint32_t(state[3]) >> 16U);
        for (int i = 0; i < 4; ++i) multi_[i] = std::uint16_t(bounded(4 + i, 0, 0xffff));
        const auto flags = std::uint32_t(bounded(8, 0, 0xff));
        bits_ = bounded(9, 0, 32);
        position_ = bounded(10, 0, 64);
        cycles_ = bounded(11, -10'000'000, 10'000'000);
        send_ = std::uint32_t(state[12]);
        recv_ = std::uint32_t(state[13]);
        resp_ = std::uint32_t(state[14]);
        if (bits_ != 0 && bits_ != 8 && bits_ != 18 && bits_ != 32) {
            throw SaveStateError("Estado instantáneo SIO corrupto");
        }
        error_ = (flags & 1U) != 0;
        active_ = (flags & 2U) != 0;
        master_ = (flags & 4U) != 0;
        exchanged_ = (flags & 8U) != 0;
        phase_ = int((flags >> 4U) & 3U);
        // Un estado activo sólo puede pertenecer a los dos modos que
        // transmiten; el resto es imposible y denuncia el archivo.
        if (active_) {
            const auto restored = decode_mode(rcnt_, siocnt_);
            if (restored != Mode::normal && restored != Mode::multi) {
                throw SaveStateError("Estado instantáneo SIO corrupto");
            }
        }
    }

private:
    static constexpr int no_event = std::numeric_limits<int>::max();

    static Mode decode_mode(std::uint16_t rcnt, std::uint16_t siocnt) noexcept {
        if ((rcnt & 0x8000U) != 0) return (rcnt & 0x4000U) != 0 ? Mode::joybus : Mode::general_purpose;
        const auto select = (siocnt >> 12) & 3U;
        if (select <= 1U) return Mode::normal;
        return select == 2U ? Mode::multi : Mode::uart;
    }
    [[nodiscard]] Mode mode() const noexcept { return decode_mode(rcnt_, siocnt_); }
    [[nodiscard]] static std::uint32_t mask_bits(int bits) noexcept {
        return bits >= 32 ? 0xffffffffU : ((1U << bits) - 1U);
    }

    // ── Lecturas calculadas ────────────────────────────────────────────────

    /**
     * Estado del par según el último `state_changed` que el transporte
     * conserva. Sin par conectado todo responde como hardware sin cable:
     * SI en alto (GBATEK: "1 = High/None") y "todos listos" a cero.
     */
    [[nodiscard]] bool peer_present() const noexcept { return endpoint_ && endpoint_->peer_present(); }
    [[nodiscard]] std::uint16_t peer_siocnt() const noexcept {
        return endpoint_ ? std::uint16_t(endpoint_->peer_state() & 0xffffU) : 0U;
    }
    [[nodiscard]] std::uint16_t peer_rcnt() const noexcept {
        return endpoint_ ? std::uint16_t(endpoint_->peer_state() >> 16U) : 0U;
    }
    [[nodiscard]] Mode peer_mode() const noexcept { return decode_mode(peer_rcnt(), peer_siocnt()); }
    [[nodiscard]] bool all_ready() const noexcept {
        return mode() == Mode::multi && peer_present() && peer_mode() == Mode::multi;
    }
    /** Nivel de la línea SI: terminal del padre/hijo en multi, SO del par en normal. */
    [[nodiscard]] bool si_high() const noexcept {
        if (mode() == Mode::multi) return !parent_;
        if (!peer_present()) return true;
        if (peer_mode() != Mode::normal) return true;
        return (peer_siocnt() & 8U) != 0;
    }
    /** Nivel de la línea SO propio. */
    [[nodiscard]] bool so_high() const noexcept {
        if (mode() == Mode::multi) return parent_;
        return (siocnt_ & 8U) != 0;
    }

    [[nodiscard]] int read_siocnt() const noexcept {
        // Se ensucian sólo los bits de estado (2 = SI, 7 = ocupado); el resto
        // vuelve tal cual se escribió, que es como se comporta el hardware.
        auto value = siocnt_ & ~0x0084U;
        if (active_) value |= 0x0080U;
        const auto current = mode();
        if (current == Mode::multi) {
            value &= ~0x0078U; // bits 3-6: SD, ID y error, todos calculados
            if (all_ready()) value |= 0x0008U;
            if (!parent_) value |= 0x0014U; // ID = 1 (hijo) y terminal SI = 1
            if (error_) value |= 0x0040U;
        } else if (current == Mode::normal) {
            if (si_high()) value |= 0x0004U;
        }
        return int(value & 0xffffU);
    }

    [[nodiscard]] int read_rcnt() const noexcept {
        if ((rcnt_ & 0x8000U) != 0) return int(rcnt_); // propósito general: tal cual
        int value = int(rcnt_ & 0xc000U);
        if (!active_) value |= 1;                          // SC en reposo alto
        if (all_ready()) value |= 2;                       // SD alto con todos en multi
        if (!si_high()) value |= 4;
        if (so_high()) value |= 8;
        return value;
    }

    // ── Escrituras de control ──────────────────────────────────────────────

    void write_siocnt(int raw) noexcept {
        const auto next = std::uint16_t(raw & 0xffff);
        const auto before = mode();
        siocnt_ = next;
        const auto after = mode();
        if (after != before && active_) cancel();
        if (after == Mode::normal) {
            if ((next & 0x80U) != 0) {
                if (!active_) begin_normal((next & 1U) != 0);
            } else if (active_) cancel();
        } else if (after == Mode::multi) {
            if ((next & 0x80U) != 0) {
                // El bit 7 es de sólo escritura en el hijo: su ocupado lo
                // pone el reloj del padre, no su juego.
                if (!active_ && parent_) begin_master_multi();
            } else if (active_ && parent_) cancel();
        }
        push_state();
    }

    void write_rcnt(int raw) noexcept {
        const auto before = mode();
        // En modos SIO los bits 0-13 son estados de línea (sólo lectura);
        // en propósito general el registro es enteramente escribible.
        rcnt_ = (raw & 0x8000) != 0 ? std::uint16_t(raw & 0xffff) : std::uint16_t(raw & 0xc000);
        if (mode() != before && active_) cancel();
        push_state();
    }

    void push_state() noexcept { if (endpoint_) endpoint_->state_changed(link_state()); }

    // ── Máquina de transferencia ───────────────────────────────────────────

    void begin_normal(bool internal_clock) noexcept {
        active_ = true;
        master_ = internal_clock;
        exchanged_ = false;
        position_ = 0;
        recv_ = 0;
        bits_ = (siocnt_ & 0x1000U) != 0 ? 32 : 8;
        send_ = bits_ == 32 ? data32_ : data8_;
        cycles_ = 0; // el maestro intercambia en el primer tick
    }

    void begin_master_multi() noexcept {
        active_ = true;
        master_ = true;
        exchanged_ = true; // multi no usa la fase de intercambio de normal
        phase_ = 0;
        position_ = 0;
        send_ = send_multi_;
        multi_.fill(0xffff);
        error_ = false;
        cycles_ = 0;
    }

    void begin_child_multi() noexcept {
        active_ = true;
        master_ = false;
        exchanged_ = false;
        position_ = 0;
        send_ = send_multi_;
        multi_.fill(0xffff);
        error_ = false;
    }

    void cancel() noexcept {
        active_ = false;
        master_ = false;
        exchanged_ = false;
        phase_ = 0;
        position_ = 0;
        bits_ = 0;
        cycles_ = 0;
    }

    /**
     * Intercambio de palabra con el par, o ausencia de par. Sin endpoint o
     * sin transporte por palabra, el enlace se trata como ausente y llegan
     * todos los bits en alto —el mismo resultado que un timeout de recepción
     * en GBATEK, FFFFh—.
     */
    void exchange(std::uint32_t outgoing, int bits, std::uint32_t& incoming, bool& ready) noexcept {
        incoming = mask_bits(bits);
        ready = false;
        if (!endpoint_) return;
        if (!endpoint_->exchange_word(*this, outgoing, bits, incoming, ready)) {
            incoming = mask_bits(bits);
            ready = false;
            return;
        }
        if (!ready) incoming = mask_bits(bits);
    }

    void advance() noexcept {
        if (mode() == Mode::multi) advance_multi();
        else advance_normal();
    }

    /** Maestro normal: una sola ida y vuelta al arrancar, luego un bit por período. */
    void advance_normal() noexcept {
        if (!exchanged_) {
            exchanged_ = true;
            bool ready = false;
            exchange(send_, bits_, resp_, ready);
            (void)ready;
            cycles_ += period_normal();
            return;
        }
        resp_ &= mask_bits(bits_); // por si vino truncado
        const auto bit = (resp_ >> (bits_ - 1 - position_)) & 1U;
        recv_ = (recv_ << 1U) | bit;
        ++position_;
        if (position_ >= bits_) { complete_normal(); return; }
        cycles_ += period_normal();
    }

    /**
     * Maestro multi: trama propia → retardo → trama del hijo → timeout, todo
     * en períodos de bit. Los intercambios ocurren al entrar en la fase, así
     * que la latencia de red sólo estira el tiempo del retardo, no la
     * cadencia de los bits.
     */
    void advance_multi() noexcept {
        if (phase_ == 0) {
            // La trama propia se entrega y se sigue: lo que conteste el par aquí
            // se descarta (el hijo aún no tiene trama propia, porque necesita
            // la del maestro antes), así que esperar su respuesta sería pagar
            // una ida y vuelta de la red —~7-8 ms entre dos móviles— por cada
            // transferencia sin ganar nada. Con `push` el par sigue relojando
            // los bits, que es lo único que esta fase necesita.
            const bool pushed = endpoint_ && endpoint_->push_word(*this, (send_ << 1U) | 1U, 18);
            if (!pushed) {
                std::uint32_t ignored = 0; bool ready = false;
                exchange((send_ << 1U) | 1U, 18, ignored, ready);
                (void)ignored; (void)ready;
            }
            phase_ = 1;
            cycles_ += 18 * period_multi();
        } else if (phase_ == 1) {
            std::uint32_t frame = 0; bool ready = false;
            exchange(mask_bits(18), 18, frame, ready);
            // Trama válida: arranque en bit 17 = 0 y parada en bit 0 = 1.
            // Sin par o en modo ajeno llega todo alto (o todo bajo), falla la
            // validación y la entrada queda en FFFFh con error, como en GBATEK.
            // Se guarda ya en SIOMULTI1 para que un guardado entre fases no
            // pierda la respuesta.
            const bool valid = ready && (frame & (1U << 17U)) == 0 && (frame & 1U) != 0;
            multi_[1] = valid ? std::uint16_t((frame >> 1U) & 0xffffU) : 0xffffU;
            error_ = !valid;
            phase_ = 2;
            cycles_ += 18 * period_multi();
        } else {
            multi_[0] = std::uint16_t(send_);
            multi_[2] = 0xffff;
            multi_[3] = 0xffff;
            complete_common();
        }
    }

    void complete_normal() noexcept {
        if (bits_ == 32) data32_ = recv_;
        else data8_ = std::uint8_t(recv_);
        complete_common();
    }

    void complete_child_multi() noexcept {
        multi_[1] = std::uint16_t(send_);
        complete_common();
    }

    void complete_common() noexcept {
        active_ = false;
        master_ = false;
        exchanged_ = false;
        phase_ = 0;
        position_ = 0;
        cycles_ = 0;
        if ((siocnt_ & 0x4000U) != 0) interrupts_.request(InterruptController::serial);
    }

    /** Bit propio del hijo en la posición `position` del esquema de 36. */
    [[nodiscard]] bool child_output_bit(int position) const noexcept {
        const auto in_frame = position - 18;
        if (in_frame < 0 || in_frame > 17) return true; // fuera de SU trama: reposo
        if (in_frame == 0) return false;                // arranque
        if (in_frame == 17) return true;                // parada
        return ((send_ >> (16 - in_frame)) & 1U) != 0;  // datos, MSB primero
    }

    [[nodiscard]] int period_normal() const noexcept { return (siocnt_ & 2U) != 0 ? 8 : 66; }
    [[nodiscard]] int period_multi() const noexcept {
        constexpr int periods[4] = {1748, 437, 292, 145}; // 16 777 216 Hz / baud
        return periods[siocnt_ & 3U];
    }

    InterruptController& interrupts_;
    ravenemu::LinkEndpoint* endpoint_{};
    bool parent_{true};

    std::uint16_t rcnt_{};
    std::uint16_t siocnt_{};
    std::uint32_t data32_{};
    std::uint16_t send_multi_{};
    std::uint8_t data8_{};
    std::array<std::uint16_t, 4> multi_{};

    bool error_{};
    bool active_{};
    bool master_{};
    bool exchanged_{};
    int phase_{};
    int bits_{};
    int position_{};
    int cycles_{};
    std::uint32_t send_{};
    std::uint32_t recv_{};
    std::uint32_t resp_{};
};

} // namespace ravenemu::gba
