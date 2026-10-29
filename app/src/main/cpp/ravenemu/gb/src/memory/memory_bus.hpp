#pragma once

#include "memory/bus.hpp"
#include "cartridge/cartridge.hpp"
#include "apu/apu.hpp"
#include "ppu/ppu.hpp"
#include "timer/timer.hpp"
#include "serial/serial_port.hpp"
#include "input/joypad.hpp"
#include "speed/speed_controller.hpp"
#include "boot/boot_rom.hpp"
#include <ravenemu/gbc/infrared_port.hpp>
#include <ravenemu/gb/hardware_mode.hpp>

namespace ravenemu::cgb {

class MemoryBus final : public Bus {
public:
    MemoryBus(Cartridge& cartridge, Ppu& ppu, InterruptController& interrupts, Timer& timer,
              SerialPort& serial, Joypad& joypad, Apu& apu,
              gb::HardwareMode boot_hardware_mode, gb::HardwareMode post_boot_hardware_mode,
              SpeedController& speed, InfraredPort& infrared, BootRom& boot_rom)
        : cartridge_(cartridge), ppu_(ppu), interrupts_(interrupts), timer_(timer), serial_(serial),
          joypad_(joypad), apu_(apu), hardware_mode_(boot_hardware_mode),
          boot_hardware_mode_(boot_hardware_mode), post_boot_hardware_mode_(post_boot_hardware_mode),
          speed_(speed), infrared_(infrared), boot_rom_(boot_rom) {}
    [[nodiscard]] int read(int address) override {
        const int addr = word(address);
        if (dma_active() && !dma_accessible(addr)) return 0xff;
        return read_internal(addr);
    }

    void write(int address, int value) override {
        const int addr = word(address);
        if (dma_active() && !dma_accessible(addr)) return;
        write_internal(addr, byte(value));
    }

    [[nodiscard]] int read_mcycle(int address) override {
        const int addr = word(address);
        const bool blocked_before = oam_dma_active_ && !dma_accessible(addr, oam_dma_source_);
        tick_mcycle();
        const bool blocked_after = oam_dma_active_ && !dma_accessible(addr, oam_dma_source_);
        if (blocked_before || blocked_after) return 0xff;
        return read_internal(addr);
    }

    void write_mcycle(int address, int value) override {
        const int addr = word(address);
        const bool blocked_before = oam_dma_active_ && !dma_accessible(addr, oam_dma_source_);
        tick_mcycle();
        const bool blocked_after = oam_dma_active_ && !dma_accessible(addr, oam_dma_source_);
        if (blocked_before || blocked_after) return;
        write_internal(addr, byte(value));
    }

    bool on_stop() override {
        reset_divider();
        if (!speed_.begin_switch_from_stop()) return false;
        ppu_.begin_speed_switch();
        return true;
    }
    bool stop_wake_requested() override { return joypad_.take_stop_wake(); }
    [[nodiscard]] bool cpu_blocked() const noexcept override {
        return hdma_block_bytes_remaining_ > 0 || (hdma_active_ && !hdma_hblank_);
    }

    void tick_mcycle() override {
        // Un HDMA/GDMA peut prendre le bus entre deux micro-opérations d'une
        // même instruction. Le CPU reprend seulement lorsque le bloc courant
        // a libéré le bus.
        while (cpu_blocked()) tick_cpu_clock(4, false);
        tick_cpu_clock(4, false);
        // Le passage en HBlank peut avoir armé un bloc pendant ce M-cycle.
        // Celui-ci doit se terminer avant l'accès bus placé à sa frontière.
        while (cpu_blocked()) tick_cpu_clock(4, false);
    }

    void tick_halted_mcycle() override {
        // Un GDMA reste maître du bus. En revanche un HBlank DMA est suspendu
        // tant que HALT n'a pas été quitté.
        while (hdma_active_ && !hdma_hblank_) tick_cpu_clock(4, false);
        tick_cpu_clock(4, true);
    }

