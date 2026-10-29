#pragma once

#include "support/bits.hpp"
#include <ravenemu/gb/hardware_mode.hpp>

namespace ravenemu::cgb {

class Apu {
public:
    static constexpr int sample_rate = 32768;

    explicit Apu(gb::HardwareMode hardware_mode = gb::HardwareMode::dmg)
        : hardware_mode_(hardware_mode) {}

    void tick(int cycles) {
        int remaining = cycles;
        while (remaining > 0) {
            const int slice = std::min(sample_timer_, remaining);
            if (power_on_) {
                square1_.step(slice); square2_.step(slice); wave_.step(slice); noise_.step(slice);
            }
            sample_timer_ -= slice;
            remaining -= slice;
            if (sample_timer_ <= 0) { sample_timer_ += cycles_per_sample; emit_sample(); }
        }
    }

    /** Front descendant du bit DIV sélectionné par la vitesse CPU. */
    void clock_divider_falling_edge() noexcept {
        // NR52 coupe les canaux mais pas le compteur DIV-APU : sa phase doit
        // continuer à avancer pour les écritures effectuées après rallumage.
        if (power_on_) clock_frame_sequencer();
        else frame_step_ = (frame_step_ + 1) & 7;
    }

    [[nodiscard]] std::size_t read_samples(std::span<std::int16_t> destination) noexcept {
        std::size_t copied{};
        const auto maximum = destination.size() & ~std::size_t{1};
        while (copied < maximum && ring_count_ > 0) {
            destination[copied++] = ring_[ring_read_];
            ring_read_ = (ring_read_ + 1) & (ring_capacity - 1);
            --ring_count_;
        }
        return copied;
    }

    [[nodiscard]] int read_pcm12() const noexcept {
        return square1_.output() | (square2_.output() << 4);
    }

    [[nodiscard]] int read_pcm34() const noexcept {
        return wave_.output() | (noise_.output() << 4);
    }

    void reset_for_boot_rom() noexcept {
        power_down();
        frame_step_ = 0;
        sample_timer_ = cycles_per_sample;
        ring_read_ = 0; ring_write_ = 0; ring_count_ = 0;
        capacitor_left_ = 0; capacitor_right_ = 0;
    }

    /** État observable à l'entrée $0100 sans redistribuer de boot ROM. */
    void initialize_hle_post_boot() noexcept {
        square1_.reset(); square2_.reset(); wave_.reset(); noise_.reset();
        power_on_ = true;
        raw_registers_.fill(0);
        nr50_ = 0x77;
        nr51_ = 0xf3;
        raw_registers_[0x01] = 0x80; // NR11 : duty 2, longueur masquée en lecture
        raw_registers_[0x02] = 0xf3; // NR12
        raw_registers_[0x14] = nr50_;
        raw_registers_[0x15] = nr51_;
        square1_.duty = 2;
        square1_.dac_enabled = true;
        square1_.enabled = true;
        square1_.envelope_initial = 15;
        square1_.envelope_period = 3;
        // **Zéro, et non quinze.** Quinze est le volume *initial* que NR12
        // déclare ; ce n'est pas celui du canal quand le jeu prend la main.
        //
        // La ROM d'amorçage déclenche le canal 1 pour son carillon pendant le
        // défilement du logo. L'enveloppe décroissante de période trois retire
        // un cran tous les trois tops du séquenceur à 64 Hz : quinze crans en
        // sept dixièmes de seconde, quand le défilement en dure plusieurs. À
        // l'entrée en $0100, le carillon est donc éteint depuis longtemps.
        //
        // Le canal reste *signalé* allumé dans NR52 — sa longueur n'a jamais
        // été armée, NR14 bit 6 étant à zéro — mais il ne produit plus rien.
        // Reproduire le volume initial faisait sonner un créneau à pleine
        // amplitude pendant les huit premiers dixièmes de seconde de chaque
        // partie, avant la musique du titre.
        square1_.volume = 0;
        square1_.envelope_timer = 3;
        square1_.length = 1;
        square1_.timer_running = true;
        square1_.force_first_step_zero = false;
        frame_step_ = 0;
        sample_timer_ = cycles_per_sample;
        ring_read_ = 0; ring_write_ = 0; ring_count_ = 0;
        prime_capacitors();
    }

