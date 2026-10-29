// ── Autotest del SIO de GBA ─────────────────────────────────────────────────
//
// Prueba de vida del módulo `serial/serial.hpp` sin pasar por el emulador
// completo: dos puertos enfrentados por un par de endpoints de mentira que
// hacen de cable (intercambio síncrono por palabra, mismo contrato que la
// red real) y aserciones sobre registros, IRQ, tiempos y guardado.
//
// No forma parte de la app ni de CMake: se compila a mano con el clang del
// NDK y se ejecuta en el dispositivo, porque en Windows no hay compilador de
// host y los .so de la app sólo se cargan desde una JVM. Compilar y correr
// (desde `app/src/main/cpp`):
//
//   NDK clang++ --target=aarch64-linux-android24 -std=c++20 -O1 ^
//     -I ravenemu\gba\src -I ravenemu\gba\include -I ravenemu\common\include ^
//     tests\serial_selftest.cpp -o serial_selftest
//   adb push serial_selftest /data/local/tmp/
//   adb shell /data/local/tmp/serial_selftest
//
// Devuelve 0 y "ok" por prueba si todo pasa; el primer fallo aborta con la
// prueba y la línea marcadas.
//
// El endpoint simulado sincroniza por EXCHANGE de palabra, no por hilos: en
// la vida real el extremo receptor lo hace `service` desde su tick, pero el
// código que examina los bits es exactamente el mismo (`clock_external_bit`
// y `outgoing_bit_high`), sólo cambia quién los llama.

#include "serial/serial.hpp"

#include <cstdio>
#include <cstdlib>
#include <limits>

namespace {

int g_checks = 0;
const char* g_test = "(ninguna)";

void check(bool condition, const char* what, int line) {
    ++g_checks;
    if (!condition) {
        std::fprintf(stderr, "FALLO en %s (linea %d): %s\n", g_test, line, what);
        std::exit(1);
    }
}
#define CHECK(expr) check((expr), #expr, __LINE__)

/**
 * Cable de mentira entre DOS endpoints —uno por "dispositivo", igual que en
 * la red real: cada extremo tiene el suyo y sabe si es padre. Si no hay par,
 * `exchange_word` devuelve `peer_ready = false`, que `Serial` traduce a
 * todos-uns (FFFFh), exactamente como un timeout de recepción en GBATEK.
 */
class MockEndpoint final : public ravenemu::LinkEndpoint {
public:
    explicit MockEndpoint(bool parent) : parent_(parent) {}

    void pair(MockEndpoint* other) noexcept { peer_ = other; }

    [[nodiscard]] bool attach(ravenemu::LinkPort& port) noexcept override {
        if (port_ != nullptr && port_ != &port) return false;
        port_ = &port;
        return true;
    }
    void detach(ravenemu::LinkPort& port) noexcept override {
        if (port_ == &port) port_ = nullptr;
    }

    // El SIO de GBA nunca usa el intercambio bit a bit (usa `exchange_word`);
    // se implementa igualmente por coherencia con el contrato de la interfaz.
    [[nodiscard]] bool exchange_bit(ravenemu::LinkPort&, bool outgoing_high) noexcept override {
        auto* other = peer_port();
        if (other == nullptr) return true; // sin par: alto
        const bool incoming = other->outgoing_bit_high();
        other->clock_external_bit(outgoing_high);
        return incoming;
    }

    [[nodiscard]] bool exchange_word(ravenemu::LinkPort&, std::uint32_t outgoing, int bits,
                                     std::uint32_t& incoming, bool& peer_ready) noexcept override {
        auto* other = peer_port();
        if (other == nullptr) { peer_ready = false; return true; }
        std::uint32_t response = 0;
        for (int index = 0; index < bits; ++index) {
            const auto bit = bits - 1 - index; // MSB primero, como el hardware
            if (other->outgoing_bit_high()) response |= 1U << bit;
            other->clock_external_bit(((outgoing >> bit) & 1U) != 0);
        }
        incoming = response;
        peer_ready = true;
        return true;
    }