    [[nodiscard]] int take_elapsed_dots() noexcept {
        const int value = elapsed_dots_;
        elapsed_dots_ = 0;
        return value;
    }

    int tick_speed_switch(int peripheral_dots) {
        for (int dot = 0; dot < peripheral_dots; ++dot) {
            ppu_.tick(1);
            if (ppu_.take_hblank_entry()) notify_hblank();
            apu_.tick(1);
            tick(0, 1);
            const bool switching_before = speed_.switching();
            speed_.tick_peripheral(1);
            if (switching_before && !speed_.switching()) ppu_.end_speed_switch();
            // La transition KEY1 arrête le CPU, pas le temps physique propre
            // aux composants de cartouche (cycle de programmation EEPROM).
            cartridge_.tick(1);
            ++elapsed_dots_;
        }
        return take_elapsed_dots();
    }

    /**
     * OAM DMA attend un M-cycle de démarrage, puis avance à 1 octet / 4
     * cycles CPU. HDMA/GDMA est cadencé sur l'horloge LCD : 1 octet / 2 dots.
     * Ainsi le DMA OAM accélère en double vitesse tandis que le nouveau DMA
     * CGB garde la même durée physique.
     */
    void tick(int cpu_cycles, int peripheral_cycles) noexcept {
        tick_oam_dma(cpu_cycles);
        tick_hdma(peripheral_cycles);
    }

    [[nodiscard]] bool dma_active() const noexcept { return oam_dma_active_; }

    void notify_hblank() noexcept {
        if (!hdma_active_ || !hdma_hblank_ || hdma_block_bytes_remaining_ > 0) return;
        hdma_block_bytes_remaining_ = std::min(16, hdma_length_);
        hdma_dot_accum_ = 0;
    }

    void save(BinaryWriter& out) const {
        out.raw(wram);
        out.raw(hram);
        out.i32(19);
        out.i32(dma_register_);
        out.i32(oam_dma_active_ ? 1 : 0);
        out.i32(oam_dma_source_);
        out.i32(oam_dma_index_);
        out.i32(oam_dma_cycle_accum_);
        out.i32(oam_dma_pending_ ? 1 : 0);
        out.i32(oam_dma_startup_cycles_);
        out.i32(svbk_);
        out.i32(hdma_source_);
        out.i32(hdma_destination_);
        out.i32(hdma_length_);
        out.i32(hdma_hblank_ ? 1 : 0);
        out.i32(hdma_active_ ? 1 : 0);
        out.i32(hdma_block_bytes_remaining_);
        out.i32(hdma_dot_accum_);
        out.i32(ff72_);
        out.i32(ff73_);
        out.i32(ff74_);
        out.i32(ff75_);
        infrared_.save(out);
        boot_rom_.save(out);
    }