    [[nodiscard]] int read(int address) const noexcept {
        if (address >= 0xff30 && address <= 0xff3f) {
            return read_wave_ram(address - 0xff30);
        }
        const int offset = address - 0xff10;
        if (offset < 0 || offset >= static_cast<int>(raw_registers_.size())) return 0xff;
        if (address == 0xff26) {
            int status = power_on_ ? 0x80 : 0;
            if (square1_.enabled) status |= 1;
            if (square2_.enabled) status |= 2;
            if (wave_.enabled) status |= 4;
            if (noise_.enabled) status |= 8;
            return status | 0x70;
        }
        return raw_registers_[static_cast<std::size_t>(offset)] | read_masks[static_cast<std::size_t>(offset)];
    }

    /**
     * Écriture d'un registre audio.
     *
     * Le mixeur peut s'y connecter ou s'en déconnecter — un DAC qu'on allume,
     * NR52 qu'on coupe. Quand cela survient au milieu d'une fenêtre déjà
     * entamée, la fenêtre est à cheval sur deux régimes et sa moyenne ne
     * représente ni l'un ni l'autre ; [emit_sample] la traite à part.
     */
    void write(int address, int value) {
        const bool connected_before = power_on_ && any_dac_enabled();
        write_register(address, value);
        if (sample_timer_ < cycles_per_sample &&
            connected_before != (power_on_ && any_dac_enabled())) {
            mixer_changed_mid_window_ = true;
        }
    }

private:
    void write_register(int address, int value) {
        value = byte(value);
        if (address >= 0xff30 && address <= 0xff3f) {
            write_wave_ram(address - 0xff30, value);
            return;
        }
        const int offset = address - 0xff10;
        if (offset < 0 || offset >= static_cast<int>(raw_registers_.size())) return;
        if (!power_on_ && address != 0xff26) {
            write_length_while_powered_off(address, value);
            return;
        }
        raw_registers_[static_cast<std::size_t>(offset)] = value;
        switch (address) {
        case 0xff10: {
            const bool new_negate = (value & 8) != 0;
            if (square1_.sweep_negate && !new_negate && square1_.sweep_negate_used) {
                square1_.enabled = false;
            }
            square1_.sweep_period = (value >> 4) & 7; square1_.sweep_negate = new_negate;
            square1_.sweep_shift = value & 7; break;
        }
        case 0xff11: square1_.duty = (value >> 6) & 3; square1_.length = 64 - (value & 0x3f); break;
        case 0xff12: configure_envelope(square1_, value); break;
        case 0xff13: square1_.frequency = (square1_.frequency & 0x700) | value; break;
        case 0xff14:
            square1_.frequency = (square1_.frequency & 0xff) | ((value & 7) << 8);
            write_channel_control(square1_, value); break;
        case 0xff16: square2_.duty = (value >> 6) & 3; square2_.length = 64 - (value & 0x3f); break;
        case 0xff17: configure_envelope(square2_, value); break;
        case 0xff18: square2_.frequency = (square2_.frequency & 0x700) | value; break;
        case 0xff19:
            square2_.frequency = (square2_.frequency & 0xff) | ((value & 7) << 8);
            write_channel_control(square2_, value); break;
        case 0xff1a: wave_.dac_enabled = (value & 0x80) != 0; if (!wave_.dac_enabled) wave_.enabled = false; break;
        case 0xff1b: wave_.length = 256 - value; break;
        case 0xff1c: wave_.volume_code = (value >> 5) & 3; break;
        case 0xff1d: wave_.frequency = (wave_.frequency & 0x700) | value; break;
        case 0xff1e:
            wave_.frequency = (wave_.frequency & 0xff) | ((value & 7) << 8);
            if ((value & 0x80) != 0 && !gb::is_cgb_hardware(hardware_mode_)) {
                corrupt_dmg_wave_ram_on_retrigger();
            }
            write_channel_control(wave_, value); break;
        case 0xff20: noise_.length = 64 - (value & 0x3f); break;
        case 0xff21: configure_envelope(noise_, value); break;
        case 0xff22:
            noise_.clock_shift = (value >> 4) & 0x0f; noise_.width_mode7 = (value & 8) != 0;
            noise_.divisor_code = value & 7; break;
        case 0xff23:
            write_channel_control(noise_, value); break;
        case 0xff24: nr50_ = value; break;
        case 0xff25: nr51_ = value; break;
        case 0xff26: {
            const bool turn_on = (value & 0x80) != 0;
            if (power_on_ && !turn_on) power_down();
            else if (!power_on_ && turn_on) power_on_ = true;
            break;
        }
        default: break;
        }
    }

public:
    void save(BinaryWriter& out) const {
        out.i32(state_layout);
        out.boolean(power_on_); out.i32(nr50_); out.i32(nr51_);
        for (const int value : raw_registers_) out.i32(value);
        out.i32(frame_step_); out.i32(sample_timer_);
        out.f64(capacitor_left_); out.f64(capacitor_right_);
        square1_.save(out); square2_.save(out); wave_.save(out); noise_.save(out);
    }
    void load(BinaryReader& in) {
        if (in.i32() != state_layout) throw SaveStateError("État instantané corrompu (APU)");
        power_on_ = in.boolean(); nr50_ = in.i32(); nr51_ = in.i32();
        for (auto& value : raw_registers_) value = in.i32();
        frame_step_ = in.i32(); sample_timer_ = in.i32();
        const bool invalid_register = std::any_of(
            raw_registers_.begin(), raw_registers_.end(),
            [](int value) { return value < 0 || value > 0xff; });
        if (nr50_ < 0 || nr50_ > 0xff || nr51_ < 0 || nr51_ > 0xff ||
            frame_step_ < 0 || frame_step_ > 7 ||
            sample_timer_ <= 0 || sample_timer_ > cycles_per_sample ||
            invalid_register) {
            throw SaveStateError("État instantané corrompu (APU)");
        }
        capacitor_left_ = in.f64(); capacitor_right_ = in.f64();
        square1_.load(in); square2_.load(in); wave_.load(in); noise_.load(in);
        ring_read_ = 0; ring_write_ = 0; ring_count_ = 0;
        mixer_changed_mid_window_ = false;
    }

private:
    struct Square {
        explicit Square(bool sweep) : has_sweep(sweep) {}
        bool has_sweep{};
        bool enabled{};
        bool dac_enabled{};
        int duty{};
        int length{};
        bool length_enabled{};
        int envelope_initial{};
        bool envelope_add{};
        int envelope_period{};
        int volume{};
        int envelope_timer{};
        int frequency{};
        int timer{2048 * 4};
        int duty_position{};
        int sweep_period{};
        bool sweep_negate{};
        int sweep_shift{};
        int sweep_timer{};
        bool sweep_enabled{};
        int shadow_frequency{};
        bool sweep_negate_used{};
        int accumulator{};
        bool timer_running{};
        bool force_first_step_zero{true};

