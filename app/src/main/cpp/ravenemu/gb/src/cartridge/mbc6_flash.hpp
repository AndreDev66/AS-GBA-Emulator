#pragma once

#include "support/bits.hpp"

namespace ravenemu::cgb {

/**
 * Macronix MX29F008TC relié au MBC6.
 *
 * Le composant ne connaît ni les fenêtres CPU ni les registres de banques :
 * Mbc6 lui transmet l'adresse physique de 20 bits obtenue après décodage. Les
 * opérations d'effacement et de programmation terminent immédiatement, faute de
 * durées publiques suffisamment précises, mais passent bien par le mode de
 * statut observable et respectent toutes les protections documentées.
 */
class Mbc6Flash final {
public:
    static constexpr std::size_t storage_size = 1U << 20U;
    static constexpr std::size_t hidden_size = 256;
    static constexpr std::size_t sector_size = 128U * 1024U;

    Mbc6Flash() {
        storage_.fill(0xff);
        hidden_.fill(0xff);
        program_buffer_.fill(0xff);
    }

    [[nodiscard]] int read(std::size_t address) const noexcept {
        address &= storage_size - 1U;
        switch (mode_) {
        case ReadMode::array:
            return storage_[address];
        case ReadMode::identifier:
            switch (address & 0x03U) {
            case 0: return 0xc2; // fabricant Macronix
            case 1: return 0x81; // MX29F008TC
            case 2: return address < sector_size ? 0xc2 : 0x00;
            default: return 0xff;
            }
        case ReadMode::hidden:
            return hidden_[address & (hidden_size - 1U)];
        case ReadMode::program_main:
        case ReadMode::program_hidden:
        case ReadMode::status:
            return status_value();
        }
        return 0xff;
    }

    /** Renvoie vrai uniquement si une donnée non volatile a changé. */
    [[nodiscard]] bool write(std::size_t address, int value,
                             bool flash_write_enabled) noexcept {
        address &= storage_size - 1U;
        const int data = byte(value);
        if (data == 0xf0) {
            reset_interface();
            return false;
        }

        if (mode_ == ReadMode::program_main || mode_ == ReadMode::program_hidden) {
            return write_program_buffer(address, data, flash_write_enabled);
        }
        if (mode_ == ReadMode::identifier || mode_ == ReadMode::hidden) {
            return false;
        }

        return advance_command(address, data, flash_write_enabled);
    }

    [[nodiscard]] const std::array<std::uint8_t, storage_size>& storage() const noexcept {
        return storage_;
    }
    [[nodiscard]] const std::array<std::uint8_t, hidden_size>& hidden() const noexcept {
        return hidden_;
    }
    [[nodiscard]] bool sector_zero_protected() const noexcept {
        return sector_zero_protected_;
    }

    void import_persistent(std::span<const std::uint8_t> storage,
                           std::span<const std::uint8_t> hidden,
                           bool sector_zero_protected) noexcept {
        if (storage.size() != storage_.size() || hidden.size() != hidden_.size()) return;
        std::copy(storage.begin(), storage.end(), storage_.begin());
        std::copy(hidden.begin(), hidden.end(), hidden_.begin());
        sector_zero_protected_ = sector_zero_protected;
        reset_interface();
    }

    void save_state(BinaryWriter& out) const {
        out.u8(static_cast<std::uint8_t>(mode_));
        out.u8(static_cast<std::uint8_t>(sequence_));
        out.u8(static_cast<std::uint8_t>(pending_));
        out.u8(program_last_offset_ < 0
            ? 0xffU : static_cast<std::uint8_t>(program_last_offset_));
        out.raw(program_buffer_);
        out.u8(sector_zero_protected_ ? 1U : 0U);
        out.raw(storage_);
        out.raw(hidden_);
    }

    void load_state(BinaryReader& in) {
        const int mode = in.u8();
        const int sequence = in.u8();
        const int pending = in.u8();
        const int last_offset = in.u8();
        in.raw(program_buffer_);
        const int protected_value = in.u8();

        if (mode > static_cast<int>(ReadMode::status) ||
            sequence > static_cast<int>(Sequence::expect_final) ||
            pending > static_cast<int>(Pending::hidden_read) ||
            (last_offset != 0xff && last_offset >= static_cast<int>(program_buffer_.size())) ||
            protected_value > 1) {
            throw SaveStateError("État instantané corrompu (flash MBC6)");
        }

        mode_ = static_cast<ReadMode>(mode);
        sequence_ = static_cast<Sequence>(sequence);
        pending_ = static_cast<Pending>(pending);
        program_last_offset_ = last_offset == 0xff ? -1 : last_offset;
        sector_zero_protected_ = protected_value != 0;

        const bool followup_sequence = sequence_ == Sequence::expect_followup_aa ||
            sequence_ == Sequence::expect_followup_55 ||
            sequence_ == Sequence::expect_final;
        const bool program_mode = mode_ == ReadMode::program_main ||
            mode_ == ReadMode::program_hidden;
        const bool special_read_mode = mode_ == ReadMode::identifier ||
            mode_ == ReadMode::hidden;
        const bool buffer_cleared = std::all_of(
            program_buffer_.begin(), program_buffer_.end(),
            [](std::uint8_t value) { return value == 0xff; }
        );
        if (followup_sequence != (pending_ != Pending::none) ||
            ((special_read_mode || program_mode) && sequence_ != Sequence::idle) ||
            (program_mode && program_last_offset_ == -1 && !buffer_cleared) ||
            (!program_mode && (program_last_offset_ != -1 || !buffer_cleared))) {
            throw SaveStateError("État instantané corrompu (séquence flash MBC6)");
        }

        in.raw(storage_);
        in.raw(hidden_);
    }

private:
    enum class ReadMode : std::uint8_t {
        array,
        identifier,
        hidden,
        program_main,
        program_hidden,
        status,
    };

