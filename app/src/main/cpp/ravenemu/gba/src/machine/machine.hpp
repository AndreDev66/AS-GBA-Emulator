#pragma once

// ── Máquina: propietario de los subsistemas y planificador del frame ─────────
//
// `Machine` es el único dueño de todo (cartridge, bus, ppu, cpu, apu, dma,
// timers, interrupciones, bios) y el único sitio donde se cablean: el bus no
// sabe de quién es la ppu, se le pasa el puntero aquí. Reordenar la creación
// cambia el orden de los callbacks, y el orden es parte del comportamiento.
//
// Bucle de frame con prioridad fija —el más alto gana todo el tiempo:
//   1. DMA en marcha  → el CPU no avanza; sólo corren los periféricos.
//   2. CPU en halt    → se avanza de 64 en 64 ciclos hasta que la BIOS lo despida.
//   3. IRQ pendiente  → se levanta antes de `cpu.step()`, que es lo que espera el juego.
//   4. CPU normal     → `step()` y se le cobran sus ciclos.
//
// `advance_peripherals` no avanza a ciegas: se detiene en el próximo evento
// (desbordamiento de temporizador, muestra de audio o bit serie) para que dos
// sucesos que tienen que coincidir coincidan exactamente, y sólo entonces
// corre ppu/timers/apu/serial.
//
// El frame termina en VCOUNT 0 de la trama siguiente; si una instrucción se
// pasa de la frontera, el excedente se descuenta de la trama siguiente en vez
// de acumularse: eso es lo que evita la deriva de timing a largo plazo.

#include "bios/bios.hpp"
#include "ppu/ppu.hpp"
#include "dma/dma_controller.hpp"
#include "apu/apu.hpp"
#include "timers/timers.hpp"
#include "serial/serial.hpp"

namespace ravenemu::gba {

class Machine {
public:
    Machine(RomImage rom, std::optional<GbaSaveType> forced, std::optional<bool> forced_rtc, Rtc::Clock clock)
        : cartridge(rom, forced, forced_rtc, std::move(clock)), bus(cartridge), ppu(bus),
          serial(interrupts), timers(interrupts), dma(bus, interrupts), cpu(bus), bios(cpu, bus) {
        bus.ppu = &ppu; bus.interrupts = &interrupts; bus.timers = &timers; bus.dma = &dma; bus.apu = &apu;
        bus.serial = &serial;
        ppu.interrupts = &interrupts; ppu.dma = &dma;
        timers.on_overflow = [this](int timer) { apu.timer_overflow(timer); };
        apu.on_fifo_request = [this](int channel) { dma.trigger_sound_fifo(channel); };
        cpu.swi_handler = [this](int number) { bios.handle_swi(number); };
        interrupts.on_request = [this](int mask) { bios.interrupt_raised(mask); bus.diagnostics.interrupt(mask); };
        cpu.reset(i32(0x08000000U));
    }
    void run_frame() {
        // Une instruction peut franchir la frontière PPU. La position courante
        // retranche ce dépassement à la trame suivante au lieu de l'accumuler.
        run_frame(ppu.cycles_until_next_frame());
    }
    void run_frame(int cycles) {
        auto elapsed = 0; bus.diagnostics.begin_frame();
        while (elapsed < cycles) {
            if (dma.active()) {
                const auto dma_cycles = std::min(cycles - elapsed, dma.cycles_until_event());
                // L'effet mémoire survient après que le coût de l'accès s'est
                // écoulé, à la même position temporelle dans tous les périphériques.
                advance_peripherals(dma_cycles); dma.tick(dma_cycles); elapsed += dma_cycles;
                continue;
            }
            if (cpu.state.halted) {
                advance_peripherals(64); elapsed += 64;
                bus.diagnostics.wait_step(64, bios.wait_state() ? bios.wait_state()->interrupt_mask : 0);
                if (bios.should_resume()) { cpu.state.halted = false; bus.diagnostics.wait_resolved(); bus.break_access_sequence(); }
                continue;
            }
            if (interrupts.pending() && !cpu.state.irq_disabled) {
                cpu.raise_exception(CpuState::mode_irq, 0x18, add32(cpu.state.regs[15], 4));
            }
            const auto consumed = cpu.step(); bus.diagnostics.instruction(); advance_peripherals(consumed); elapsed += consumed;
        }
    }
    void advance_peripherals(int cycles) {
        auto remaining = cycles;
        while (remaining > 0) {
            const auto step = std::max(1, std::min({remaining, timers.cycles_until_next_overflow(),
                                                     apu.cycles_until_next_sample(), serial.cycles_until_next_event()}));
            ppu.tick(step); timers.tick(step); apu.tick(step); serial.tick(step); remaining -= step;
        }
    }

    Cartridge cartridge;
    Bus bus;
    Ppu ppu;
    InterruptController interrupts;
    Serial serial;
    Timers timers;
    DmaController dma;
    Apu apu;
    Cpu cpu;
    Bios bios;
};

} // namespace ravenemu::gba
