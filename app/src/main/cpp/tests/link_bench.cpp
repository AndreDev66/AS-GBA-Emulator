// ── Banco de pruebas del coste del enlace (fase N3) ──────────────────────────
//
// Mide lo que el enlace cuesta POR FOTOGRAMA, que es donde se decide si el
// juego va a 60 o a 24 fps. El problema que motivó este banco: `service()`
// se llama desde `Serial::tick`, y el planificador invoca `tick` una vez por
// instrucción de CPU (unas 280 000 por fotograma a 60 fps). Antes, cada una
// de esas llamadas hacía un `exchange` atómico secuencialmente consistente y
// un `lock` de mutex, aunque no hubiera nada que hacer: 10-30 ms de sobrecarga
// por fotograma, es decir, el juego entero.
//
// Qué mide, con el MISMO par de medidas y sin red:
//   1. SIO sin endpoint: la línea base.
//   2. SIO con endpoint **sin par**: sólo el camino de `service` por
//      instrucción, sin ni un paquete. Si esto se acerca a la línea base, el
//      enlace ya no cobra peaje al juego.
//
// No forma parte de la app ni de CMake: se compila a mano con el clang del
// NDK y se ejecuta en el dispositivo, porque en Windows no hay compilador de
// host. Desde `app/src/main/cpp`:
//
//   NDK clang++ --target=aarch64-linux-android24 -std=c++20 -O2 ^
//     -I . -I ravenemu\gba\src -I ravenemu\gba\include ^
//     -I ravenemu\common\include tests\link_bench.cpp ^
//     link\network_link_endpoint.cpp -o link_bench
//   adb push link_bench /data/local/tmp/
//   adb shell "chmod 755 /data/local/tmp/link_bench && /data/local/tmp/link_bench"
//
// El presupuesto: a 60 fps un fotograma son 16 666 us. El enlace en reposo
// puede gastar como mucho una décima de eso (~1 600 us) y el banco falla si
// lo supera, para que una regresión así no pueda colarse sin que se note.

#include "link/network_link_endpoint.hpp"
#include "serial/serial.hpp"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>

namespace {

using Clock = std::chrono::steady_clock;

/** Ciclos emulados por fotograma de GBA: 16.78 MHz / 60 fps. */
constexpr int kCyclesPerFrame = 280000;

/** Tope de gasto del enlace por fotograma, en microsegundos (16 666 / 10). */
constexpr double kBudgetUs = 1600.0;

/**
 * Ejecuta `frames` fotogramas de SIO con `endpoint` conectado (o nulo) y
 * devuelve los microsegundos que costaron.
 *
 * El trabajo emulado es un ciclo cerrado: el maestro normal escribe
 * SIOCNT=0x2000 (inicio), 16 bits de reloj y un pulso de datos, y espera a
 * que el SIO quede libre. Es el mismo bucle cerrado por cada instrucción que
 * hace la máquina, así que mide el coste real del camino `tick -> service`.
 */
double run_frames(ravenemu::gba::Serial& serial, int frames) {
    const auto started = Clock::now();
    for (int frame = 0; frame < frames; ++frame) {
        for (int chunk = 0; chunk < kCyclesPerFrame / 512; ++chunk) {
            serial.tick(512);
        }
    }
    const auto elapsed = Clock::now() - started;
    return std::chrono::duration<double, std::micro>(elapsed).count();
}

double ms_per_frame(double total_us, int frames) {
    return total_us / double(frames) / 1000.0;
}

} // namespace

int main() {
    std::printf("Banco del coste del enlace LAN\n");
    std::printf("  %d ciclos emulados por fotograma, %d fotogramas por pasada\n\n",
                kCyclesPerFrame, 60);

    // ── Línea base: SIO sin endpoint ──────────────────────────────────────
    double baseline_ms = 0.0;
    {
        ravenemu::gba::InterruptController interrupts;
        ravenemu::gba::Serial serial{interrupts};
        serial.tick(kCyclesPerFrame); // calentamiento
        baseline_ms = ms_per_frame(run_frames(serial, 60), 60);
    }

    // ── Con endpoint, sin par: el peaje de `service` ──────────────────────
    // Se abre una sala en un puerto libre y no se conecta nadie: es
    // exactamente el caso "el jugador tiene el enlace abierto pero el otro
    // todavía no ha entrado".
    double with_endpoint_ms = 0.0;
    {
        ravenemu::NetworkLinkEndpoint endpoint(true, "", 17171);
        if (endpoint.open_server() != 0) {
            std::fprintf(stderr, "No se pudo abrir la sala de pruebas\n");
            return 1;
        }
        ravenemu::gba::InterruptController interrupts;
        ravenemu::gba::Serial serial{interrupts};
        if (!serial.connect(&endpoint)) {
            std::fprintf(stderr, "El SIO no acepto el endpoint\n");
            return 1;
        }
        serial.tick(kCyclesPerFrame);
        with_endpoint_ms = ms_per_frame(run_frames(serial, 60), 60);
        serial.disconnect();
        endpoint.close_socket();
    }

    const double cost_us = (with_endpoint_ms - baseline_ms) * 1000.0;

    std::printf("  SIO solo .............. %7.3f ms/frame\n", baseline_ms);
    std::printf("  SIO + enlace sin par .. %7.3f ms/frame\n", with_endpoint_ms);
    std::printf("  Coste del enlace ...... %7.1f us/frame  (presupuesto %.0f us)\n\n",
                cost_us, kBudgetUs);

    if (cost_us > kBudgetUs) {
        std::printf("FALLO: el enlace en reposo gasta %.1f us por fotograma; con 60 fps "
                    "eso son %.1f ms de los 16.7 ms disponibles.\n",
                    cost_us, cost_us / 1000.0);
        return 1;
    }

    std::printf("OK: el enlace no penaliza el fotograma en reposo.\n");
    return 0;
}