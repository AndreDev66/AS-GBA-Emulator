#pragma once

#include "support/bits.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <span>

namespace ravenemu::gba {

class WaveChannel {
public:
    void trigger() noexcept {
        enabled = dac_enabled;
        if (length_counter == 0) length_counter = 256;
        timer_ = (2048 - frequency) * 2;
        position_ = 0;
    }
    /**
     * Fait avancer la lecture, en cumulant chaque point le temps qu'il dure.
     *
     * Même raison que pour les canaux carrés : c'est la moyenne sur la fenêtre
     * d'un échantillon qui doit être prélevée, non la valeur qui se trouve là
     * à l'instant du prélèvement.
     */
    void tick(int cycles) noexcept {
        if (!enabled) return;
        const auto period = std::max(1, (2048 - frequency) * 2);
        int remaining = cycles;
        while (remaining > 0) {
            if (timer_ <= 0) timer_ = period;
            const int slice = std::min(timer_, remaining);
            accumulator_ += output() * slice;
            timer_ -= slice;
            remaining -= slice;
            if (timer_ <= 0) {
                timer_ = period;
                position_ = (position_ + 1) & 31;
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
    [[nodiscard]] int output() const noexcept {
        if (!enabled || !dac_enabled || volume_code == 0) return 0;
        const auto sample = wave_ram[static_cast<std::size_t>(position_)];
        return volume_code == 1 ? sample : volume_code == 2 ? sample >> 1 : sample >> 2;
    }
    void reset() noexcept {
        enabled = false; timer_ = 0; position_ = 0; length_counter = 0; accumulator_ = 0;
    }
    /** Nombre de mots que le canal occupe dans un état instantané. */
    static constexpr std::size_t state_words = 41;
    /**
     * Le canal entier, RAM d'échantillons comprise.
     *
     * `position_` est l'endroit de la table d'échantillons où en est la lecture :
     * un jeu écrit la table pour un effet sonore, passe à autre chose, et ne la
     * réécrit pas au chargement. Sans la position, la lecture repartirait du
     * début de la table et rejouerait l'attaque du timbre au milieu de la
     * partie, en plein milieu d'un bruitage.
     */
    [[nodiscard]] std::array<std::int32_t, state_words> export_state() const noexcept {
        std::array<std::int32_t, state_words> result{};
        result[0] = enabled ? 1 : 0; result[1] = dac_enabled ? 1 : 0; result[2] = frequency;
        result[3] = length_counter; result[4] = length_enabled ? 1 : 0; result[5] = volume_code;
        result[6] = timer_; result[7] = accumulator_; result[8] = position_;
        std::copy(wave_ram.begin(), wave_ram.end(), result.begin() + 9);
        return result;
    }
    void import_state(std::span<const std::int32_t> state) noexcept {
        enabled = state[0] != 0; dac_enabled = state[1] != 0; frequency = state[2];
        length_counter = state[3]; length_enabled = state[4] != 0; volume_code = state[5];
        timer_ = state[6]; accumulator_ = state[7]; position_ = state[8];
        std::copy_n(state.begin() + 9, static_cast<std::ptrdiff_t>(wave_ram.size()), wave_ram.begin());
    }
    bool enabled{};
    bool dac_enabled{};
    int frequency{};
    int length_counter{};
    bool length_enabled{};
    int volume_code{};
    std::array<int, 32> wave_ram{};
private:
    int timer_{};
    int accumulator_{};
    int position_{};
};

} // namespace ravenemu::gba
