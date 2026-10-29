#pragma once

#include "support/bits.hpp"

namespace ravenemu::cgb {

/**
 * Protocole observable du microcontrôleur 4 bits HuC3.
 *
 * Le mapper ne fait que sélectionner un registre miroir dans $A000-$BFFF.
 * Cette classe porte la boîte aux lettres B/C, le sémaphore D, l'index sur
 * 256 nibbles et l'horloge autonome minute/jour. Les fonctions sonores et les
 * alarmes internes, dont le comportement public reste incomplet, ne sont pas
 * synthétisées ici.
 */
class Huc3Mcu final {
public:
    using Clock = std::function<std::int64_t()>;

    static constexpr int command_busy_dots = 4;
    static constexpr std::size_t memory_nibbles = 256;
    static constexpr std::size_t packed_memory_size = memory_nibbles / 2;
    static constexpr std::size_t battery_footer_size = 4 + 1 + packed_memory_size + 1 + 1 + 8;

    explicit Huc3Mcu(Clock clock) : clock_(std::move(clock)) {
        reset_persistent_state();
    }

    [[nodiscard]] int read_mailbox() const noexcept {
        return 0x80 | mailbox_;
    }

    [[nodiscard]] int read_response() const noexcept {
        return 0x80 | (mailbox_ & 0x70) | response_;
    }

    [[nodiscard]] int read_semaphore() const noexcept {
        // D partage les lignes 2-6 de la boîte B ; D1 est lu à un et D0
        // expose l'état prêt/occupé. D7 n'est pas câblé et est normalisé haut.
        return 0x80 | (mailbox_ & 0x7c) | 0x02 | (busy_dots_ == 0 ? 1 : 0);
    }

    [[nodiscard]] int read_infrared(bool light_detected) const noexcept {
        // E partage les lignes 2-6 de C, force E1 à zéro et remplace E0 par
        // la sortie du phototransistor.
        return 0x80 | (read_response() & 0x7c) | (light_detected ? 1 : 0);
    }

    void write_mailbox(int value) noexcept { mailbox_ = byte(value) & 0x7f; }

    void write_semaphore(int value) noexcept {
        // Écrire un dans D0 ne modifie pas le sémaphore. Un front demandé
        // pendant que le MCU est occupé est ignoré, comme une boîte unique.
        if ((byte(value) & 1) != 0 || busy_dots_ != 0) return;
        pending_mailbox_ = mailbox_;
        busy_dots_ = command_busy_dots;
    }

    /** Avance le MCU ; retourne vrai si un état alimenté par la pile a été écrit. */
    [[nodiscard]] bool tick(int dots) {
        if (dots <= 0 || busy_dots_ == 0) return false;
        busy_dots_ = std::max(0, busy_dots_ - dots);
        if (busy_dots_ != 0) return false;
        return execute(pending_mailbox_);
    }

    /** Pied de page RavenEmu placé après les 32 Kio de SRAM bruts. */
    [[nodiscard]] std::vector<std::uint8_t> export_battery_footer() {
        sync_clock();
        std::vector<std::uint8_t> output(battery_footer_size);
        output[0] = 'R'; output[1] = 'V'; output[2] = 'H'; output[3] = '3';
        output[version_offset] = battery_version;
        for (std::size_t i = 0; i < packed_memory_size; ++i) {
            output[memory_offset + i] = static_cast<std::uint8_t>(
                memory_[i * 2] | (memory_[i * 2 + 1] << 4U)
            );
        }
        output[seconds_offset] = static_cast<std::uint8_t>(second_remainder_);
        output[index_offset] = static_cast<std::uint8_t>(index_);
        auto timestamp = std::bit_cast<std::uint64_t>(last_sync_epoch_);
        for (unsigned i = 0; i < 8; ++i) {
            output[timestamp_offset + i] = static_cast<std::uint8_t>(timestamp);
            timestamp >>= 8U;
        }
        return output;
    }

