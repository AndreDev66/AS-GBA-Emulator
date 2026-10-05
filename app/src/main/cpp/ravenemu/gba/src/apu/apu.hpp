#pragma once

#include "apu/square_channel.hpp"
#include "apu/wave_channel.hpp"
#include "apu/noise_channel.hpp"
#include "memory/bus.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <span>

namespace ravenemu::gba {

class Apu {
public:
    static constexpr int sample_rate = 32768;
    Apu() : square1(true), square2(false) {}
    [[nodiscard]] int cycles_until_next_sample() const noexcept {
        return ((cycles_per_sample - sample_timer_) << 2) - cpu_remainder_;
    }
    void tick(int cpu_cycles) {
        cpu_remainder_ += cpu_cycles;
        if (cycles_until_next_sample() > 0) return;
        auto audio_cycles = cpu_remainder_ >> 2;
        cpu_remainder_ -= audio_cycles << 2;
        const auto start = measuring_time_ ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
        while (audio_cycles > 0) {
            const auto step = std::min(audio_cycles, cycles_per_sample - sample_timer_);
            square1.tick(step); square2.tick(step); wave.tick(step); noise.tick(step);
            sequencer_timer_ += step;
            while (sequencer_timer_ >= 8192) { sequencer_timer_ -= 8192; clock_sequencer(); }
            sample_timer_ += step;
            if (sample_timer_ >= cycles_per_sample) { sample_timer_ -= cycles_per_sample; emit_sample(); }
            audio_cycles -= step;
        }
        if (measuring_time_ && on_batch_nanos) {
            on_batch_nanos(std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - start).count());
        }
    }
    void timer_overflow(int timer) {
        if (timer > 1) return;
        if (((control_h >> 10) & 1) == timer) pop_fifo(0);
        if (((control_h >> 14) & 1) == timer) pop_fifo(1);
    }
    void push_fifo(int channel, std::int32_t value, int bytes) {
        auto& fifo = channel == 0 ? fifo_a_ : fifo_b_;
        for (int i = 0; i < bytes; ++i) fifo.push(static_cast<std::int8_t>(u32(value) >> (static_cast<unsigned>(i) * 8U)));
    }
    void write_register(int offset, int value) {
        switch (offset) {
        case 0x60: square1.write_sweep(value & 0xff); break;
        case 0x62: write_square_duty(square1, value); break;
        case 0x64: write_square_frequency(square1, value); break;
        case 0x68: write_square_duty(square2, value); break;
        case 0x6c: write_square_frequency(square2, value); break;
        case 0x70: wave.dac_enabled = (value & 0x80) != 0; if (!wave.dac_enabled) wave.enabled = false; break;
        case 0x72: wave.length_counter = 256 - (value & 0xff); wave.volume_code = (value >> 13) & 3; break;
        case 0x74:
            wave.frequency = value & 0x7ff; wave.length_enabled = (value & 0x4000) != 0;
            if ((value & 0x8000) != 0) wave.trigger();
            break;
        case 0x78:
            noise.length_counter = 64 - (value & 0x3f); noise.envelope.write((value >> 8) & 0xff);
            if (!noise.envelope.dac_enabled()) noise.enabled = false;
            break;
        case 0x7c:
            noise.write_polynomial(value & 0xff); noise.length_enabled = (value & 0x4000) != 0;
            if ((value & 0x8000) != 0) noise.trigger();
            break;
        case 0x80: control_l = value; break;
        case 0x82:
            control_h = value;
            if ((value & 0x0800) != 0) reset_fifo(0);
            if ((value & 0x8000) != 0) reset_fifo(1);
            break;
        case 0x84:
            master_enable = (value & 0x80) != 0;
            if (!master_enable) { square1.reset(); square2.reset(); wave.reset(); noise.reset(); }
            break;
        default:
            if (offset >= 0x90 && offset <= 0x9f) {
                const auto index = static_cast<std::size_t>((offset - 0x90) * 2);
                if (index + 3 < wave.wave_ram.size()) {
                    wave.wave_ram[index] = (value >> 4) & 15;
                    wave.wave_ram[index + 1] = value & 15;
                    wave.wave_ram[index + 2] = (value >> 12) & 15;
                    wave.wave_ram[index + 3] = (value >> 8) & 15;
                }
            }
            break;
        }
    }
    std::size_t read_samples(std::span<std::int16_t> destination) {
        if (available_ == 0 && !destination.empty()) ++underruns;
        const auto count = std::min(destination.size(), available_);
        for (std::size_t i = 0; i < count; ++i) {
            destination[i] = ring_[read_index_];
            read_index_ = (read_index_ + 1U) & ring_mask;
        }
        available_ -= count;
        return count;
    }
    [[nodiscard]] int fifo_size(int channel) const noexcept { return channel == 0 ? fifo_a_.size : fifo_b_.size; }
    [[nodiscard]] int fifo_empty_reads(int channel) const noexcept { return fifo_empty_reads_[static_cast<std::size_t>(channel)]; }
    void set_measuring(bool value) noexcept { measuring_time_ = value; }
    void reset() noexcept {
        square1.reset(); square2.reset(); wave.reset(); noise.reset(); fifo_a_.clear(); fifo_b_.clear();
        direct_a_ = direct_b_ = 0; control_l = control_h = 0; master_enable = false;
        write_index_ = read_index_ = available_ = 0; underruns = 0; fifo_empty_reads_.fill(0);
        cpu_remainder_ = sample_timer_ = sequencer_timer_ = sequencer_step_ = 0;
    }
    /**
     * Nombre de mots que l'APU occupe dans un état instantané.
     *
     * La composition est vérifiée en bas de la classe : trois mots de mélange,
     * 19 par canal carré, 41 pour l'onde (RAM d'échantillons comprise), 14 pour
     * le bruit, 35 par file, puis huit mots de sortie directe et d'horloges.
     */
    static constexpr std::size_t state_words = 174;
    /**
     * L'APU entier : les quatre canaux, les deux files et le mélange.
     *
     * Ces registres sont **la seule trace** que le son était en marche. Les
     * entrées du bus en sont la copie *logique*, mais l'APU garde les siennes,
     * et c'est lui qui décide de ce qui sort : `master_enable` efface tout, et
     * les bits de volume et de destination ne vivent que dans `control_l` et
     * `control_h`. Un jeu écrit SOUNDCNT_X et SOUNDCNT_H une fois pour toutes au
     * démarrage, puis plus jamais. Les omettre d'un état instantané laissait donc
     * un APU vierge dont le jeu croyait l'inverse : plus rien ne se déclenchait,
     * et la partie reprenait en silence.
     */
    [[nodiscard]] std::array<std::int32_t, state_words> export_state() const noexcept {
        const auto s1 = square1.export_state(); const auto s2 = square2.export_state();
        const auto wv = wave.export_state(); const auto ns = noise.export_state();
        const auto fa = fifo_a_.export_state(); const auto fb = fifo_b_.export_state();
        std::array<std::int32_t, state_words> result{};
        result[0] = control_l; result[1] = control_h; result[2] = master_enable ? 1 : 0;
        std::copy(s1.begin(), s1.end(), result.begin() + 3);
        std::copy(s2.begin(), s2.end(), result.begin() + 22);
        std::copy(wv.begin(), wv.end(), result.begin() + 41);
        std::copy(ns.begin(), ns.end(), result.begin() + 82);
        std::copy(fa.begin(), fa.end(), result.begin() + 96);
        std::copy(fb.begin(), fb.end(), result.begin() + 131);
        result[166] = direct_a_; result[167] = direct_b_;
        result[168] = cpu_remainder_; result[169] = sample_timer_;
        result[170] = sequencer_timer_; result[171] = sequencer_step_;
        result[172] = fifo_empty_reads_[0]; result[173] = fifo_empty_reads_[1];
        return result;
    }
    void import_state(std::span<const std::int32_t> state) {
        if (state.size() != state_words) throw SaveStateError("État APU GBA invalide");
        control_l = state[0]; control_h = state[1]; master_enable = state[2] != 0;
        square1.import_state(state.subspan(3, SquareChannel::state_words));
        square2.import_state(state.subspan(22, SquareChannel::state_words));
        wave.import_state(state.subspan(41, WaveChannel::state_words));
        noise.import_state(state.subspan(82, NoiseChannel::state_words));
        fifo_a_.import_state(state.subspan(96, Fifo::state_words));
        fifo_b_.import_state(state.subspan(131, Fifo::state_words));
        direct_a_ = state[166]; direct_b_ = state[167];
        cpu_remainder_ = state[168]; sample_timer_ = state[169];
        sequencer_timer_ = state[170]; sequencer_step_ = state[171];
        fifo_empty_reads_[0] = state[172]; fifo_empty_reads_[1] = state[173];
        // La file d'attente n'est **pas** restituée : elle porte les échantillons
        // de la trame abandonnée. La vider évite de faire entendre du son déjà
        // joué avant le chargement ; le transport audio, lui, se réamorce seul.
        write_index_ = read_index_ = available_ = 0;
    }
    /**
     * Rejoue les écritures de son encore inscrites dans le bus.
     *
     * Repli pour les états antérieurs à la sauvegarde de l'APU : leur machine
     * neuve sortait du silence alors que le jeu, lui, croyait le son en marche
     * depuis le démarrage. Ce repli ne restitue pas la phase des canaux — elle
     * n'y figure pas — mais il remet la sortie dans l'état où le jeu l'a
     * laissée, si bien que le son revient dès la note suivante au lieu de ne
     * jamais revenir.
     */
    void replay_from_io(const Bus& bus) noexcept {
        for (int offset = 0x60; offset <= 0x9e; offset += 2) {
            write_register(offset, bus.io[static_cast<std::size_t>(offset)]
                | bus.io[static_cast<std::size_t>(offset + 1)] << 8);
        }
    }
    std::function<void(int)> on_fifo_request;
    std::function<void(std::int64_t)> on_batch_nanos;
    SquareChannel square1;
    SquareChannel square2;
    WaveChannel wave;
    NoiseChannel noise;
    int control_l{};
    int control_h{};
    bool master_enable{};
    int underruns{};

private:
    struct Fifo {
        std::array<std::int8_t, 32> data{};
        int head{}; int tail{}; int size{};
        void push(std::int8_t value) noexcept {
            if (size >= 32) return;
            data[static_cast<std::size_t>(tail)] = value; tail = (tail + 1) & 31; ++size;
        }
        int pop() noexcept {
            if (size == 0) return 0;
            const auto value = data[static_cast<std::size_t>(head)]; head = (head + 1) & 31; --size;
            return value;
        }
        void clear() noexcept { head = 0; tail = 0; size = 0; }
        /** Nombre de mots que la file occupe dans un état instantané. */
        static constexpr std::size_t state_words = 35;
        [[nodiscard]] std::array<std::int32_t, state_words> export_state() const noexcept {
            std::array<std::int32_t, state_words> result{};
            for (std::size_t i = 0; i < data.size(); ++i) result[i] = data[i];
            result[32] = head; result[33] = tail; result[34] = size;
            return result;
        }
        void import_state(std::span<const std::int32_t> state) noexcept {
            for (std::size_t i = 0; i < data.size(); ++i) data[i] = static_cast<std::int8_t>(state[i]);
            head = state[32]; tail = state[33]; size = state[34];
        }
    };
    void pop_fifo(int channel) {
        auto& fifo = channel == 0 ? fifo_a_ : fifo_b_;
        if (fifo.size == 0) ++fifo_empty_reads_[static_cast<std::size_t>(channel)];
        const auto sample = fifo.pop();
        if (channel == 0) direct_a_ = sample; else direct_b_ = sample;
        if (fifo.size <= 16 && on_fifo_request) on_fifo_request(channel);
    }
    void reset_fifo(int channel) noexcept {
        if (channel == 0) { fifo_a_.clear(); direct_a_ = 0; }
        else { fifo_b_.clear(); direct_b_ = 0; }
    }
    static void write_square_duty(SquareChannel& channel, int value) {
        channel.length_counter = 64 - (value & 0x3f); channel.duty = (value >> 6) & 3;
        channel.envelope.write((value >> 8) & 0xff);
        if (!channel.envelope.dac_enabled()) channel.enabled = false;
    }
    static void write_square_frequency(SquareChannel& channel, int value) {
        channel.frequency = value & 0x7ff; channel.length_enabled = (value & 0x4000) != 0;
        if ((value & 0x8000) != 0) channel.trigger();
    }
    void clock_sequencer() {
        if (sequencer_step_ == 0 || sequencer_step_ == 4) clock_lengths();
        else if (sequencer_step_ == 2 || sequencer_step_ == 6) { clock_lengths(); square1.clock_sweep(); }
        else if (sequencer_step_ == 7) { square1.envelope.clock(); square2.envelope.clock(); noise.envelope.clock(); }
        sequencer_step_ = (sequencer_step_ + 1) & 7;
    }
    void clock_lengths() noexcept { square1.clock_length(); square2.clock_length(); wave.clock_length(); noise.clock_length(); }
    void emit_sample() {
        if (!master_enable) {
            // Les cumuls sont vidés même à l'arrêt : les garder ferait verser
            // la fenêtre d'avant dans le premier échantillon d'après.
            drain_channels();
            push_sample(0, 0);
            return;
        }
        // **La moyenne sur la fenêtre, et non la valeur de l'instant.** Un
        // canal carré change d'état bien plus vite que le débit de sortie ; le
        // prélever d'un coup replierait ses harmoniques dans l'audible, où
        // elles s'entendent comme des sifflements sans rapport avec le
        // morceau. Le cœur Game Boy procède ainsi depuis toujours.
        const auto outputs = drain_channels();
        int left{}; int right{};
        for (std::size_t i = 0; i < outputs.size(); ++i) {
            if ((control_l & (1 << (8 + static_cast<int>(i)))) != 0) left += outputs[i];
            if ((control_l & (1 << (12 + static_cast<int>(i)))) != 0) right += outputs[i];
        }
        static constexpr std::array ratio{64, 128, 256, 256};
        // Une seule division, tout à la fin. Les cumuls valent au plus quinze
        // fois la fenêtre, soit mille neuf cent vingt par canal ; le produit
        // complet, volume et proportion compris, tient largement dans un entier
        // de trente-deux bits, et le résultat ne dépend d'aucun arrondi
        // intermédiaire.
        const auto psg_ratio = ratio[static_cast<std::size_t>(control_h & 3)];
        auto mixed_left = left * (((control_l >> 4) & 7) + 1) * psg_scale * psg_ratio
            / (256 * cycles_per_sample);
        auto mixed_right = right * ((control_l & 7) + 1) * psg_scale * psg_ratio
            / (256 * cycles_per_sample);
        const auto direct_a = direct_a_ * 32 * ((control_h & 4) != 0 ? 2 : 1);
        const auto direct_b = direct_b_ * 32 * ((control_h & 8) != 0 ? 2 : 1);
        // Les deux voies directes ne se moyennent pas : elles portent déjà des
        // échantillons, tenus jusqu'au suivant. Les lisser reviendrait à
        // filtrer deux fois ce que le jeu a lui-même échantillonné.
        if ((control_h & 0x0200) != 0) mixed_left += direct_a;
        if ((control_h & 0x0100) != 0) mixed_right += direct_a;
        if ((control_h & 0x2000) != 0) mixed_left += direct_b;
        if ((control_h & 0x1000) != 0) mixed_right += direct_b;
        push_sample(std::clamp(mixed_left, -32768, 32767), std::clamp(mixed_right, -32768, 32767));
    }