    void load(BinaryReader& in) {
        in.raw(wram);
        in.raw(hram);
        if (in.i32() != 19) throw SaveStateError("État instantané corrompu (bus/DMA)");
        const auto bounded = [&in](int minimum, int maximum) {
            const int value = in.i32();
            if (value < minimum || value > maximum) {
                throw SaveStateError("État instantané corrompu (DMA)");
            }
            return value;
        };
        dma_register_ = bounded(0, 0xff);
        oam_dma_active_ = bounded(0, 1) != 0;
        oam_dma_source_ = bounded(0, 0xff00);
        oam_dma_index_ = bounded(0, 0xa0);
        oam_dma_cycle_accum_ = bounded(0, 3);
        oam_dma_pending_ = bounded(0, 1) != 0;
        oam_dma_startup_cycles_ = bounded(0, 4);
        svbk_ = bounded(0, 7);
        hdma_source_ = bounded(0, 0xffff);
        hdma_destination_ = bounded(0, 0x2000);
        hdma_length_ = bounded(0, 0x800);
        hdma_hblank_ = bounded(0, 1) != 0;
        hdma_active_ = bounded(0, 1) != 0;
        hdma_block_bytes_remaining_ = bounded(0, 16);
        hdma_dot_accum_ = bounded(0, 1);
        ff72_ = bounded(0, 0xff);
        ff73_ = bounded(0, 0xff);
        ff74_ = bounded(0, 0xff);
        ff75_ = bounded(0, 0x70) & 0x70;
        if ((oam_dma_active_ && !oam_dma_pending_ && oam_dma_source_ != dma_source_from_register()) ||
            (oam_dma_active_ && oam_dma_index_ >= 0xa0) ||
            (!oam_dma_pending_ && oam_dma_startup_cycles_ != 0) ||
            (hdma_active_ && (hdma_destination_ >= 0x2000 || hdma_length_ == 0)) ||
            hdma_block_bytes_remaining_ > hdma_length_ ||
            (!hdma_active_ && hdma_block_bytes_remaining_ != 0) ||
            (hdma_active_ && !hdma_hblank_ && hdma_block_bytes_remaining_ == 0) ||
            (hdma_active_ && hdma_hblank_ && !ppu_.lcd_enabled())) {
            throw SaveStateError("État instantané corrompu (DMA)");
        }
        if (oam_dma_active_) ppu_.restore_oam_dma_bus(oam_dma_index_);
        else ppu_.end_oam_dma();
        elapsed_dots_ = 0; // compteur de budget hôte, pas un registre matériel
        infrared_.load(in);
        boot_rom_.load(in);
        const auto restored_mode = boot_rom_.mapped() ? boot_hardware_mode_ : post_boot_hardware_mode_;
        if (!gb::cgb_features_enabled(restored_mode) &&
            (speed_.switching() || ppu_.speed_switch_active())) {
            throw SaveStateError("État instantané corrompu (transition KEY1 hors mode CGB)");
        }
        set_hardware_mode(restored_mode);
        ppu_.validate_speed_switch_state(speed_.switching());
    }

    std::array<std::uint8_t, 0x8000> wram{};
    std::array<std::uint8_t, 0x7f> hram{};

private:
    void tick_cpu_clock(int cpu_cycles, bool pause_hblank_hdma) {
        const int cpu_cycles_per_dot = 1 << speed_.peripheral_shift();
        const int peripheral_dots = cpu_cycles / cpu_cycles_per_dot;
        for (int dot = 0; dot < peripheral_dots; ++dot) {
            const bool apu_divider_before = timer_.apu_divider_signal(speed_.double_speed());
            timer_.tick(cpu_cycles_per_dot);
            if (apu_divider_before && !timer_.apu_divider_signal(speed_.double_speed())) {
                apu_.clock_divider_falling_edge();
            }
            serial_.tick(cpu_cycles_per_dot);
            ppu_.tick(1);
            if (ppu_.take_hblank_entry()) notify_hblank();
            apu_.tick(1);
            tick_oam_dma(cpu_cycles_per_dot);
            if (!pause_hblank_hdma || !hdma_hblank_) tick_hdma(1);
            // Les périphériques autonomes de cartouche suivent le temps
            // physique : un dot reste un dot même en double vitesse CGB.
            cartridge_.tick(1);
            ++elapsed_dots_;
        }
    }

    [[nodiscard]] int wram_bank() const noexcept {
        if (!gb::cgb_features_enabled(hardware_mode_)) return 1;
        const int value = svbk_ & 7;
        return value == 0 ? 1 : value;
    }

    [[nodiscard]] bool dma_accessible(int address) const noexcept {
        return dma_accessible(address, oam_dma_source_);
    }

