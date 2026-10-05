#pragma once

#include "apu/envelope.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <span>

namespace ravenemu::gba {

class NoiseChannel {
public:
    void trigger() noexcept {
        enabled = envelope.dac_enabled();
        if (length_counter == 0) length_counter = 64;
        timer_ = period(); lfsr_ = 0x7fff; envelope.trigger();
    }
    void write_polynomial(int value) noexcept {
        shift_clock_ = (value >> 4) & 15;
        width_mode_ = (value & 8) != 0;
        divisor_code_ = value & 7;
    }
    /**
     * Fait avancer le registre à décalage, en cumulant chaque état le temps
     * qu'il dure.
     *
     * Le bruit est le canal où le repliement s'entend le plus : son spectre
     * monte jusqu'à la fréquence de décalage, très au-dessus de la moitié du
     * débit de sortie. Prélevé sans moyenne, il se replie en un souffle
     * métallique au lieu d'un bruit plat.
     */
    void tick(int cycles) noexcept {
        if (!enabled) return;
        const auto timer_period = period();
        int remaining = cycles;
        while (remaining > 0) {
            if (timer_ <= 0) timer_ = timer_period;
            const int slice = std::min(timer_, remaining);
            accumulator_ += output() * slice;
            timer_ -= slice;
            remaining -= slice;
            if (timer_ <= 0) {
                timer_ = timer_period;
                const auto feedback = (lfsr_ & 1) ^ ((lfsr_ >> 1) & 1);
                lfsr_ = (lfsr_ >> 1) | (feedback << 14);
                if (width_mode_) lfsr_ = (lfsr_ & ~0x40) | (feedback << 6);
            }
        }
    }
    /**
     * Rend le cumul de la fenêtre écoulée et le remet à zéro.
     *
     * Le cumul est rendu **tel quel**, sans division : c'est le mélangeur qui
     * divise, une seule fois, à la toute fin. Diviser ici demanderait un
     * flottant — la moyenne n'est presque jamais entière — et ferait dépendre
     * la sortie de l'ordre des arrondis. Tout le mixage reste ainsi en entiers
     * exacts, ce qui vaut aussi pour le modèle de référence Kotlin auquel ce
     * cœur est confronté trame par trame.
     */
    [[nodiscard]] int drain_accumulator() noexcept {
        const auto total = accumulator_;
        accumulator_ = 0;
        return total;
    }
    void clock_length() noexcept {
        if (length_enabled && length_counter > 0 && --length_counter == 0) enabled = false;
    }
    [[nodiscard]] int output() const noexcept { return enabled && (lfsr_ & 1) == 0 ? envelope.volume : 0; }
    void reset() noexcept {
        enabled = false; timer_ = 0; lfsr_ = 0x7fff; length_counter = 0; accumulator_ = 0;
    }
    /** Nombre de mots que le canal occupe dans un état instantané. */
    static constexpr std::size_t state_words = 14;
    /**
     * Le canal entier, **registre à décalage compris**.
     *
     * `lfsr_` est la source du bruit lui-même : le perdre ne couperait pas le
     * son, il le recommencerait par son tout premier état, et l'on entendrait un
     * souffle sec au milieu d'un bruitage continu.
     */
    [[nodiscard]] std::array<std::int32_t, state_words> export_state() const noexcept {
        const auto env = envelope.export_state();
        std::array<std::int32_t, state_words> result{};
        result[0] = enabled ? 1 : 0; result[1] = length_counter; result[2] = length_enabled ? 1 : 0;
        std::copy(env.begin(), env.end(), result.begin() + 3);
        result[8] = shift_clock_; result[9] = width_mode_ ? 1 : 0; result[10] = divisor_code_;
        result[11] = timer_; result[12] = accumulator_; result[13] = lfsr_;
        return result;
    }
    void import_state(std::span<const std::int32_t> state) noexcept {
        enabled = state[0] != 0; length_counter = state[1]; length_enabled = state[2] != 0;
        envelope.import_state(state.subspan(3, Envelope::state_words));
        shift_clock_ = state[8]; width_mode_ = state[9] != 0; divisor_code_ = state[10];
        timer_ = state[11]; accumulator_ = state[12]; lfsr_ = state[13];
    }
    bool enabled{};
    int length_counter{};
    bool length_enabled{};
    Envelope envelope;
private:
    [[nodiscard]] int period() const noexcept {
        const auto divisor = divisor_code_ == 0 ? 8 : divisor_code_ * 16;
        return std::max(1, divisor << shift_clock_);
    }
    int shift_clock_{};
    bool width_mode_{};
    int divisor_code_{};
    int timer_{};
    int accumulator_{};
    int lfsr_{0x7fff};
};

} // namespace ravenemu::gba