        [[nodiscard]] int output() const noexcept {
            return enabled && dac_enabled && !force_first_step_zero &&
                duty_table[static_cast<std::size_t>(duty)][static_cast<std::size_t>(duty_position)] == 1
                ? volume : 0;
        }
        [[nodiscard]] int dac_output() const noexcept { return dac_enabled ? 15 - output() * 2 : 0; }
        void step(int cycles) noexcept {
            if (!timer_running) {
                accumulator += dac_output() * cycles;
                return;
            }
            int remaining = cycles;
            while (remaining > 0) {
                const int slice = std::min(timer, remaining);
                accumulator += dac_output() * slice; timer -= slice; remaining -= slice;
                if (timer <= 0) {
                    timer = (2048 - frequency) * 4;
                    duty_position = (duty_position + 1) & 7;
                    force_first_step_zero = false;
                }
            }
        }
        double drain_average() noexcept {
            const double average = static_cast<double>(accumulator) / cycles_per_sample;
            accumulator = 0; return average;
        }
        void clock_length() noexcept { if (length_enabled && length > 0 && --length == 0) enabled = false; }
        /**
         * Cadence l'enveloppe de volume, à 64 Hz.
         *
         * Deux règles distinctes, et c'est leur confusion qui coûte cher :
         *
         * - le **compteur** traite une période nulle comme une période de
         *   huit ; c'est ce qui fixe l'instant du prochain top si le jeu
         *   réécrit NRx2 avec une période non nulle sans redéclencher ;
         * - le **volume**, lui, ne bouge que si la période est non nulle. Une
         *   période nulle veut dire « pas d'enveloppe » : le volume programmé
         *   est tenu tant que le canal joue.
         *
         * Ne garder que la première règle fait perdre un cran de volume toutes
         * les huit périodes à *tout* son de volume fixe, soit une extinction
         * complète en moins de deux secondes. C'est le cas le plus courant de
         * la musique Game Boy, qui tient ses notes à volume constant : les
         * tenues et les basses disparaissaient en cours de morceau.
         */
        void clock_envelope() noexcept {
            if (--envelope_timer > 0) return;
            envelope_timer = envelope_period != 0 ? envelope_period : 8;
            if (envelope_period == 0) return;
            volume = envelope_add ? std::min(15, volume + 1) : std::max(0, volume - 1);
        }
        int next_sweep_frequency() noexcept {
            const int delta = shadow_frequency >> sweep_shift;
            const int next = sweep_negate ? shadow_frequency - delta : shadow_frequency + delta;
            if (sweep_negate) sweep_negate_used = true;
            if (next > 2047) enabled = false;
            return next;
        }
        void clock_sweep() noexcept {
            if (!has_sweep || !sweep_enabled) return;
            if (--sweep_timer <= 0) {
                sweep_timer = sweep_period != 0 ? sweep_period : 8;
                if (sweep_period != 0) {
                    const int next = next_sweep_frequency();
                    if (next <= 2047 && sweep_shift != 0) {
                        shadow_frequency = next; frequency = next; static_cast<void>(next_sweep_frequency());
                    }
                }
            }
        }
        void trigger(bool shorten_zero_length_reload, bool delay_envelope_clock) noexcept {
            enabled = dac_enabled;
            if (length == 0) {
                length = 64;
                if (shorten_zero_length_reload) --length;
            }
            timer = ((2048 - frequency) * 4) | (timer & 3);
            timer_running = true;
            volume = envelope_initial;
            envelope_timer = (envelope_period != 0 ? envelope_period : 8) +
                (delay_envelope_clock ? 1 : 0);
            if (has_sweep) {
                shadow_frequency = frequency; sweep_timer = sweep_period != 0 ? sweep_period : 8;
                sweep_enabled = sweep_period != 0 || sweep_shift != 0;
                sweep_negate_used = false;
                if (sweep_shift != 0) static_cast<void>(next_sweep_frequency());
            }
        }
        void reset() noexcept {
            enabled = false; dac_enabled = false; duty = 0; length = 0; length_enabled = false;
            envelope_initial = 0; envelope_add = false; envelope_period = 0; volume = 0;
            envelope_timer = 0; frequency = 0; duty_position = 0; sweep_period = 0;
            sweep_negate = false; sweep_shift = 0; sweep_timer = 0; sweep_enabled = false;
            shadow_frequency = 0; sweep_negate_used = false;
            timer = 2048 * 4; accumulator = 0; timer_running = false;
            force_first_step_zero = true;
        }
        void save(BinaryWriter& out) const {
            out.boolean(enabled); out.boolean(dac_enabled); out.i32(duty); out.i32(length);
            out.boolean(length_enabled); out.i32(envelope_initial); out.boolean(envelope_add);
            out.i32(envelope_period); out.i32(volume); out.i32(envelope_timer);
            out.i32(frequency); out.i32(timer); out.i32(duty_position); out.i32(sweep_period);
            out.boolean(sweep_negate); out.i32(sweep_shift); out.i32(sweep_timer);
            out.boolean(sweep_enabled); out.i32(shadow_frequency); out.boolean(sweep_negate_used);
            out.i32(accumulator); out.boolean(timer_running); out.boolean(force_first_step_zero);
        }
        void load(BinaryReader& in) {
            enabled = in.boolean(); dac_enabled = in.boolean(); duty = in.i32(); length = in.i32();
            length_enabled = in.boolean(); envelope_initial = in.i32(); envelope_add = in.boolean();
            envelope_period = in.i32(); volume = in.i32(); envelope_timer = in.i32();
            frequency = in.i32(); timer = in.i32(); duty_position = in.i32(); sweep_period = in.i32();
            sweep_negate = in.boolean(); sweep_shift = in.i32(); sweep_timer = in.i32();
            sweep_enabled = in.boolean(); shadow_frequency = in.i32();
            sweep_negate_used = in.boolean(); accumulator = in.i32();
            timer_running = in.boolean(); force_first_step_zero = in.boolean();
        }
    };

