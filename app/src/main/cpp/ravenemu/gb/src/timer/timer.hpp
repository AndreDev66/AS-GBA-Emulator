#pragma once

#include "interrupt/interrupt_controller.hpp"
#include <ravenemu/gb/hardware_mode.hpp>

namespace ravenemu::cgb {

class Timer {
public:
    explicit Timer(InterruptController& interrupts,
                   gb::HardwareMode hardware_mode = gb::HardwareMode::dmg)
        : interrupts_(interrupts), hardware_mode_(hardware_mode) {}

    void tick(int cycles) noexcept {
        for (int i = 0; i < cycles; ++i) {
            reload_cycle_ = false;
            if (reload_delay_ > 0 && --reload_delay_ == 0) {
                tima_ = tma_;
                interrupts_.request(Interrupt::timer);
                reload_delay_ = -1;
                reload_cycle_ = true;
            }
            set_div_counter(div_counter_ + 1);
        }
    }
    [[nodiscard]] int read_div() const noexcept { return (div_counter_ >> 8) & 0xff; }
    [[nodiscard]] int reset_aligned_phase() const noexcept { return div_counter_ & 0x1ff; }
    /**
     * Ligne d'horloge du compteur DIV-APU. Le multiplexeur suit le bit 12 du
     * diviseur interne a vitesse normale et le bit 13 en double vitesse, afin
     * de conserver un front descendant a 512 Hz dans les deux cas.
     */
    [[nodiscard]] bool apu_divider_signal(bool double_speed) const noexcept {
        return (div_counter_ & (1 << (double_speed ? 13 : 12))) != 0;
    }
    void write_div() noexcept { set_div_counter(0); }
    void reset_for_boot_rom() noexcept {
        div_counter_ = 0;
        tima_ = 0;
        tma_ = 0;
        tac_ = 0xf8;
        reload_delay_ = -1;
        reload_cycle_ = false;
    }
    void initialize_hle_post_boot() noexcept {
        // Phases observees a l'entree $0100 sur DMG ABC/MGB et CGB ABCDE.
        // Elles sont distinctes meme si DIV n'en expose que l'octet haut.
        div_counter_ = hardware_mode_ == gb::HardwareMode::dmg ? 0xabc8 : 0x2674;
        tima_ = 0;
        tma_ = 0;
        tac_ = 0xf8;
        reload_delay_ = -1;
        reload_cycle_ = false;
    }
    [[nodiscard]] int read_tima() const noexcept { return tima_; }
    void write_tima(int value) noexcept {
        if (reload_cycle_) return;
        if (reload_delay_ > 0) reload_delay_ = -1;
        tima_ = byte(value);
    }
    [[nodiscard]] int read_tac() const noexcept { return tac_ | 0xf8; }
    void write_tac(int value) noexcept {
        const int old_tac = tac_;
        const bool before = signal(div_counter_, tac_);
        tac_ = (value & 7) | 0xf8;
        const bool disabled = (old_tac & 4) != 0 && (tac_ & 4) == 0;
        // Le circuit CGB ne produit pas le front de desactivation du DMG,
        // mais les revisions couvertes par les tests materiels produisent un
        // tick lorsque le timer est active sur une entree deja haute.
        const bool cgb_enable_high = gb::is_cgb_hardware(hardware_mode_) &&
            (old_tac & 4) == 0 && (tac_ & 4) != 0 &&
            (div_counter_ & selected_mask(tac_)) != 0;
        if (before && !signal(div_counter_, tac_) &&
            !(gb::is_cgb_hardware(hardware_mode_) && disabled)) {
            increment_tima();
        } else if (cgb_enable_high) {
            increment_tima();
        }
    }
    [[nodiscard]] int tma() const noexcept { return tma_; }
    void set_tma(int value) noexcept {
        tma_ = byte(value);
        if (reload_cycle_) tima_ = tma_;
    }
    void save(BinaryWriter& out) const {
        out.i32(div_counter_); out.i32(tima_); out.i32(tma_); out.i32(read_tac()); out.i32(reload_delay_);
        out.boolean(reload_cycle_);
    }
    void load(BinaryReader& in) {
        div_counter_ = word(in.i32()); tima_ = byte(in.i32()); tma_ = byte(in.i32());
        tac_ = in.i32() | 0xf8; reload_delay_ = in.i32(); reload_cycle_ = in.boolean();
    }

private:
    static int selected_mask(int tac) noexcept {
        switch (tac & 3) { case 0: return 1 << 9; case 1: return 1 << 3; case 2: return 1 << 5; default: return 1 << 7; }
    }
    static bool signal(int counter, int tac) noexcept {
        return (tac & 4) != 0 && (counter & selected_mask(tac)) != 0;
    }
    void increment_tima() noexcept { tima_ = byte(tima_ + 1); if (tima_ == 0) reload_delay_ = 4; }
    void set_div_counter(int value) noexcept {
        const bool before = signal(div_counter_, tac_);
        div_counter_ = word(value);
        if (before && !signal(div_counter_, tac_)) increment_tima();
    }

    InterruptController& interrupts_;
    gb::HardwareMode hardware_mode_{gb::HardwareMode::dmg};
    int div_counter_{0xab00};
    int tima_{};
    int tma_{};
    int tac_{0xf8};
    int reload_delay_{-1};
    bool reload_cycle_{};
};

} // namespace ravenemu::cgb