    /** Valide entièrement le pied de page avant de modifier le MCU. */
    [[nodiscard]] bool import_battery_footer(std::span<const std::uint8_t> data) {
        if (data.size() != battery_footer_size || data[0] != 'R' || data[1] != 'V' ||
            data[2] != 'H' || data[3] != '3' || data[version_offset] != battery_version) {
            return false;
        }

        std::array<std::uint8_t, memory_nibbles> loaded_memory{};
        for (std::size_t i = 0; i < packed_memory_size; ++i) {
            loaded_memory[i * 2] = data[memory_offset + i] & 0x0f;
            loaded_memory[i * 2 + 1] = data[memory_offset + i] >> 4U;
        }
        const int loaded_seconds = data[seconds_offset];
        const int loaded_minutes = decode_12(loaded_memory, current_minute_base);
        if (loaded_seconds >= 60 || loaded_minutes >= minutes_per_day) return false;

        std::uint64_t timestamp{};
        for (unsigned i = 0; i < 8; ++i) {
            timestamp |= static_cast<std::uint64_t>(data[timestamp_offset + i]) << (i * 8U);
        }

        memory_ = loaded_memory;
        minute_of_day_ = loaded_minutes;
        day_counter_ = decode_12(memory_, current_day_base);
        second_remainder_ = loaded_seconds;
        index_ = data[index_offset];
        last_sync_epoch_ = std::bit_cast<std::int64_t>(timestamp);
        sync_clock();
        return true;
    }

    /** Import d'une ancienne sauvegarde limitée à la SRAM, sans état RTC. */
    void reset_persistent_state() {
        memory_.fill(0);
        minute_of_day_ = 0;
        day_counter_ = 0;
        second_remainder_ = 0;
        index_ = 0;
        last_sync_epoch_ = now();
        refresh_current_time();
    }

    void save_state(BinaryWriter& out) const {
        out.u8(static_cast<std::uint8_t>(mailbox_));
        out.u8(static_cast<std::uint8_t>(response_));
        out.u8(static_cast<std::uint8_t>(index_));
        out.u8(static_cast<std::uint8_t>(pending_mailbox_));
        out.i32(busy_dots_);
        out.u8(tone_command_armed_ ? 1U : 0U);
        out.u8(static_cast<std::uint8_t>(second_remainder_));
        out.i64(last_sync_epoch_);
        out.raw(memory_);
    }

    void load_state(BinaryReader& in) {
        mailbox_ = in.u8();
        response_ = in.u8();
        index_ = in.u8();
        pending_mailbox_ = in.u8();
        busy_dots_ = in.i32();
        tone_command_armed_ = read_bool(in);
        second_remainder_ = in.u8();
        last_sync_epoch_ = in.i64();
        in.raw(memory_);

        minute_of_day_ = decode_12(memory_, current_minute_base);
        day_counter_ = decode_12(memory_, current_day_base);
        if (mailbox_ > 0x7f || response_ > 0x0f || pending_mailbox_ > 0x7f ||
            busy_dots_ < 0 || busy_dots_ > command_busy_dots || second_remainder_ >= 60 ||
            minute_of_day_ >= minutes_per_day ||
            std::any_of(memory_.begin(), memory_.end(), [](std::uint8_t value) {
                return value > 0x0f;
            })) {
            throw SaveStateError("État instantané corrompu (MCU HuC3)");
        }
    }

private:
    static constexpr std::uint8_t battery_version = 1;
    static constexpr std::size_t version_offset = 4;
    static constexpr std::size_t memory_offset = version_offset + 1;
    static constexpr std::size_t seconds_offset = memory_offset + packed_memory_size;
    static constexpr std::size_t index_offset = seconds_offset + 1;
    static constexpr std::size_t timestamp_offset = index_offset + 1;
    static_assert(timestamp_offset + 8 == battery_footer_size);
    static constexpr int current_minute_base = 0x10;
    static constexpr int current_day_base = 0x13;
    static constexpr int minutes_per_day = 1'440;
    static constexpr int day_modulus = 4'096;
    static constexpr std::uint64_t minute_cycle =
        static_cast<std::uint64_t>(minutes_per_day) * day_modulus;

    [[nodiscard]] std::int64_t now() const {
        return clock_ ? clock_() : 0;
    }

    static bool read_bool(BinaryReader& in) {
        const int value = in.u8();
        if (value > 1) throw SaveStateError("État instantané corrompu (booléen HuC3)");
        return value != 0;
    }

    static int decode_12(const std::array<std::uint8_t, memory_nibbles>& memory,
                         int base) noexcept {
        return memory[static_cast<std::size_t>(base)] |
            (memory[static_cast<std::size_t>(base + 1)] << 4) |
            (memory[static_cast<std::size_t>(base + 2)] << 8);
    }

    void encode_12(int base, int value) noexcept {
        for (int nibble = 0; nibble < 3; ++nibble) {
            memory_[static_cast<std::size_t>(base + nibble)] =
                static_cast<std::uint8_t>((value >> (nibble * 4)) & 0x0f);
        }
    }

    void refresh_current_time() noexcept {
        encode_12(current_minute_base, minute_of_day_);
        encode_12(current_day_base, day_counter_);
    }