    struct Wave {
        std::array<std::uint8_t, 16> ram{};
        bool enabled{};
        bool dac_enabled{};
        int length{};
        bool length_enabled{};
        int volume_code{};
        int frequency{};
        int timer{2048 * 2};
        int position{};
        int sample_buffer{};
        int ram_access_cycles{};
        int accumulator{};

        [[nodiscard]] int output() const noexcept {
            if (!enabled || !dac_enabled) return 0;
            if (volume_code == 0) return 0;
            if (volume_code == 1) return sample_buffer;
            return volume_code == 2 ? sample_buffer >> 1 : sample_buffer >> 2;
        }
        [[nodiscard]] int dac_output() const noexcept { return dac_enabled ? 15 - output() * 2 : 0; }
        void step(int cycles) noexcept {
            int remaining = cycles;
            while (remaining > 0) {
                const int slice = std::min(timer, remaining);
                ram_access_cycles = std::max(0, ram_access_cycles - slice);
                accumulator += dac_output() * slice; timer -= slice; remaining -= slice;
                if (timer <= 0) {
                    timer = (2048 - frequency) * 2; position = (position + 1) & 31;
                    const int value = ram[static_cast<std::size_t>(position >> 1)];
                    sample_buffer = (position & 1) == 0 ? value >> 4 : value & 0x0f;
                    ram_access_cycles = 2;
                }
            }
        }
        double drain_average() noexcept {
            const double average = static_cast<double>(accumulator) / cycles_per_sample;
            accumulator = 0; return average;
        }
        void clock_length() noexcept { if (length_enabled && length > 0 && --length == 0) enabled = false; }
        void trigger(bool shorten_zero_length_reload, bool /* delay_envelope_clock */) noexcept {
            enabled = dac_enabled;
            if (length == 0) {
                length = 256;
                if (shorten_zero_length_reload) --length;
            }
            timer = (2048 - frequency) * 2; position = 0; ram_access_cycles = 0;
        }
        void reset() noexcept {
            enabled = false; dac_enabled = false; length = 0; length_enabled = false;
            volume_code = 0; frequency = 0; timer = 2048 * 2;
            position = 0; sample_buffer = 0; ram_access_cycles = 0; accumulator = 0;
        }
        void save(BinaryWriter& out) const {
            out.raw(ram); out.boolean(enabled); out.boolean(dac_enabled); out.i32(length);
            out.boolean(length_enabled); out.i32(volume_code); out.i32(frequency);
            out.i32(timer); out.i32(position); out.i32(sample_buffer); out.i32(ram_access_cycles);
            out.i32(accumulator);
        }
        void load(BinaryReader& in) {
            in.raw(ram); enabled = in.boolean(); dac_enabled = in.boolean(); length = in.i32();
            length_enabled = in.boolean(); volume_code = in.i32(); frequency = in.i32();
            timer = in.i32(); position = in.i32(); sample_buffer = in.i32();
            ram_access_cycles = std::max(0, in.i32()); accumulator = in.i32();
        }
    };