    [[nodiscard]] bool dma_accessible(int address, int source) const noexcept {
        if ((address >= 0xff80 && address <= 0xfffe) || address == 0xff46) return true;
        const bool source_cartridge = source <= 0x7fff || (source >= 0xa000 && source <= 0xbfff);
        const bool source_vram = source >= 0x8000 && source <= 0x9fff;
        const bool source_wram = source >= 0xc000 && source <= 0xdfff;

        // Sur DMG, le cas mesuré d'un DMA provenant de VRAM laisse les bus
        // cartouche et WRAM fournir les fetches CPU. Les autres combinaisons
        // restent soumises à la restriction HRAM documentée ; ne pas
        // extrapoler les bus CGB.
        if (!gb::is_cgb_hardware(hardware_mode_)) {
            return source_vram &&
                (address <= 0x7fff || (address >= 0xa000 && address <= 0xfdff));
        }
        if (gb::is_cgb_hardware(hardware_mode_) && address >= 0xff00 && address <= 0xff7f) return true;
        if (gb::is_cgb_hardware(hardware_mode_) && address == 0xffff) return true;
        if (address >= 0xfe00 && address <= 0xfeff) return false;

        // Sur CGB le conflit porte sur le bus physique utilisé par la source.
        if (source_cartridge) return address >= 0xc000 && address <= 0xfdff;
        if (source_wram) return address <= 0xbfff;
        if (source_vram) return address <= 0x7fff || (address >= 0xa000 && address <= 0xfdff);
        return false;
    }

    [[nodiscard]] int read_internal(int address) {
        if (address <= 0x7fff) {
            if (boot_rom_.contains(address)) return boot_rom_.read(address);
            return cartridge_.read_rom(address);
        }
        if (address <= 0x9fff) return ppu_.read_vram(address);
        if (address <= 0xbfff) return cartridge_.read_ram(address);
        if (address <= 0xcfff) return wram[static_cast<std::size_t>(address - 0xc000)];
        if (address <= 0xdfff) return wram[static_cast<std::size_t>(wram_bank() * 0x1000 + address - 0xd000)];
        if (address <= 0xefff) return wram[static_cast<std::size_t>(address - 0xe000)];
        if (address <= 0xfdff) return wram[static_cast<std::size_t>(wram_bank() * 0x1000 + address - 0xf000)];
        if (address <= 0xfe9f) return ppu_.read_oam(address);
        if (address <= 0xfeff) return 0xff;
        switch (address) {
        case 0xff00: return joypad_.read();
        case 0xff01: return serial_.read_data();
        case 0xff02: return serial_.read_control();
        case 0xff04: return timer_.read_div();
        case 0xff05: return timer_.read_tima();
        case 0xff06: return timer_.tma();
        case 0xff07: return timer_.read_tac();
        case 0xff0f: return interrupts_.read_flags();
        case 0xff40: return ppu_.lcdc();
        case 0xff41: return ppu_.read_stat();
        case 0xff42: return ppu_.scy();
        case 0xff43: return ppu_.scx();
        case 0xff44: return ppu_.ly();
        case 0xff45: return ppu_.lyc();
        case 0xff46: return dma_register_;
        case 0xff47: return ppu_.bgp();
        case 0xff48: return ppu_.obp0();
        case 0xff49: return ppu_.obp1();
        case 0xff4a: return ppu_.wy();
        case 0xff4b: return ppu_.wx();
        case 0xff4d: return gb::cgb_features_enabled(hardware_mode_) ? speed_.read_key1() : 0xff;
        case 0xff4f: return ppu_.read_vram_bank();
        case 0xff50: return 0xff;
        case 0xff51: case 0xff52: case 0xff53: case 0xff54: return 0xff;
        case 0xff55: return read_hdma_status();
        case 0xff56: return gb::cgb_features_enabled(hardware_mode_) ? infrared_.read() : 0xff;
        case 0xff68: return gb::is_cgb_hardware(hardware_mode_) ? ppu_.read_bcps() : 0xff;
        case 0xff69: return gb::cgb_features_enabled(hardware_mode_) ? ppu_.read_bcpd() : 0xff;
        case 0xff6a: return gb::is_cgb_hardware(hardware_mode_) ? ppu_.read_ocps() : 0xff;
        case 0xff6b: return gb::cgb_features_enabled(hardware_mode_) ? ppu_.read_ocpd() : 0xff;
        case 0xff6c: return ppu_.read_opri();
        case 0xff70: return gb::cgb_features_enabled(hardware_mode_) ? svbk_ | 0xf8 : 0xff;
        case 0xff72: return gb::is_cgb_hardware(hardware_mode_) ? ff72_ : 0xff;
        case 0xff73: return gb::is_cgb_hardware(hardware_mode_) ? ff73_ : 0xff;
        case 0xff74: return gb::cgb_features_enabled(hardware_mode_) ? ff74_ : 0xff;
        case 0xff75: return gb::is_cgb_hardware(hardware_mode_) ? ff75_ | 0x8f : 0xff;
        case 0xff76: return hardware_mode_ == gb::HardwareMode::cgb_native
            ? apu_.read_pcm12() : hardware_mode_ == gb::HardwareMode::cgb_compatibility ? 0x00 : 0xff;
        case 0xff77: return hardware_mode_ == gb::HardwareMode::cgb_native
            ? apu_.read_pcm34() : hardware_mode_ == gb::HardwareMode::cgb_compatibility ? 0x00 : 0xff;
        case 0xffff: return interrupts_.read_enable();
        default: break;
        }
        if (address >= 0xff10 && address <= 0xff3f) return apu_.read(address);
        if (address >= 0xff80 && address <= 0xfffe) return hram[static_cast<std::size_t>(address - 0xff80)];
        return 0xff;
    }

