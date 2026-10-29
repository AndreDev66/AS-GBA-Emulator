#pragma once

#include "cpu/cpu.hpp"
#include "memory/memory_bus.hpp"
#include "cartridge/cartridge_factory.hpp"
#include "infrared/machine_infrared_port.hpp"
#include <ravenemu/gb/hardware_mode.hpp>
#include <ravenemu/gbc/infrared_port.hpp>

namespace ravenemu::cgb {

class Machine {
public:
    Machine(RomImage rom, Cartridge::Clock clock, gb::HardwareMode mode,
            std::span<const std::uint8_t> boot_rom_image = {})
        : infrared_router(), cartridge(Cartridge::create(rom, std::move(clock))), hardware_mode(mode),
          boot_rom(mode, boot_rom_image),
          timer(interrupts, mode), joypad(interrupts), speed(gb::cgb_features_enabled(
              gb::boot_execution_mode(mode, !boot_rom_image.empty()))),
          serial(interrupts, gb::cgb_features_enabled(
              gb::boot_execution_mode(mode, !boot_rom_image.empty()))),
          infrared(gb::cgb_features_enabled(
              gb::boot_execution_mode(mode, !boot_rom_image.empty()))),
          ppu(interrupts, gb::boot_execution_mode(mode, !boot_rom_image.empty())), apu(mode),
          bus(*cartridge, ppu, interrupts, timer, serial, joypad, apu,
              gb::boot_execution_mode(mode, !boot_rom_image.empty()), mode,
              speed, infrared, boot_rom),
          cpu(bus, interrupts, gb::is_cgb_hardware(mode)) {
        if (!infrared.connect(&infrared_router) ||
            !cartridge->connect_infrared_endpoint(&infrared_router)) {
            throw std::logic_error("Routage infrarouge interne GB/GBC impossible");
        }
        if (boot_rom.supplied()) {
            // Le firmware fourni doit observer l'état de mise sous tension,
            // pas les valeurs HLE laissées normalement à l'entrée 0100.
            interrupts.flags = 0;
            interrupts.enable = 0;
            timer.reset_for_boot_rom();
            ppu.reset_for_boot_rom();
            apu.reset_for_boot_rom();
            cpu.set_af(0); cpu.set_bc(0); cpu.set_de(0); cpu.set_hl(0);
            cpu.sp = 0; cpu.pc = 0;
        } else {
            initialize_hle_post_boot();
            timer.initialize_hle_post_boot();
            serial.initialize_hle_post_boot(timer.reset_aligned_phase());
            joypad.write(mode == gb::HardwareMode::dmg ? 0x00 : 0x30);
            apu.initialize_hle_post_boot();
            ppu.initialize_hle_post_boot();
            if (mode == gb::HardwareMode::cgb_compatibility) {
                ppu.initialize_hle_compatibility_palettes();
            }
        }
    }

    /**
     * Exécute une tranche et retourne le nombre de dots périphériques écoulés.
     * 0 signifie que la machine est en STOP normal et qu'aucune horloge interne
     * ne doit progresser avant un réveil par le joypad.
     */
    int step() {
        if (speed.switching()) {
            const int dots = std::min(4, speed.switch_dots_remaining());
            return bus.tick_speed_switch(dots);
        }

        static_cast<void>(cpu.step());
        return bus.take_elapsed_dots();
    }

    // Construit avant les ports internes et détruit après eux : leurs
    // destructeurs peuvent ainsi se détacher sans conserver de pointeur mort.
    MachineInfraredPort infrared_router;
    std::unique_ptr<Cartridge> cartridge;
    gb::HardwareMode hardware_mode{gb::HardwareMode::dmg};
    BootRom boot_rom;
    InterruptController interrupts;
    Timer timer;
    Joypad joypad;
    SpeedController speed;
    SerialPort serial;
    InfraredPort infrared;
    Ppu ppu;
    Apu apu;
    MemoryBus bus;
    Cpu cpu;

private:
    void initialize_hle_post_boot() noexcept {
        cpu.sp = 0xfffe;
        cpu.pc = 0x0100;
        switch (hardware_mode) {
        case gb::HardwareMode::dmg: {
            // DMG ABC: H/C depend on the header checksum value left by the
            // boot ROM's final comparison, while Z is set.
            const int flags = cartridge->read_rom(0x014d) == 0 ? 0x80 : 0xb0;
            cpu.set_af(0x0100 | flags);
            cpu.set_bc(0x0013);
            cpu.set_de(0x00d8);
            cpu.set_hl(0x014d);
            break;
        }
        case gb::HardwareMode::cgb_native:
            cpu.set_af(0x1180);
            cpu.set_bc(0x0000);
            cpu.set_de(0xff56);
            cpu.set_hl(0x000d);
            break;
        case gb::HardwareMode::cgb_compatibility: {
            int b_value{};
            const int old_licensee = cartridge->read_rom(0x014b);
            const bool nintendo_licensee = old_licensee == 0x01 ||
                (old_licensee == 0x33 && cartridge->read_rom(0x0144) == 0x30 &&
                 cartridge->read_rom(0x0145) == 0x31);
            if (nintendo_licensee) {
                for (int address = 0x0134; address <= 0x0143; ++address) {
                    b_value = byte(b_value + cartridge->read_rom(address));
                }
            }
            cpu.set_af(0x1180);
            cpu.set_bc(b_value << 8);
            cpu.set_de(0x0008);
            cpu.set_hl(b_value == 0x43 || b_value == 0x58 ? 0x991a : 0x007c);
            break;
        }
        }
    }
};

} // namespace ravenemu::cgb