    enum class Sequence : std::uint8_t {
        idle,
        expect_unlock_55,
        expect_command,
        expect_followup_aa,
        expect_followup_55,
        expect_final,
    };

    enum class Pending : std::uint8_t { none, erase, extended, hidden_read };

    static constexpr std::size_t command_address_aa = 0x5555;
    static constexpr std::size_t command_address_55 = 0x2aaa;

    [[nodiscard]] static bool is_command_address_aa(std::size_t address) noexcept {
        return (address & 0x7fffU) == command_address_aa;
    }

    [[nodiscard]] static bool is_command_address_55(std::size_t address) noexcept {
        return (address & 0x7fffU) == command_address_55;
    }

    [[nodiscard]] int status_value() const noexcept {
        return 0x80 | (sector_zero_protected_ ? 0x02 : 0x00);
    }

    void reset_interface() noexcept {
        mode_ = ReadMode::array;
        sequence_ = Sequence::idle;
        pending_ = Pending::none;
        clear_program_buffer();
    }

    void clear_program_buffer() noexcept {
        program_buffer_.fill(0xff);
        program_last_offset_ = -1;
    }

    void restart_sequence(std::size_t address, int data) noexcept {
        pending_ = Pending::none;
        sequence_ = is_command_address_aa(address) && data == 0xaa
            ? Sequence::expect_unlock_55 : Sequence::idle;
    }

    void begin_program(ReadMode mode) noexcept {
        mode_ = mode;
        sequence_ = Sequence::idle;
        pending_ = Pending::none;
        clear_program_buffer();
    }

    [[nodiscard]] bool advance_command(std::size_t address, int data,
                                       bool flash_write_enabled) noexcept {
        switch (sequence_) {
        case Sequence::idle:
            if (is_command_address_aa(address) && data == 0xaa) {
                sequence_ = Sequence::expect_unlock_55;
            }
            return false;
        case Sequence::expect_unlock_55:
            if (is_command_address_55(address) && data == 0x55) {
                sequence_ = Sequence::expect_command;
            } else {
                restart_sequence(address, data);
            }
            return false;
        case Sequence::expect_command:
            if (!is_command_address_aa(address)) {
                restart_sequence(address, data);
                return false;
            }
            switch (data) {
            case 0x80: pending_ = Pending::erase; break;
            case 0x60: pending_ = Pending::extended; break;
            case 0x77: pending_ = Pending::hidden_read; break;
            case 0x90:
                mode_ = ReadMode::identifier;
                sequence_ = Sequence::idle;
                return false;
            case 0xa0:
                begin_program(ReadMode::program_main);
                return false;
            default:
                restart_sequence(address, data);
                return false;
            }
            sequence_ = Sequence::expect_followup_aa;
            return false;
        case Sequence::expect_followup_aa:
            if (is_command_address_aa(address) && data == 0xaa) {
                sequence_ = Sequence::expect_followup_55;
            } else {
                restart_sequence(address, data);
            }
            return false;
        case Sequence::expect_followup_55:
            if (is_command_address_55(address) && data == 0x55) {
                sequence_ = Sequence::expect_final;
            } else {
                restart_sequence(address, data);
            }
            return false;
        case Sequence::expect_final:
            return finish_command(address, data, flash_write_enabled);
        }
        return false;
    }