    struct Noise {
        bool enabled{};
        bool dac_enabled{};
        int length{};
        bool length_enabled{};
        int envelope_initial{};
        bool envelope_add{};
        int envelope_period{};
        int volume{};
        int envelope_timer{};
        int divisor_code{};
        bool width_mode7{};
        int clock_shift{};
        int lfsr{0x7fff};
        int timer{8};
        int accumulator{};

        [[nodiscard]] int period() const noexcept { return divisors[static_cast<std::size_t>(divisor_code)] << clock_shift; }
        [[nodiscard]] int output() const noexcept { return enabled && dac_enabled && (lfsr & 1) == 0 ? volume : 0; }
        [[nodiscard]] int dac_output() const noexcept { return dac_enabled ? 15 - output() * 2 : 0; }
        void step(int cycles) noexcept {
            int remaining = cycles;
            while (remaining > 0) {
                const int slice = std::min(timer, remaining);
                accumulator += dac_output() * slice; timer -= slice; remaining -= slice;
                if (timer <= 0) {
                    timer += period();
                    // Les valeurs 14 et 15 déconnectent l'horloge du LFSR :
                    // le timer interne avance, mais aucun nouveau bit n'est produit.
                    if (clock_shift < 14) {
                        const int feedback = (lfsr ^ (lfsr >> 1)) & 1;
                        lfsr = (lfsr >> 1) | (feedback << 14);
                        if (width_mode7) lfsr = (lfsr & ~(1 << 6)) | (feedback << 6);
                    }
                }
            }
        }
        double drain_average() noexcept {
            const double average = static_cast<double>(accumulator) / cycles_per_sample;
            accumulator = 0; return average;
        }
        void clock_length() noexcept { if (length_enabled && length > 0 && --length == 0) enabled = false; }
        /** Même règle que pour les canaux carrés : période nulle, volume tenu. */
        void clock_envelope() noexcept {
            if (--envelope_timer > 0) return;
            envelope_timer = envelope_period != 0 ? envelope_period : 8;
            if (envelope_period == 0) return;
            volume = envelope_add ? std::min(15, volume + 1) : std::max(0, volume - 1);
        }
        void trigger(bool shorten_zero_length_reload, bool delay_envelope_clock) noexcept {
            enabled = dac_enabled;
            if (length == 0) {
                length = 64;
                if (shorten_zero_length_reload) --length;
            }
            timer = period();
            volume = envelope_initial;
            envelope_timer = (envelope_period != 0 ? envelope_period : 8) +
                (delay_envelope_clock ? 1 : 0);
            lfsr = 0x7fff;
        }
        void reset() noexcept {
            enabled = false; dac_enabled = false; length = 0; length_enabled = false;
            envelope_initial = 0; envelope_add = false; envelope_period = 0; volume = 0;
            envelope_timer = 0; divisor_code = 0; width_mode7 = false; clock_shift = 0;
            lfsr = 0x7fff; timer = 8; accumulator = 0;
        }
        void save(BinaryWriter& out) const {
            out.boolean(enabled); out.boolean(dac_enabled); out.i32(length); out.boolean(length_enabled);
            out.i32(envelope_initial); out.boolean(envelope_add); out.i32(envelope_period);
            out.i32(volume); out.i32(envelope_timer); out.i32(divisor_code);
            out.boolean(width_mode7); out.i32(clock_shift); out.i32(lfsr); out.i32(timer);
            out.i32(accumulator);
        }
        void load(BinaryReader& in) {
            enabled = in.boolean(); dac_enabled = in.boolean(); length = in.i32(); length_enabled = in.boolean();
            envelope_initial = in.i32(); envelope_add = in.boolean(); envelope_period = in.i32();
            volume = in.i32(); envelope_timer = in.i32(); divisor_code = in.i32();
            width_mode7 = in.boolean(); clock_shift = in.i32(); lfsr = in.i32(); timer = in.i32();
            accumulator = in.i32();
        }
    };