    [[nodiscard]] bool peer_present() const noexcept override { return peer_port() != nullptr; }
    [[nodiscard]] std::uint32_t peer_state() const noexcept override {
        const auto* other = peer_port();
        return other != nullptr ? other->link_state() : 0;
    }
    [[nodiscard]] bool is_parent() const noexcept override { return parent_; }

private:
    [[nodiscard]] ravenemu::LinkPort* peer_port() const noexcept {
        if (peer_ == nullptr || peer_->port_ == nullptr) return nullptr;
        return peer_->port_;
    }
    MockEndpoint* peer_{};
    ravenemu::LinkPort* port_{};
    bool parent_{};
};

/** Un "dispositivo": su controlador de IRQ, su serial y su endpoint. */
struct Device {
    explicit Device(bool parent) : endpoint(parent) {}
    ravenemu::gba::InterruptController interrupts;
    ravenemu::gba::Serial serial{interrupts};
    MockEndpoint endpoint;

    void wire(Device& other) noexcept {
        endpoint.pair(&other.endpoint);
        other.endpoint.pair(&endpoint);
    }
    void connect() { CHECK(serial.connect(&endpoint)); }
    void write(int offset, int value) { serial.write(offset, value); }
    [[nodiscard]] int read(int offset) { return serial.read(offset); }
    void siocnt(int value) { serial.write(0x128, value); }
    [[nodiscard]] int siocnt() { return serial.read(0x128); }
    [[nodiscard]] bool busy() { return (siocnt() & 0x80) != 0; }
    [[nodiscard]] bool irq() { return (interrupts.flags & (1 << 7)) != 0; }
};

void run(const char* name, void (*body)()) {
    g_test = name;
    body();
    std::printf("ok  %s\n", name);
}

// ── Pruebas ────────────────────────────────────────────────────────────────

// Normal 8 bit con apretón de mano de SO/SI: el esclavo publica SO en alto,
// el maestro lo ve en su bit 2; el esclavo baja SO ("listo"), el maestro lo
// ve y arranca. Al terminar, ambos tienen sus datos cruzados y su IRQ serie.
void test_normal8_handshake() {
    Device master(false), slave(false);
    master.wire(slave);
    master.connect(); slave.connect();

    slave.write(0x12a, 0x5a);
    // bit7 arranque + bit0 reloj externo + bit3 SO en alto + bit14 IRQ = 0x4088
    slave.siocnt(0x4088);
    CHECK(slave.busy()); // esperando reloj externo

    CHECK((master.siocnt() & 0x04) != 0); // SI ve el SO alto del esclavo
    slave.siocnt(0x4080);                 // SO bajo = listo (arranque intacto)
    CHECK(slave.busy());
    CHECK((master.siocnt() & 0x04) == 0); // SI ve el SO bajo

    master.write(0x12a, 0xa7);
    master.siocnt(0x4081); // arranque + reloj interno + IRQ
    CHECK(master.busy());

    master.serial.tick(1); // primer tick: intercambio de palabra
    CHECK(!slave.busy());  // el esclavo ya terminó (sus bits corrieron en el exchange)
    CHECK(slave.read(0x12a) == 0xa7);
    CHECK(slave.irq());
    CHECK(master.busy());          // el maestro aún desplaza a su cadencia
    CHECK(!master.irq());
    CHECK((slave.siocnt() & 0x80) == 0);

    master.serial.tick(520); // 7 de 8 bits (período de 66 ciclos)
    CHECK(master.busy());
    master.serial.tick(66); // último bit
    CHECK(!master.busy());
    CHECK(master.read(0x12a) == 0x5a);
    CHECK(master.irq());
    CHECK((master.siocnt() & 0x04) == 0); // SO del esclavo sigue bajo
}

// Normal 32 bit: los datos viven en SIODATA32 (0x120/0x122) y la longitud la
// decide el bit 12 de SIOCNT en el mismo escrito de arranque.
void test_normal32() {
    Device master(false), slave(false);
    master.wire(slave);
    master.connect(); slave.connect();

    slave.write(0x120, 0x3344);
    slave.write(0x122, 0x1122);
    slave.siocnt(0x5080); // bit12 (32 bit) + arranque + externo + IRQ
    CHECK(slave.busy());

    master.write(0x120, 0xbeef);
    master.write(0x122, 0xdead);
    master.siocnt(0x5081); // interno
    master.serial.tick(1);
    CHECK(slave.read(0x120) == 0xbeef);
    CHECK(slave.read(0x122) == 0xdead);
    CHECK(slave.irq());

    master.serial.tick(66 * 32);
    CHECK(!master.busy());
    CHECK(master.read(0x120) == 0x3344);
    CHECK(master.read(0x122) == 0x1122);
    CHECK(master.irq());
}

// Multi con las dos unidades: el padre manda su trama, espera el retardo,
// recoge la del hijo; al final cada uno ve los datos de todos en SIOMULTI0-3
// y los bits de estado (ID, terminal, "todos listos") son los que GBATEK
// describe. Se comprueba también el esquema de 18 bits: arranque 0, datos,
// parada 1.
void test_multi_dos_unidades() {
    Device parent(true), child(false);
    parent.wire(child);
    parent.connect(); child.connect();

    // Primero el modo, después SIOMLT_SEND: en modo normal 0x12A es
    // SIODATA8 y el dato de 16 bits se perdería.
    parent.siocnt(0x2003); // modo multi (bit13) + baudaje 115200, sin arranque
    parent.write(0x12a, 0xbeef);
    child.siocnt(0x6000); // hijo en multi con IRQ serie
    child.write(0x12a, 0x1234);

    CHECK((parent.siocnt() & 0x08) != 0); // SD: ambos en multi -> listos
    CHECK((child.siocnt() & 0x08) != 0);
    CHECK((parent.siocnt() & 0x34) == 0); // padre: ID 0, terminal 0
    CHECK((child.siocnt() & 0x34) == 0x14); // hijo: ID 1 (bit4) y terminal 1 (bit2)

    parent.siocnt(0x6083); // arranque + IRQ + baudaje 3
    CHECK(parent.busy());
    CHECK(!child.busy()); // el hijo aún no recibió nada

    parent.serial.tick(1); // fase 0: trama del padre (18 bits al hijo)
    CHECK(child.busy());   // el hijo arrancó, pero su trama aún no llegó
    parent.serial.tick(18 * 145); // retardo: 18 períodos de bit a 115200
    CHECK(!child.busy());         // fase 1 recogida: el hijo terminó ya
    CHECK(child.irq());
    CHECK(child.read(0x120) == 0xbeef); // datos del padre muestreados
    CHECK(child.read(0x122) == 0x1234); // su propio dato en su slot
    CHECK(child.read(0x124) == 0xffff && child.read(0x126) == 0xffff);
    CHECK(parent.busy()); // al padre le queda el timeout

    parent.serial.tick(18 * 145);
    CHECK(!parent.busy());
    CHECK(parent.irq());
    CHECK(parent.read(0x120) == 0xbeef);
    CHECK(parent.read(0x122) == 0x1234);
    CHECK(parent.read(0x124) == 0xffff && parent.read(0x126) == 0xffff);
    CHECK((parent.siocnt() & 0x40) == 0); // sin error: hubo trama válida
}

// Multi sin hijo: GBATEK manda que la entrada del hijo quede en FFFFh y se
// levante el bit de error. "Todos listos" nunca se activa sin par, que es
// lo que impide que un juego solo arranque transferencias.
void test_multi_sin_hijo() {
    Device solo(true);
    solo.siocnt(0x2003);
    solo.write(0x12a, 0xcafe);
    CHECK((solo.siocnt() & 0x08) == 0); // sin par: nada listo

    solo.siocnt(0x6083);
    solo.serial.tick(1);
    solo.serial.tick(18 * 145);
    solo.serial.tick(18 * 145);
    CHECK(!solo.busy());
    CHECK(solo.irq());
    CHECK(solo.read(0x120) == 0xcafe);
    CHECK(solo.read(0x122) == 0xffff); // sin hijo: FFFFh
    CHECK((solo.siocnt() & 0x40) != 0); // error de conexión
}

// Tiempos: el maestro no termina antes de longitud x período, y sin
// transferencia el planificador no ve ningún evento. Sin par, los bits
// recibidos valen todos 1 (timeout de GBATEK).
void test_tiempo_y_ausencia_de_par() {
    Device solo(false);
    solo.write(0x12a, 0x11);
    CHECK(solo.serial.cycles_until_next_event() == std::numeric_limits<int>::max());
    solo.siocnt(0x4081);
    CHECK(solo.serial.cycles_until_next_event() == 1); // arranque pendiente

    solo.serial.tick(1); // intercambio: consume el ciclo de este tick
    CHECK(solo.serial.cycles_until_next_event() == 65); // 66 menos el ciclo consumido
    CHECK(solo.busy());
    solo.serial.tick(520);
    CHECK(solo.busy()); // siete de ocho bits
    solo.serial.tick(66);
    CHECK(!solo.busy());
    CHECK(solo.read(0x12a) == 0xff); // sin par: todos los bits en alto
    CHECK(solo.serial.cycles_until_next_event() == std::numeric_limits<int>::max());
}

// RCNT: en propósito general el registro es escribible entero; en modo SIO
// sólo el selector de modo (bits 15-14) y los bits 0-3 son estados de línea.
// Cambiar de modo a mitad de transferencia la cancela.
void test_rcnt_y_cambio_de_modo() {
    Device solo(false);
    solo.write(0x134, 0x80ff);
    CHECK(solo.read(0x134) == 0x80ff); // propósito general: tal cual

    solo.write(0x134, 0x0000); // vuelta a modo SIO
    CHECK((solo.read(0x134) & 0x01) != 0); // SC en reposo alto

    solo.write(0x12a, 0x22);
    solo.siocnt(0x4081);
    CHECK(solo.busy());
    solo.write(0x134, 0x8000); // cambio de modo -> cancela
    CHECK(!solo.busy());
}

// Guardado instant\u00eneo: exportar, importar y volver a exportar devuelve lo
// mismo; un estado con longitudes imposibles se rechaza con SaveStateError.
void test_guardado() {
    Device master(false), slave(false);
    master.wire(slave);
    master.connect(); slave.connect();

    slave.write(0x12a, 0x77);
    slave.siocnt(0x4080);
    master.write(0x12a, 0x88);
    master.siocnt(0x4081);
    master.serial.tick(1);
    master.serial.tick(66 * 8);

    const auto saved = master.serial.export_state();
    master.serial.reset();
    CHECK(master.read(0x12a) == 0);
    master.serial.import_state(saved);
    const auto restored = master.serial.export_state();
    CHECK(saved == restored);
    CHECK(master.read(0x12a) == 0x77); // el dato volvió con el estado

    auto corrupt = saved;
    corrupt[9] = 7; // longitud imposible
    bool rejected = false;
    try {
        master.serial.import_state(corrupt);
    } catch (const ravenemu::SaveStateError&) {
        rejected = true;
    }
    CHECK(rejected);
}

// El reset de E/S (SWI RegisterRamReset, bandera 0x20) deja los registros a
// cero y la transferencia cancelada, pero NO toca el enlace cableado.
void test_reset() {
    Device master(false), slave(false);
    master.wire(slave);
    master.connect(); slave.connect();

    master.write(0x12a, 0x99);
    master.siocnt(0x4081);
    master.serial.tick(1);
    CHECK(master.busy());

    master.serial.reset();
    CHECK(!master.busy());
    CHECK(master.read(0x120) == 0);
    CHECK(master.read(0x12a) == 0);
    // SIO: SC en reposo alto (1) y SI en bajo (4) porque el par sigue
    // cableado y su SO por defecto es 0.
    CHECK(master.read(0x134) == 0x0005);
    // El par sigue cableado: su SO se sigue reflejando en SI.
    slave.write(0x12a, 0x01);
    slave.siocnt(0x4088); // SO en alto
    CHECK((master.siocnt() & 0x04) != 0);
    slave.siocnt(0x4080); // SO en bajo
    CHECK((master.siocnt() & 0x04) == 0);
}

} // namespace

int main() {
    // Sin búfer: si una prueba aborta, el diagnóstico previo no se pierde.
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    run("normal8_handshake", test_normal8_handshake);
    run("normal32", test_normal32);
    run("multi_dos_unidades", test_multi_dos_unidades);
    run("multi_sin_hijo", test_multi_sin_hijo);
    run("tiempo_y_ausencia_de_par", test_tiempo_y_ausencia_de_par);
    run("rcnt_y_cambio_de_modo", test_rcnt_y_cambio_de_modo);
    run("guardado", test_guardado);
    run("reset", test_reset);
    std::printf("OK (%d comprobaciones)\n", g_checks);
    return 0;
}