    [[nodiscard]] bool finish_command(std::size_t address, int data,
                                      bool flash_write_enabled) noexcept {
        const Pending command = pending_;
        sequence_ = Sequence::idle;
        pending_ = Pending::none;

        switch (command) {
        case Pending::erase:
            if (is_command_address_aa(address) && data == 0x10) {
                return erase_chip(flash_write_enabled);
            }
            if (data == 0x30) return erase_sector(address, flash_write_enabled);
            break;
        case Pending::extended:
            switch (data) {
            case 0x04:
                if (is_command_address_aa(address)) {
                    return erase_hidden(flash_write_enabled);
                }
                break;
            case 0xe0:
                if (is_command_address_aa(address)) {
                    if (flash_write_enabled) begin_program(ReadMode::program_hidden);
                    else mode_ = ReadMode::array;
                    return false;
                }
                break;
            case 0x40:
                if (address < sector_size) {
                    return set_sector_zero_protection(false, flash_write_enabled);
                }
                break;
            case 0x20:
                if (address < sector_size) {
                    return set_sector_zero_protection(true, flash_write_enabled);
                }
                break;
            default: break;
            }
            break;
        case Pending::hidden_read:
            if (is_command_address_aa(address) && data == 0x77) {
                mode_ = ReadMode::hidden;
                return false;
            }
            break;
        case Pending::none:
            break;
        }
        restart_sequence(address, data);
        return false;
    }

    [[nodiscard]] bool write_program_buffer(std::size_t address, int data,
                                            bool flash_write_enabled) noexcept {
        const int offset = static_cast<int>(address & (program_buffer_.size() - 1U));
        if (program_last_offset_ != offset) {
            program_buffer_[static_cast<std::size_t>(offset)] =
                static_cast<std::uint8_t>(data);
            program_last_offset_ = offset;
            return false;
        }

        bool changed{};
        if (mode_ == ReadMode::program_main) {
            const std::size_t base = address & ~(program_buffer_.size() - 1U);
            const bool sector_zero = base < sector_size;
            const bool allowed = !sector_zero ||
                (flash_write_enabled && !sector_zero_protected_);
            if (allowed) {
                for (std::size_t index = 0; index < program_buffer_.size(); ++index) {
                    const auto programmed = static_cast<std::uint8_t>(
                        storage_[base + index] & program_buffer_[index]
                    );
                    changed = changed || programmed != storage_[base + index];
                    storage_[base + index] = programmed;
                }
            }
        } else {
            const std::size_t base = address & 0x80U;
            if (flash_write_enabled) {
                for (std::size_t index = 0; index < program_buffer_.size(); ++index) {
                    const auto programmed = static_cast<std::uint8_t>(
                        hidden_[base + index] & program_buffer_[index]
                    );
                    changed = changed || programmed != hidden_[base + index];
                    hidden_[base + index] = programmed;
                }
            }
        }
        mode_ = ReadMode::status;
        clear_program_buffer();
        return changed;
    }

    [[nodiscard]] bool erase_sector(std::size_t address,
                                    bool flash_write_enabled) noexcept {
        const std::size_t base = (address / sector_size) * sector_size;
        const bool sector_zero = base == 0;
        const bool allowed = !sector_zero ||
            (flash_write_enabled && !sector_zero_protected_);
        bool changed{};
        if (allowed) {
            const auto first = storage_.begin() + static_cast<std::ptrdiff_t>(base);
            const auto last = first + static_cast<std::ptrdiff_t>(sector_size);
            changed = std::any_of(first, last,
                                  [](std::uint8_t value) { return value != 0xff; });
            std::fill(first, last, 0xff);
        }
        mode_ = ReadMode::status;
        return changed;
    }

    [[nodiscard]] bool erase_chip(bool flash_write_enabled) noexcept {
        const std::size_t first_erased = flash_write_enabled && !sector_zero_protected_
            ? 0U : sector_size;
        const auto first = storage_.begin() + static_cast<std::ptrdiff_t>(first_erased);
        const bool changed = std::any_of(
            first, storage_.end(), [](std::uint8_t value) { return value != 0xff; }
        );
        std::fill(first, storage_.end(), 0xff);
        mode_ = ReadMode::status;
        return changed;
    }

    [[nodiscard]] bool erase_hidden(bool flash_write_enabled) noexcept {
        if (!flash_write_enabled) {
            mode_ = ReadMode::array;
            return false;
        }
        const bool changed = std::any_of(
            hidden_.begin(), hidden_.end(), [](std::uint8_t value) { return value != 0xff; }
        );
        hidden_.fill(0xff);
        mode_ = ReadMode::status;
        return changed;
    }

    [[nodiscard]] bool set_sector_zero_protection(bool protect,
                                                  bool flash_write_enabled) noexcept {
        if (!flash_write_enabled) {
            mode_ = ReadMode::array;
            return false;
        }
        const bool changed = sector_zero_protected_ != protect;
        sector_zero_protected_ = protect;
        mode_ = ReadMode::status;
        return changed;
    }

    std::array<std::uint8_t, storage_size> storage_{};
    std::array<std::uint8_t, hidden_size> hidden_{};
    std::array<std::uint8_t, 128> program_buffer_{};
    ReadMode mode_{ReadMode::array};
    Sequence sequence_{Sequence::idle};
    Pending pending_{Pending::none};
    int program_last_offset_{-1};
    bool sector_zero_protected_{};
};

} // namespace ravenemu::cgb