    [[nodiscard]] int read_wave_ram(int requested_index) const noexcept {
        if (!wave_.enabled) {
            return wave_.ram[static_cast<std::size_t>(requested_index & 0x0f)];
        }
        if (!gb::is_cgb_hardware(hardware_mode_) && wave_.ram_access_cycles <= 0) return 0xff;
        return wave_.ram[static_cast<std::size_t>((wave_.position >> 1) & 0x0f)];
    }

    void write_wave_ram(int requested_index, int value) noexcept {
        int index = requested_index & 0x0f;
        if (wave_.enabled) {
            if (!gb::is_cgb_hardware(hardware_mode_) && wave_.ram_access_cycles <= 0) return;
            index = (wave_.position >> 1) & 0x0f;
        }
        wave_.ram[static_cast<std::size_t>(index)] = static_cast<std::uint8_t>(value);
    }

    template <typename Channel>
    void write_channel_control(Channel& channel, int value) noexcept {
        const bool trigger = (value & 0x80) != 0;
        const bool was_length_enabled = channel.length_enabled;
        channel.length_enabled = (value & 0x40) != 0;
        const bool extra_length_clock = (frame_step_ & 1) != 0;

        // Activer le compteur pendant une phase où la prochaine étape ne le
        // cadence pas produit immédiatement un clock supplémentaire. Si la
        // même écriture déclenche le canal, l'extinction éventuelle est
        // remplacée par le reload du trigger.
        if (!was_length_enabled && channel.length_enabled && extra_length_clock && channel.length > 0) {
            --channel.length;
            if (channel.length == 0 && !trigger) channel.enabled = false;
        }
        if (trigger) {
            channel.trigger(channel.length_enabled && extra_length_clock, frame_step_ == 7);
        }
    }

    void corrupt_dmg_wave_ram_on_retrigger() noexcept {
        if (!wave_.enabled || wave_.ram_access_cycles <= 0) return;
        const int current_byte = (wave_.position >> 1) & 0x0f;
        if (current_byte < 4) {
            wave_.ram[0] = wave_.ram[static_cast<std::size_t>(current_byte)];
            return;
        }
        const int source = current_byte & ~3;
        for (int index = 0; index < 4; ++index) {
            wave_.ram[static_cast<std::size_t>(index)] =
                wave_.ram[static_cast<std::size_t>(source + index)];
        }
    }

    void write_length_while_powered_off(int address, int value) noexcept {
        // Sur les modèles monochromes, les quatre compteurs de longueur sont
        // encore câblés lorsque NR52 coupe le reste de l'APU. Le CGB bloque
        // aussi ces écritures.
        if (gb::is_cgb_hardware(hardware_mode_)) return;
        switch (address) {
        case 0xff11: square1_.length = 64 - (value & 0x3f); break;
        case 0xff16: square2_.length = 64 - (value & 0x3f); break;
        case 0xff1b: wave_.length = 256 - value; break;
        case 0xff20: noise_.length = 64 - (value & 0x3f); break;
        default: break;
        }
    }

