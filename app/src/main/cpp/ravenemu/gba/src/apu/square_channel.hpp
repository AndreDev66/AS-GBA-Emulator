#pragma once

#include "apu/envelope.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <span>

namespace ravenemu::gba {

class SquareChannel {
public:
    explicit SquareChannel(bool sweep) : has_sweep_(sweep) {}
    void trigger() {
        enabled = envelope.dac_enabled();
        if (length_counter == 0) length_counter = 64;
        timer_ = (2048 - frequency) * 4;
        envelope.trigger();
        if (has_sweep_) {
            sweep_shadow_ = frequency;
            sweep_timer_ = sweep_period_ == 0 ? 8 : sweep_period_;
            sweep_active_ = sweep_period_ != 0 || sweep_shift_ != 0;
            if (sweep_shift_ != 0) compute_sweep(false);
        }
    }
    void write_sweep(int value) noexcept {
        sweep_period_ = (value >> 4) & 7;
        sweep_negate_ = (value & 8) != 0;
        sweep_shift_ = value & 7;
    }
    /**
     * Fait avancer le canal, en **cumulant sa sortie le temps qu'elle dure**.
     *
     * Le compteur est découpé aux frontières du rapport cyclique, et chaque
     * tranche verse sa valeur pondérée par sa durée. C'est ce cumul, moyenné
     * sur la fenêtre d'un échantillon, qui devient l'échantillon : prendre la
     * valeur instantanée à l'instant du prélèvement replierait toutes les
     * harmoniques au-dessus de la moitié du débit de sortie, qu'un signal
     * carré produit en quantité, et les rendrait comme des sifflements
     * étrangers au morceau.
     *
     * Le cœur Game Boy procède déjà ainsi ; ce canal ne le faisait pas.
     */
    void tick(int cycles) noexcept {
        if (!enabled) return;
        const auto period = std::max(1, (2048 - frequency) * 4);
        int remaining = cycles;
        while (remaining > 0) {
            // Un compteur à plat ou négatif est ramené à une période : sans
            // cela une tranche de longueur nulle ferait tourner la boucle sans
            // consommer.
            if (timer_ <= 0) timer_ = period;
            const int slice = std::min(timer_, remaining);
            accumulator_ += output() * slice;
            timer_ -= slice;
            remaining -= slice;
            if (timer_ <= 0) {
                timer_ = period;
                duty_step_ = (duty_step_ + 1) & 7;
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
    void clock_sweep() {
        if (!has_sweep_ || !sweep_active_) return;
        if (sweep_timer_ > 0) --sweep_timer_;
        if (sweep_timer_ != 0) return;
        sweep_timer_ = sweep_period_ == 0 ? 8 : sweep_period_;
        if (sweep_period_ != 0) compute_sweep(true);
    }
    [[nodiscard]] int output() const noexcept {
        static constexpr std::array masks{0x80, 0x81, 0xe1, 0x7e};
        return enabled && ((masks[static_cast<std::size_t>(duty)] >> duty_step_) & 1) != 0
            ? envelope.volume : 0;
    }
    void reset() noexcept {
        enabled = false; timer_ = 0; duty_step_ = 0; length_counter = 0; accumulator_ = 0;
    }
    /** Nombre de mots que le canal occupe dans un état instantané. */
    static constexpr std::size_t state_words = 19;
    /**
     * Le canal entier : registres, enveloppe et **position dans le cycle**.
     *
     * `timer_`, `duty_step_` et le cumul de la fenêtre en cours sont du temps
     * écoulé, pas des réglages. Un jeu ne les réécrit jamais après une sauvegarde
     * : sans eux, le chargement repartirait du début de la période avec le
     * cycle carré remis à zéro, et la note poursuivrait par une attaque
     * parasite au lieu de reprendre où elle en était.
     */
    [[nodiscard]] std::array<std::int32_t, state_words> export_state() const noexcept {
        const auto env = envelope.export_state();
        std::array<std::int32_t, state_words> result{};
        result[0] = enabled ? 1 : 0; result[1] = duty; result[2] = frequency;
        result[3] = length_counter; result[4] = length_enabled ? 1 : 0;
        std::copy(env.begin(), env.end(), result.begin() + 5);
        result[10] = timer_; result[11] = accumulator_; result[12] = duty_step_;
        result[13] = sweep_period_; result[14] = sweep_negate_ ? 1 : 0; result[15] = sweep_shift_;
        result[16] = sweep_timer_; result[17] = sweep_shadow_; result[18] = sweep_active_ ? 1 : 0;
        return result;
    }
    void import_state(std::span<const std::int32_t> state) noexcept {
        enabled = state[0] != 0; duty = state[1]; frequency = state[2];
        length_counter = state[3]; length_enabled = state[4] != 0;
        envelope.import_state(state.subspan(5, Envelope::state_words));
        timer_ = state[10]; accumulator_ = state[11]; duty_step_ = state[12];
        sweep_period_ = state[13]; sweep_negate_ = state[14] != 0; sweep_shift_ = state[15];
        sweep_timer_ = state[16]; sweep_shadow_ = state[17]; sweep_active_ = state[18] != 0;
    }
    bool enabled{};
    int duty{2};
    int frequency{};
    int length_counter{};
    bool length_enabled{};
    Envelope envelope;
private:
    void compute_sweep(bool apply) noexcept {
        const auto delta = sweep_shadow_ >> sweep_shift_;
        const auto next = sweep_negate_ ? sweep_shadow_ - delta : sweep_shadow_ + delta;
        if (next > 2047) { enabled = false; return; }
        if (apply && sweep_shift_ != 0) { sweep_shadow_ = next; frequency = next; }
    }
    bool has_sweep_{};
    int timer_{};
    int accumulator_{};
    int duty_step_{};
    int sweep_period_{};
    bool sweep_negate_{};
    int sweep_shift_{};
    int sweep_timer_{};
    int sweep_shadow_{};
    bool sweep_active_{};
};

} // namespace ravenemu::gba
