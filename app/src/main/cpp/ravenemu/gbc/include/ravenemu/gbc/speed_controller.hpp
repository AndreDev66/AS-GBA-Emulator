#pragma once

#include "ravenemu/binary_io.hpp"

#include <algorithm>

namespace ravenemu::cgb {

/** Contrôleur KEY1 et transition de fréquence du CGB.
 *
 * Les périphériques LCD/APU restent cadencés sur l'horloge de base. Le CPU,
 * DIV/TIMA et le port série interne voient la fréquence doublée après la
 * transition. La transition bloque le CPU pendant 2050 M-cycles. Elle est
 * exprimée en dots LCD : 8200 depuis la vitesse normale et 4100 depuis la
 * double vitesse.
 */
class SpeedController {
public:
    explicit SpeedController(bool cgb_mode) : cgb_mode_(cgb_mode) {}

    [[nodiscard]] int peripheral_shift() const noexcept { return double_speed_ ? 1 : 0; }
    [[nodiscard]] bool double_speed() const noexcept { return double_speed_; }
    [[nodiscard]] bool switching() const noexcept { return switch_dots_remaining_ > 0; }
    [[nodiscard]] int switch_dots_remaining() const noexcept { return switch_dots_remaining_; }

    void set_cgb_mode(bool enabled) noexcept {
        cgb_mode_ = enabled;
        if (!enabled) {
            double_speed_ = false;
            armed_ = false;
            switch_dots_remaining_ = 0;
        }
    }

    [[nodiscard]] int read_key1() const noexcept {
        if (!cgb_mode_) return 0xff;
        return (double_speed_ ? 0x80 : 0) | (armed_ ? 1 : 0) | 0x7e;
    }

    void write_key1(int value) noexcept {
        if (cgb_mode_ && !switching()) armed_ = (value & 1) != 0;
    }

    /**
     * Appelé par STOP. Retourne true si STOP a lancé une transition de vitesse
     * et ne doit donc pas entrer en veille normale.
     */
    bool begin_switch_from_stop() noexcept {
        if (!cgb_mode_ || !armed_ || switching()) return false;
        armed_ = false;
        switch_dots_remaining_ = double_speed_ ? switch_from_double_dots : switch_from_normal_dots;
        return true;
    }

    /** Avance la transition avec l'horloge périphérique (4,194304 MHz). */
    void tick_peripheral(int dots) noexcept {
        if (switch_dots_remaining_ <= 0 || dots <= 0) return;
        switch_dots_remaining_ = std::max(0, switch_dots_remaining_ - dots);
        if (switch_dots_remaining_ == 0) double_speed_ = !double_speed_;
    }

    void save(detail::BinaryWriter& out) const {
        out.i32(double_speed_ ? 1 : 0);
        out.i32(armed_ ? 1 : 0);
        out.i32(switch_dots_remaining_);
    }

    void load(detail::BinaryReader& in) {
        const int double_speed = in.i32();
        const int armed = in.i32();
        const int remaining = in.i32();
        const int maximum_remaining = double_speed != 0
            ? switch_from_double_dots : switch_from_normal_dots;
        if ((double_speed != 0 && double_speed != 1) || (armed != 0 && armed != 1) ||
            remaining < 0 || remaining > maximum_remaining ||
            (remaining > 0 && armed != 0) ||
            (!cgb_mode_ && (double_speed != 0 || armed != 0 || remaining != 0))) {
            throw SaveStateError("État instantané corrompu (KEY1)");
        }
        double_speed_ = double_speed != 0;
        armed_ = armed != 0;
        switch_dots_remaining_ = remaining;
    }

private:
    static constexpr int switch_from_normal_dots = 2'050 * 4;
    static constexpr int switch_from_double_dots = 2'050 * 2;

    bool cgb_mode_{};
    bool double_speed_{};
    bool armed_{};
    int switch_dots_remaining_{};
};

} // namespace ravenemu::cgb