    template <typename Channel>
    static void configure_envelope(Channel& channel, int value) noexcept {
        // Le seul comportement "zombie" commun aux révisions documentées :
        // réécrire $08 pendant une enveloppe croissante de période 0 avance
        // manuellement le volume d'un cran.
        if (channel.enabled && channel.envelope_add && channel.envelope_period == 0 &&
            (value & 0x0f) == 0x08) {
            channel.volume = (channel.volume + 1) & 0x0f;
        }
        channel.envelope_initial = (value >> 4) & 0x0f;
        channel.envelope_add = (value & 8) != 0;
        channel.envelope_period = value & 7;
        channel.dac_enabled = (value & 0xf8) != 0;
        if (!channel.dac_enabled) channel.enabled = false;
    }
    void clock_frame_sequencer() noexcept {
        if (frame_step_ == 0 || frame_step_ == 4) clock_lengths();
        else if (frame_step_ == 2 || frame_step_ == 6) { clock_lengths(); square1_.clock_sweep(); }
        else if (frame_step_ == 7) { square1_.clock_envelope(); square2_.clock_envelope(); noise_.clock_envelope(); }
        frame_step_ = (frame_step_ + 1) & 7;
    }
    void clock_lengths() noexcept {
        square1_.clock_length(); square2_.clock_length(); wave_.clock_length(); noise_.clock_length();
    }
    static std::int16_t clamp_short(double value) noexcept {
        return static_cast<std::int16_t>(std::clamp(static_cast<int>(value), -32768, 32767));
    }
    /**
     * Aiguillage NR51 et volumes maîtres NR50, pour quatre sorties de canaux.
     *
     * Partagé entre le prélèvement d'un échantillon et l'amorçage du
     * condensateur : les deux doivent voir exactement le même niveau, sans quoi
     * l'amorçage laisserait justement la marche qu'il vise à supprimer.
     */
    [[nodiscard]] std::pair<double, double> route(
        double c1, double c2, double c3, double c4) const noexcept {
        double left{}; double right{};
        if ((nr51_ & 0x10) != 0) left += c1;
        if ((nr51_ & 0x20) != 0) left += c2;
        if ((nr51_ & 0x40) != 0) left += c3;
        if ((nr51_ & 0x80) != 0) left += c4;
        if ((nr51_ & 0x01) != 0) right += c1;
        if ((nr51_ & 0x02) != 0) right += c2;
        if ((nr51_ & 0x04) != 0) right += c3;
        if ((nr51_ & 0x08) != 0) right += c4;
        left *= static_cast<double>(((nr50_ >> 4) & 7) + 1) * mix_gain;
        right *= static_cast<double>((nr50_ & 7) + 1) * mix_gain;
        return {left, right};
    }

    /**
     * Charge le condensateur au niveau continu courant.
     *
     * Le filtre ne laisse passer que l'écart entre l'entrée et la charge : un
     * condensateur à zéro devant un convertisseur qui repose déjà sur un
     * niveau non nul produit une marche de toute la hauteur de ce niveau, puis
     * sa décroissance — un claquement de quelques millisecondes.
     *
     * Sur le matériel, la question ne se pose pas : quand le jeu prend la main,
     * la ROM d'amorçage a alimenté le convertisseur depuis plusieurs secondes
     * et le condensateur est chargé depuis longtemps. C'est cet état-là que
     * l'entrée en $0100 doit installer, et non celui d'une machine dont on
     * vient de brancher le haut-parleur.
     */
    void prime_capacitors() noexcept {
        const auto [left, right] = route(
            square1_.dac_output(), square2_.dac_output(),
            wave_.dac_output(), noise_.dac_output());
        capacitor_left_ = left;
        capacitor_right_ = right;
    }

