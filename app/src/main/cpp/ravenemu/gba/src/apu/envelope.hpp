#pragma once

#include "support/bits.hpp"

#include <array>
#include <cstdint>
#include <span>

namespace ravenemu::gba {

class Envelope {
public:
    /** Nombre de mots que l'enveloppe occupe dans un état instantané. */
    static constexpr std::size_t state_words = 5;
    void trigger() noexcept { volume = initial_volume; timer_ = period; }
    void clock() noexcept {
        if (period == 0) return;
        if (timer_ > 0) --timer_;
        if (timer_ != 0) return;
        timer_ = period;
        if (increasing && volume < 15) ++volume;
        else if (!increasing && volume > 0) --volume;
    }
    void write(int value) noexcept {
        initial_volume = (value >> 4) & 15;
        increasing = (value & 8) != 0;
        period = value & 7;
        volume = initial_volume;
    }
    [[nodiscard]] bool dac_enabled() const noexcept { return initial_volume != 0 || increasing; }
    /**
     * L'enveloppe entière : réglages **et** décours.
     *
     * `volume` et `timer_` ne sont pas des registres, ce sont du temps écoulé.
     * C'est justement ce qu'un jeu ne réécrit jamais une fois la partie
     * sauvegardée : les laisser derrière ferait revenir le son à son volume
     * initial à chaque chargement, et l'on entendrait une attaque de note au
     * milieu d'un passage. Un état instantané qui omet ce décours n'est pas un
     * état instantané de la musique.
     */
    [[nodiscard]] std::array<std::int32_t, state_words> export_state() const noexcept {
        return {initial_volume, increasing ? 1 : 0, period, volume, timer_};
    }
    void import_state(std::span<const std::int32_t> state) noexcept {
        initial_volume = state[0]; increasing = state[1] != 0; period = state[2];
        volume = state[3]; timer_ = state[4];
    }
    int initial_volume{};
    bool increasing{};
    int period{};
    int volume{};
private:
    int timer_{};
};

} // namespace ravenemu::gba