    void write_internal(int address, int value) {
        if (address <= 0x7fff) { cartridge_.write_control(address, value); return; }
        if (address <= 0x9fff) { ppu_.write_vram(address, value); return; }
        if (address <= 0xbfff) { cartridge_.write_ram(address, value); return; }
        if (address <= 0xcfff) { wram[static_cast<std::size_t>(address - 0xc000)] = static_cast<std::uint8_t>(value); return; }
        if (address <= 0xdfff) { wram[static_cast<std::size_t>(wram_bank() * 0x1000 + address - 0xd000)] = static_cast<std::uint8_t>(value); return; }
        if (address <= 0xefff) { wram[static_cast<std::size_t>(address - 0xe000)] = static_cast<std::uint8_t>(value); return; }
        if (address <= 0xfdff) { wram[static_cast<std::size_t>(wram_bank() * 0x1000 + address - 0xf000)] = static_cast<std::uint8_t>(value); return; }
        if (address <= 0xfe9f) { ppu_.write_oam(address, value); return; }
        if (address <= 0xfeff) return;
        switch (address) {
        case 0xff00: joypad_.write(value); break;
        case 0xff01: serial_.write_data(value); break;
        case 0xff02: serial_.write_control(value); break;
        case 0xff04: reset_divider(); break;
        case 0xff05: timer_.write_tima(value); break;
        case 0xff06: timer_.set_tma(value); break;
        case 0xff07: timer_.write_tac(value); break;
        case 0xff0f: interrupts_.write_flags(value); break;
        case 0xff40: {
            const bool was_enabled = ppu_.lcd_enabled();
            ppu_.write_lcdc(value);
            if (was_enabled && !ppu_.lcd_enabled() && hdma_active_ && hdma_hblank_) {
                // Sans HBlank futur, le matériel termine comme un GDMA.
                hdma_hblank_ = false;
                hdma_block_bytes_remaining_ = std::min(16, hdma_length_);
                hdma_dot_accum_ = 0;
            }
            break;
        }
        case 0xff41: ppu_.write_stat(value); break;
        case 0xff42: ppu_.set_scy(value); break;
        case 0xff43: ppu_.set_scx(value); break;
        case 0xff44: break;
        case 0xff45: ppu_.write_lyc(value); break;
        case 0xff46: start_dma(value); break;
        case 0xff47: ppu_.set_bgp(value); break;
        case 0xff48: ppu_.set_obp0(value); break;
        case 0xff49: ppu_.set_obp1(value); break;
        case 0xff4a: ppu_.set_wy(value); break;
        case 0xff4b: ppu_.set_wx(value); break;
        case 0xff4d: if (gb::cgb_features_enabled(hardware_mode_)) speed_.write_key1(value); break;
        case 0xff4f: ppu_.write_vram_bank(value); break;
        case 0xff50: {
            const bool was_mapped = boot_rom_.mapped();
            boot_rom_.write_ff50(value);
            if (was_mapped && !boot_rom_.mapped()) set_hardware_mode(post_boot_hardware_mode_);
            break;
        }
        case 0xff51: if (gb::cgb_features_enabled(hardware_mode_)) hdma_source_ = (hdma_source_ & 0x00f0) | (value << 8); break;
        case 0xff52: if (gb::cgb_features_enabled(hardware_mode_)) hdma_source_ = (hdma_source_ & 0xff00) | (value & 0xf0); break;
        case 0xff53: if (gb::cgb_features_enabled(hardware_mode_)) hdma_destination_ = (hdma_destination_ & 0x00f0) | ((value & 0x1f) << 8); break;
        case 0xff54: if (gb::cgb_features_enabled(hardware_mode_)) hdma_destination_ = (hdma_destination_ & 0x1f00) | (value & 0xf0); break;
        case 0xff55: if (gb::cgb_features_enabled(hardware_mode_)) start_hdma(value); break;
        case 0xff56: if (gb::cgb_features_enabled(hardware_mode_)) infrared_.write(value); break;
        case 0xff68: if (gb::cgb_features_enabled(hardware_mode_)) ppu_.write_bcps(value); break;
        case 0xff69: if (gb::cgb_features_enabled(hardware_mode_)) ppu_.write_bcpd(value); break;
        case 0xff6a: if (gb::cgb_features_enabled(hardware_mode_)) ppu_.write_ocps(value); break;
        case 0xff6b: if (gb::cgb_features_enabled(hardware_mode_)) ppu_.write_ocpd(value); break;
        case 0xff6c: ppu_.write_opri(value); break;
        case 0xff70: if (gb::cgb_features_enabled(hardware_mode_)) svbk_ = value & 7; break;
        case 0xff72: if (gb::is_cgb_hardware(hardware_mode_)) ff72_ = value; break;
        case 0xff73: if (gb::is_cgb_hardware(hardware_mode_)) ff73_ = value; break;
        case 0xff74: if (gb::cgb_features_enabled(hardware_mode_)) ff74_ = value; break;
        case 0xff75: if (gb::is_cgb_hardware(hardware_mode_)) ff75_ = value & 0x70; break;
        case 0xffff: interrupts_.write_enable(value); break;
        default:
            if (address >= 0xff10 && address <= 0xff3f) apu_.write(address, value);
            else if (address >= 0xff80 && address <= 0xfffe) hram[static_cast<std::size_t>(address - 0xff80)] = static_cast<std::uint8_t>(value);
            break;
        }
    }