    void emit_sample() noexcept {
        const double c1 = square1_.drain_average(); const double c2 = square2_.drain_average();
        const double c3 = wave_.drain_average(); const double c4 = noise_.drain_average();
        if (!power_on_ || !any_dac_enabled()) {
            // Le condensateur est physiquement déconnecté lorsque les quatre
            // DAC sont coupés : sortie nulle et charge conservée.
            mixer_changed_mid_window_ = false;
            push_sample(0, 0);
            return;
        }
        if (mixer_changed_mid_window_) {
            // Fenêtre à cheval sur une reconnexion du mixeur. Une partie s'est
            // écoulée mixeur coupé — sortie nulle par la règle ci-dessus — et
            // l'autre mixeur rétabli, sur un niveau continu que la charge
            // gardée pendant la coupure rejoint exactement. Les deux moitiés
            // valent donc zéro séparément.
            //
            // Leur *moyenne*, elle, ne vaut zéro que par accident : elle mêle
            // les deux dans une proportion qui ne tient qu'à l'instant où le
            // jeu a écrit son registre. Filtrée contre la charge, elle sortait
            // une impulsion d'un seul échantillon, jusqu'à un sixième de la
            // dynamique — le tic entendu à l'ouverture d'une partie, quand le
            // jeu éteint puis rallume l'APU avant de jouer sa première note.
            //
            // On rend donc le zéro que valent les deux moitiés, et on reprend
            // la charge sur le niveau retrouvé : le régime établi commence à la
            // fenêtre suivante, sans marche.
            mixer_changed_mid_window_ = false;
            prime_capacitors();
            push_sample(0, 0);
            return;
        }
        const auto [left, right] = route(c1, c2, c3, c4);
        const double charge_factor = gb::is_cgb_hardware(hardware_mode_)
            ? cgb_charge_factor : dmg_charge_factor;
        const double filtered_left = left - capacitor_left_;
        capacitor_left_ = left - filtered_left * charge_factor;
        const double filtered_right = right - capacitor_right_;
        capacitor_right_ = right - filtered_right * charge_factor;
        push_sample(clamp_short(filtered_left), clamp_short(filtered_right));
    }
    [[nodiscard]] bool any_dac_enabled() const noexcept {
        return square1_.dac_enabled || square2_.dac_enabled || wave_.dac_enabled || noise_.dac_enabled;
    }
    void push_sample(std::int16_t left, std::int16_t right) noexcept {
        if (ring_count_ > ring_capacity - 2) { ring_read_ = (ring_read_ + 2) & (ring_capacity - 1); ring_count_ -= 2; }
        ring_[ring_write_] = left; ring_[(ring_write_ + 1) & (ring_capacity - 1)] = right;
        ring_write_ = (ring_write_ + 2) & (ring_capacity - 1); ring_count_ += 2;
    }
    void power_down() noexcept {
        const int square1_length = square1_.length;
        const int square2_length = square2_.length;
        const int wave_length = wave_.length;
        const int noise_length = noise_.length;
        power_on_ = false;
        square1_.reset(); square2_.reset(); wave_.reset(); noise_.reset();
        if (!gb::is_cgb_hardware(hardware_mode_)) {
            square1_.length = square1_length;
            square2_.length = square2_length;
            wave_.length = wave_length;
            noise_.length = noise_length;
        }
        nr50_ = 0; nr51_ = 0; raw_registers_.fill(0);
    }

    static constexpr int state_layout = 1;
    static constexpr int cycles_per_sample = 128;
    static constexpr std::size_t ring_capacity = 8192;
    static constexpr int mix_gain = 64;
    static constexpr double dmg_charge_factor = 0.9946383125332977;
    static constexpr double cgb_charge_factor = 0.8733948326038061;
    inline static constexpr std::array<std::array<int, 8>, 4> duty_table{{
        {{0, 0, 0, 0, 0, 0, 0, 1}}, {{1, 0, 0, 0, 0, 0, 0, 1}},
        {{1, 0, 0, 0, 0, 1, 1, 1}}, {{0, 1, 1, 1, 1, 1, 1, 0}},
    }};
    inline static constexpr std::array<int, 8> divisors{{8, 16, 32, 48, 64, 80, 96, 112}};
    inline static constexpr std::array<int, 48> read_masks{{
        0x80, 0x3f, 0x00, 0xff, 0xbf, 0xff, 0x3f, 0x00, 0xff, 0xbf,
        0x7f, 0xff, 0x9f, 0xff, 0xbf, 0xff, 0xff, 0x00, 0x00, 0xbf,
        0x00, 0x00, 0x70, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    }};

    Square square1_{true};
    Square square2_{false};
    Wave wave_{};
    Noise noise_{};
    gb::HardwareMode hardware_mode_{gb::HardwareMode::dmg};
    bool power_on_{true};
    int nr50_{0x77};
    int nr51_{0xf3};
    std::array<int, 48> raw_registers_ = [] { std::array<int, 48> values{}; values[0x14] = 0x77; values[0x15] = 0xf3; return values; }();
    int frame_step_{};
    int sample_timer_{cycles_per_sample};
    std::array<std::int16_t, ring_capacity> ring_{};
    std::size_t ring_read_{};
    std::size_t ring_write_{};
    std::size_t ring_count_{};
    double capacitor_left_{};
    double capacitor_right_{};

    /**
     * Le mixeur a changé d'état de connexion dans la fenêtre en cours.
     *
     * Transitoire : consommé par le prélèvement suivant. Rien n'en est
     * sérialisé, un état instantané reprenant toujours à une frontière de
     * fenêtre.
     */
    bool mixer_changed_mid_window_{};
};

} // namespace ravenemu::cgb