    /** Vide les quatre cumuls et les rend, dans l'ordre des canaux. */
    std::array<int, 4> drain_channels() noexcept {
        return {
            square1.drain_accumulator(),
            square2.drain_accumulator(),
            wave.drain_accumulator(),
            noise.drain_accumulator(),
        };
    }
    void push_sample(int left, int right) noexcept {
        if (available_ + 2U > ring_.size()) return;
        ring_[write_index_] = static_cast<std::int16_t>(left); write_index_ = (write_index_ + 1U) & ring_mask;
        ring_[write_index_] = static_cast<std::int16_t>(right); write_index_ = (write_index_ + 1U) & ring_mask;
        available_ += 2;
    }
    static constexpr int cycles_per_sample = 128;
    /** Mise à l'échelle des canaux PSG vers le PCM seize bits. */
    static constexpr int psg_scale = 24;
    static constexpr std::size_t ring_mask = 8191;
    // Le compte de mots ci-dessus n'a de sens que s'il suit ses canaux : un
    // état instantané tronqué par un mot de trop se lirait sans jamais lever
    // d'erreur, en décalant tous les canaux d'un cran.
    static_assert(state_words == 3 + 2 * SquareChannel::state_words + WaveChannel::state_words
        + NoiseChannel::state_words + 2 * Fifo::state_words + 8,
        "Le compte de mots de l'APU doit suivre ses canaux");
    Fifo fifo_a_;
    Fifo fifo_b_;
    std::array<int, 2> fifo_empty_reads_{};
    int direct_a_{};
    int direct_b_{};
    int cpu_remainder_{};
    int sample_timer_{};
    int sequencer_timer_{};
    int sequencer_step_{};
    std::array<std::int16_t, 8192> ring_{};
    std::size_t write_index_{};
    std::size_t read_index_{};
    std::size_t available_{};
    bool measuring_time_{};
};

} // namespace ravenemu::gba