    void advance_minutes(std::uint64_t delta) noexcept {
        if (delta == 0) return;
        const auto current = static_cast<std::uint64_t>(day_counter_) * minutes_per_day +
            static_cast<std::uint64_t>(minute_of_day_);
        const auto wrapped = (current + (delta % minute_cycle)) % minute_cycle;
        day_counter_ = static_cast<int>(wrapped / minutes_per_day);
        minute_of_day_ = static_cast<int>(wrapped % minutes_per_day);
        refresh_current_time();
    }

    void sync_clock() {
        const auto current_epoch = now();
        // Un recul de l'horloge hôte ne doit jamais faire avancer deux fois
        // la cartouche lorsque l'heure murale rattrape ensuite sa valeur.
        if (current_epoch <= last_sync_epoch_) return;
        const auto elapsed = static_cast<std::uint64_t>(current_epoch) -
            static_cast<std::uint64_t>(last_sync_epoch_);
        last_sync_epoch_ = current_epoch;

        const auto accumulated_seconds =
            static_cast<std::uint64_t>(second_remainder_) + (elapsed % 60);
        const auto elapsed_minutes = elapsed / 60 + accumulated_seconds / 60;
        second_remainder_ = static_cast<int>(accumulated_seconds % 60);
        advance_minutes(elapsed_minutes);
    }

    [[nodiscard]] int read_memory(int address) {
        if (address >= current_minute_base && address <= current_day_base + 2) {
            sync_clock();
        }
        return memory_[static_cast<std::size_t>(address)];
    }

    [[nodiscard]] bool write_memory(int address, int value) noexcept {
        // Les nibbles $08-$1F sont produits par le MCU et sont en lecture
        // seule ; $00-$07 et $20-$FF constituent la fenêtre de travail.
        if (address >= 0x08 && address <= 0x1f) return false;
        memory_[static_cast<std::size_t>(address)] = static_cast<std::uint8_t>(value & 0x0f);
        return true;
    }

    [[nodiscard]] bool execute_extended(int argument) {
        switch (argument) {
        case 0x0:
            sync_clock();
            for (int i = 0; i < 6; ++i) {
                memory_[static_cast<std::size_t>(i)] =
                    memory_[static_cast<std::size_t>(current_minute_base + i)];
            }
            return true;
        case 0x1:
            if (memory_[6] != 1 || memory_[7] != 0) return false;
            sync_clock();
            minute_of_day_ = decode_12(memory_, 0) % minutes_per_day;
            day_counter_ = decode_12(memory_, 3) & (day_modulus - 1);
            second_remainder_ = 0;
            last_sync_epoch_ = now();
            memory_[6] = 0;
            refresh_current_time();
            return true;
        case 0x2:
            // Réponse de présence utilisée par les logiciels HuC3 connus.
            response_ = 1;
            return false;
        case 0xe:
            // Deux commandes $6E consécutives déclenchent le générateur de
            // tonalité réel. L'armement est conservé, mais aucune onde n'est
            // inventée tant que le circuit sonore n'est pas caractérisé.
            tone_command_armed_ = !tone_command_armed_;
            return false;
        default:
            return false;
        }
    }

    [[nodiscard]] bool execute(int mailbox) {
        const int command = (mailbox >> 4) & 0x07;
        const int argument = mailbox & 0x0f;
        if (command != 6 || argument != 0x0e) tone_command_armed_ = false;

        switch (command) {
        case 0:
            response_ = read_memory(index_);
            return false;
        case 1:
            response_ = read_memory(index_);
            index_ = byte(index_ + 1);
            return true;
        case 2:
            return write_memory(index_, argument);
        case 3: {
            static_cast<void>(write_memory(index_, argument));
            index_ = byte(index_ + 1);
            return true; // l'index alimenté par la pile a changé
        }
        case 4:
            index_ = (index_ & 0xf0) | argument;
            return true;
        case 5:
            index_ = (index_ & 0x0f) | (argument << 4);
            return true;
        case 6:
            return execute_extended(argument);
        default:
            return false;
        }
    }

    Clock clock_;
    std::array<std::uint8_t, memory_nibbles> memory_{};
    int mailbox_{0x7f};
    int response_{};
    int index_{};
    int pending_mailbox_{0x7f};
    int busy_dots_{};
    bool tone_command_armed_{};
    int minute_of_day_{};
    int day_counter_{};
    int second_remainder_{};
    std::int64_t last_sync_epoch_{};
};

} // namespace ravenemu::cgb