    void reset_divider() noexcept {
        const bool apu_divider_before = timer_.apu_divider_signal(speed_.double_speed());
        timer_.write_div();
        if (apu_divider_before && !timer_.apu_divider_signal(speed_.double_speed())) {
            apu_.clock_divider_falling_edge();
        }
    }

    void start_dma(int high) noexcept {
        dma_register_ = byte(high);
        // Le nouveau transfert ne remplace le moteur courant qu'après le
        // M-cycle de démarrage. Lors d'un restart, l'ancien DMA continue donc
        // pendant cette fenêtre, comme sur le bus physique.
        oam_dma_pending_ = true;
        oam_dma_startup_cycles_ = 4;
    }

    void tick_oam_dma(int cpu_cycles) noexcept {
        if (cpu_cycles <= 0) return;
        for (int cycle = 0; cycle < cpu_cycles; ++cycle) {
            if (oam_dma_pending_) {
                if (oam_dma_startup_cycles_ > 0) {
                    tick_active_oam_dma(1);
                    --oam_dma_startup_cycles_;
                    continue;
                }
                activate_pending_oam_dma();
            }
            tick_active_oam_dma(1);
        }
    }

    void activate_pending_oam_dma() noexcept {
        oam_dma_pending_ = false;
        oam_dma_source_ = dma_source_from_register();
        oam_dma_index_ = 0;
        oam_dma_cycle_accum_ = 0;
        oam_dma_startup_cycles_ = 0;
        oam_dma_active_ = true;
        ppu_.begin_oam_dma();
    }

