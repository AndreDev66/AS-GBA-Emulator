// ── Autotest del enlace LAN (fase N2) ──────────────────────────────────────
//
// Comprueba `link/network_link_endpoint.cpp` de punta a punta: dos extremos
// reales de TCP en el mismo equipo (loopback), cada uno con su SIO de GBA y
// su hilo, cruzando una transferencia multiprocesador completa, el empuje
// del estado de línea por la red, la caída del par y una pareja mal formada.
//
// No forma parte de la app ni de CMake: se compila a mano con el clang del
// NDK y se ejecuta en el dispositivo, porque en Windows no hay compilador de
// host y los .so de la app sólo se cargan desde una JVM. Desde
// `app/src/main/cpp`:
//
//   NDK clang++ --target=aarch64-linux-android24 -std=c++20 -O1 ^
//     -I . -I ravenemu\gba\src -I ravenemu\gba\include ^
//     -I ravenemu\common\include tests\link_selftest.cpp ^
//     link\network_link_endpoint.cpp -o link_selftest
//   adb push link_selftest /data/local/tmp/
//   adb shell "chmod 755 /data/local/tmp/link_selftest && /data/local/tmp/link_selftest"
//
// En el dispositivo real son dos teléfonos; aquí cada extremo lleva su hilo
// de emulación, que es justo lo que reproduce el reparto de hilos real: el
// hilo lector del socket nunca toca el puerto, sólo encola.

#include "link/network_link_endpoint.hpp"
#include "serial/serial.hpp"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <thread>

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

/** Un "dispositivo": su controlador de IRQ y su SIO, sin más. */
struct Device {
    ravenemu::gba::InterruptController interrupts;
    ravenemu::gba::Serial serial{interrupts};

    void write(int offset, int value) { serial.write(offset, value); }
    [[nodiscard]] int read(int offset) { return serial.read(offset); }
    void siocnt(int value) { serial.write(0x128, value); }
    [[nodiscard]] int siocnt() { return serial.read(0x128); }
    [[nodiscard]] bool busy() { return (siocnt() & 0x80) != 0; }
    [[nodiscard]] bool irq() { return (interrupts.flags & (1 << 7)) != 0; }
};

bool wait_status(ravenemu::NetworkLinkEndpoint& endpoint, int want, int timeout_ms) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    while (std::chrono::steady_clock::now() < deadline) {
        if (endpoint.status() == want) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    return endpoint.status() == want;
}

void run(const char* name, void (*body)()) {
    g_test = name;
    body();
    std::printf("ok  %s\n", name);
}

// ── Pruebas ────────────────────────────────────────────────────────────────

// Enlace completo por TCP: saludos, estado de línea en los dos sentidos,
// transferencia multiprocesador con las fases temporizadas del padre, y la
// desconexión del hijo — tras la que el padre ve FFFFh y el bit de error,
// que es lo mismo que un cable arrancado en caliente.
void test_enlace_y_caida() {
    constexpr int kPort = 41990;
    ravenemu::NetworkLinkEndpoint server(true, "", kPort); // la sala es el padre
    CHECK(server.open_server() == 0);
    ravenemu::NetworkLinkEndpoint client(false, "127.0.0.1", kPort);
    CHECK(client.open_client(3000) == 0);
    CHECK(wait_status(server, 1, 3000));
    CHECK(wait_status(client, 1, 3000));

    Device parent, child;
    CHECK(parent.serial.connect(&server));
    CHECK(child.serial.connect(&client));

    // Empuje del estado de línea: la escritura de SIOCNT del hijo debe
    // verse reflejada en el padre sin que nadie más haga nada.
    child.siocnt(0x6000); // hijo en modo multiprocesador, con IRQ serie
    bool pushed = false;
    for (int tries = 0; tries < 300 && !pushed; ++tries) {
        pushed = (server.peer_state() & 0xffffU) == 0x6000U;
        if (!pushed) std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    CHECK(pushed);
    CHECK((server.peer_state() >> 16) == 0); // RCNT del hijo sin basura
    CHECK(server.peer_present());

    child.write(0x12a, 0x1234);

    // El hijo sólo lo atiende su "hilo de emulación": mientras el padre
    // empuja fases, éste sólo sirve peticiones (un esclavo no mueve reloj).
    std::atomic<bool> stop_child{false};
    std::thread child_thread([&] {
        while (!stop_child.load(std::memory_order_relaxed)) {
            child.serial.tick(212);
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    });

    parent.siocnt(0x2003); // modo multi, sin arranque todavía
    parent.write(0x12a, 0xbeef);
    parent.siocnt(0x6083); // arranque + IRQ + 115200
    CHECK(parent.busy());

    parent.serial.tick(1);        // fase 0: el intercambio bloquea hasta que el hijo atiende
    parent.serial.tick(18 * 145); // fase 1: la trama del hijo
    parent.serial.tick(18 * 145); // fase 2: cierre por timeout

    CHECK(!parent.busy());
    CHECK(parent.irq());
    CHECK(parent.read(0x120) == 0xbeef);
    CHECK(parent.read(0x122) == 0x1234);
    CHECK(parent.read(0x124) == 0xffff && parent.read(0x126) == 0xffff);
    CHECK((parent.siocnt() & 0x40) == 0); // hubo trama válida: sin error

    stop_child.store(true);
    child_thread.join();

    CHECK(!child.busy());
    CHECK(child.irq());
    CHECK(child.read(0x120) == 0xbeef); // datos del padre, muestreados por la red
    CHECK(child.read(0x122) == 0x1234);

    // El hijo se desconecta: el padre lo nota y, sin par, todo-uns + error.
    client.close_socket();
    CHECK(wait_status(server, 2, 3000));

    parent.siocnt(0x2003);
    parent.write(0x12a, 0x0f0f);
    parent.siocnt(0x6083);
    parent.serial.tick(1);
    parent.serial.tick(18 * 145);
    parent.serial.tick(18 * 145);
    CHECK(!parent.busy());
    CHECK((parent.siocnt() & 0x08) == 0); // sin par: "todos listos" nunca se activa
    CHECK((parent.siocnt() & 0x40) != 0); // error de conexión
    CHECK(parent.read(0x122) == 0xffff);  // sin hijo: FFFFh

    server.close_socket();
}

// Pareja mal formada: dos equipos que ambos se declaran padres se rechazan
// en el saludo. En la app no puede pasar —la sala es servidor y la unión es
// cliente—, pero el protocolo lo verifica por si acaso.
void test_pareja_mal_formada() {
    constexpr int kPort = 41991;
    ravenemu::NetworkLinkEndpoint server(true, "", kPort);
    CHECK(server.open_server() == 0);
    ravenemu::NetworkLinkEndpoint impostor(true, "127.0.0.1", kPort); // también padre
    CHECK(impostor.open_client(3000) == 0);
    CHECK(wait_status(impostor, 2, 3000)); // al ver el saludo del servidor, corta
    CHECK(wait_status(server, 2, 3000));   // y el servidor, al ver el suyo, también
    server.close_socket();
    impostor.close_socket();
}

} // namespace

int main() {
    // Sin búfer: si una prueba aborta, el diagnóstico previo no se pierde.
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    run("enlace_y_caida", test_enlace_y_caida);
    run("pareja_mal_formada", test_pareja_mal_formada);
    std::printf("OK (%d comprobaciones)\n", g_checks);
    return 0;
}