    [[nodiscard]] int dma_source_from_register() const noexcept {
        // Sur les DMG, E0-FF reboucle sur C0-DF (la ligne A13 n'atteint pas
        // le mux source du DMA). Le CGB ne possède pas ce miroir.
        const int high = hardware_mode_ == gb::HardwareMode::dmg && dma_register_ >= 0xe0
            ? dma_register_ & 0xdf : dma_register_;
        return high << 8;
    }

    void tick_active_oam_dma(int cpu_cycles) noexcept {
        if (!oam_dma_active_ || cpu_cycles <= 0) return;
        oam_dma_cycle_accum_ += cpu_cycles;
        while (oam_dma_active_ && oam_dma_cycle_accum_ >= 4) {
            oam_dma_cycle_accum_ -= 4;
            const int source = oam_dma_source_ + oam_dma_index_;
            ppu_.write_oam_dma_byte(oam_dma_index_, read_for_oam_dma(source));
            ++oam_dma_index_;
            if (oam_dma_index_ >= 0xa0) {
                oam_dma_active_ = false;
                oam_dma_cycle_accum_ = 0;
                oam_dma_startup_cycles_ = 0;
                ppu_.end_oam_dma();
            }
        }
    }

    [[nodiscard]] int read_for_oam_dma(int address) const {
        // CGB DMG-style DMA accepte 0000-DFFF ; E000-FFFF n'est pas une source valide.
        if (address < 0 || address > 0xdfff) return 0xff;
        if (address <= 0x7fff) return cartridge_.read_rom(address);
        if (address <= 0x9fff) return ppu_.vram[static_cast<std::size_t>(ppu_.vram_bank() * 0x2000 + (address & 0x1fff))];
        if (address <= 0xbfff) return cartridge_.read_ram(address);
        if (address <= 0xcfff) return wram[static_cast<std::size_t>(address - 0xc000)];
        return wram[static_cast<std::size_t>(wram_bank() * 0x1000 + address - 0xd000)];
    }

    [[nodiscard]] int read_hdma_status() const noexcept {
        if (!gb::cgb_features_enabled(hardware_mode_)) return 0xff;
        if (hdma_length_ <= 0) return 0xff;
        const int remaining = ((hdma_length_ + 15) / 16 - 1) & 0x7f;
        return hdma_active_ ? remaining : 0x80 | remaining;
    }

    void start_hdma(int value) noexcept {
        if (hdma_active_) {
            if (hdma_hblank_ && (value & 0x80) == 0) {
                // Seul bit 7=0 arrête un HBlank DMA. Une écriture avec bit 7
                // à 1 ne recharge ni son adresse ni sa longueur.
                hdma_active_ = false;
                hdma_block_bytes_remaining_ = 0;
                hdma_dot_accum_ = 0;
            }
            return;
        }
        const bool hblank = (value & 0x80) != 0 && ppu_.lcd_enabled();
        hdma_length_ = ((value & 0x7f) + 1) * 16;
        hdma_hblank_ = hblank;
        hdma_active_ = true;
        hdma_block_bytes_remaining_ = hblank ? 0 : std::min(16, hdma_length_);
        hdma_dot_accum_ = 0;
    }

    void tick_hdma(int peripheral_cycles) noexcept {
        if (!hdma_active_ || hdma_block_bytes_remaining_ <= 0 || peripheral_cycles <= 0) return;
        hdma_dot_accum_ += peripheral_cycles;
        while (hdma_active_ && hdma_block_bytes_remaining_ > 0 && hdma_dot_accum_ >= 2) {
            hdma_dot_accum_ -= 2;
            const bool destination_available = transfer_hdma_byte();
            --hdma_block_bytes_remaining_;
            --hdma_length_;
            if (hdma_length_ <= 0 || !destination_available) {
                hdma_length_ = std::max(0, hdma_length_);
                hdma_active_ = false;
                hdma_block_bytes_remaining_ = 0;
                break;
            }
            if (hdma_block_bytes_remaining_ == 0 && !hdma_hblank_) {
                hdma_block_bytes_remaining_ = std::min(16, hdma_length_);
            }
        }
    }

    [[nodiscard]] bool transfer_hdma_byte() noexcept {
        const int value = read_for_hdma(word(hdma_source_));
        ppu_.vram[static_cast<std::size_t>(ppu_.vram_bank() * 0x2000 + hdma_destination_)] = static_cast<std::uint8_t>(value);
        hdma_source_ = word(hdma_source_ + 1);
        ++hdma_destination_;
        return hdma_destination_ < 0x2000;
    }

    [[nodiscard]] int read_for_hdma(int address) const {
        if (address <= 0x7fff) return cartridge_.read_rom(address);
        if (address >= 0xa000 && address <= 0xbfff) return cartridge_.read_ram(address);
        if (address >= 0xc000 && address <= 0xcfff) return wram[static_cast<std::size_t>(address - 0xc000)];
        if (address >= 0xd000 && address <= 0xdfff) return wram[static_cast<std::size_t>(wram_bank() * 0x1000 + address - 0xd000)];
        return 0xff;
    }

    void set_hardware_mode(gb::HardwareMode mode) noexcept {
        hardware_mode_ = mode;
        const bool native_cgb = gb::cgb_features_enabled(mode);
        speed_.set_cgb_mode(native_cgb);
        serial_.set_cgb_mode(native_cgb);
        infrared_.set_cgb_mode(native_cgb);
        ppu_.set_hardware_mode(mode);
    }

    Cartridge& cartridge_;
    Ppu& ppu_;
    InterruptController& interrupts_;
    Timer& timer_;
    SerialPort& serial_;
    Joypad& joypad_;
    Apu& apu_;
    gb::HardwareMode hardware_mode_{gb::HardwareMode::dmg};
    gb::HardwareMode boot_hardware_mode_{gb::HardwareMode::dmg};
    gb::HardwareMode post_boot_hardware_mode_{gb::HardwareMode::dmg};
    SpeedController& speed_;
    InfraredPort& infrared_;
    BootRom& boot_rom_;

    int svbk_{1};

    int dma_register_{0xff};
    bool oam_dma_active_{};
    int oam_dma_source_{};
    int oam_dma_index_{};
    int oam_dma_cycle_accum_{};
    bool oam_dma_pending_{};
    int oam_dma_startup_cycles_{};

    int hdma_source_{};
    int hdma_destination_{};
    int hdma_length_{};
    bool hdma_hblank_{};
    bool hdma_active_{};
    int hdma_block_bytes_remaining_{};
    int hdma_dot_accum_{};
    int ff72_{};
    int ff73_{};
    int ff74_{};
    int ff75_{};
    int elapsed_dots_{};
};

} // namespace ravenemu::cgb
