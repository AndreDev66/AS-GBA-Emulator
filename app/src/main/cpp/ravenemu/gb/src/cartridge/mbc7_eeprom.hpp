#pragma once

#include "support/bits.hpp"

namespace ravenemu::cgb {

/** EEPROM 93LC56 câblée en organisation 128 mots de 16 bits. */
class Mbc7Eeprom final {
public:
    static constexpr std::size_t storage_size = 256;
    static constexpr int write_busy_dots = 20'972; // 5 ms à 4,194304 MHz

    Mbc7Eeprom() { storage_.fill(0xff); }

    [[nodiscard]] int pins() const noexcept {
        return (cs_ ? 0x80 : 0) | (clock_ ? 0x40 : 0) |
            (data_in_ ? 0x02 : 0) | (data_out_ ? 0x01 : 0);
    }

    [[nodiscard]] bool write_pins(int value) noexcept {
        const bool next_cs = (value & 0x80) != 0;
        const bool next_clock = (value & 0x40) != 0;
        data_in_ = (value & 0x02) != 0;
        bool changed{};
        if (!next_cs) {
            reset_transaction();
        } else {
            if (!cs_) begin_transaction();
            if (!clock_ && next_clock) changed = rising_edge();
        }
        cs_ = next_cs;
        clock_ = next_clock;
        return changed;
    }

    void tick(int dots) noexcept {
        if (busy_dots_ <= 0) return;
        busy_dots_ = std::max(0, busy_dots_ - dots);
        if (busy_dots_ == 0) {
            if (cs_) data_out_ = true;
            else phase_ = Phase::await_start;
        }
    }

    [[nodiscard]] const std::array<std::uint8_t, storage_size>& storage() const noexcept {
        return storage_;
    }

    void import(std::span<const std::uint8_t> data) noexcept {
        if (data.size() != storage_.size()) return;
        std::copy(data.begin(), data.end(), storage_.begin());
        reset_interface();
    }

    void save_state(BinaryWriter& out) const {
        out.raw(storage_);
        out.u8(write_enabled_ ? 1U : 0U);
        out.u8(cs_ ? 1U : 0U); out.u8(clock_ ? 1U : 0U);
        out.u8(data_in_ ? 1U : 0U); out.u8(data_out_ ? 1U : 0U);
        out.u8(static_cast<std::uint8_t>(phase_));
        out.u8(static_cast<std::uint8_t>(pending_));
        out.u32(command_); out.i32(command_bits_);
        out.u32(data_shift_); out.i32(data_bits_); out.i32(output_bits_);
        out.i32(target_address_); out.i32(busy_dots_);
    }

    void load_state(BinaryReader& in) {
        in.raw(storage_);
        write_enabled_ = read_bool(in); cs_ = read_bool(in); clock_ = read_bool(in);
        data_in_ = read_bool(in); data_out_ = read_bool(in);
        const int phase = in.u8(); const int pending = in.u8();
        command_ = in.u32(); command_bits_ = in.i32();
        data_shift_ = in.u32(); data_bits_ = in.i32(); output_bits_ = in.i32();
        target_address_ = in.i32(); busy_dots_ = in.i32();
        if (phase > static_cast<int>(Phase::busy) ||
            pending > static_cast<int>(Pending::write_all) || command_bits_ < 0 ||
            command_bits_ > 11 || data_bits_ < 0 || data_bits_ > 16 ||
            output_bits_ < 0 || output_bits_ > 16 || target_address_ < 0 ||
            target_address_ > 0x7f || busy_dots_ < 0 || busy_dots_ > write_busy_dots) {
            throw SaveStateError("État instantané corrompu (EEPROM MBC7)");
        }
        phase_ = static_cast<Phase>(phase);
        pending_ = static_cast<Pending>(pending);
        if ((phase_ == Phase::input_data) != (pending_ != Pending::none) ||
            (phase_ == Phase::busy && busy_dots_ == 0 && !cs_) ||
            (phase_ != Phase::busy && busy_dots_ != 0)) {
            throw SaveStateError("État instantané corrompu (séquence EEPROM MBC7)");
        }
    }

private:
    enum class Phase : std::uint8_t { await_start, command, input_data, output_data, busy };
    enum class Pending : std::uint8_t { none, write, write_all };

    static bool read_bool(BinaryReader& in) {
        const int value = in.u8();
        if (value > 1) throw SaveStateError("État instantané corrompu (broche EEPROM MBC7)");
        return value != 0;
    }

    void reset_interface() noexcept {
        write_enabled_ = false;
        cs_ = clock_ = data_in_ = false;
        busy_dots_ = 0;
        reset_transaction();
    }

    void begin_transaction() noexcept {
        command_ = 0; command_bits_ = 0; data_shift_ = 0; data_bits_ = 0;
        output_bits_ = 0; pending_ = Pending::none;
        phase_ = busy_dots_ > 0 ? Phase::busy : Phase::await_start;
        data_out_ = busy_dots_ == 0;
    }

    void reset_transaction() noexcept {
        command_ = 0; command_bits_ = 0; data_shift_ = 0; data_bits_ = 0;
        output_bits_ = 0; pending_ = Pending::none;
        phase_ = busy_dots_ > 0 ? Phase::busy : Phase::await_start;
        data_out_ = true;
    }

    [[nodiscard]] bool rising_edge() noexcept {
        if (phase_ == Phase::busy) {
            data_out_ = busy_dots_ == 0;
            return false;
        }
        if (phase_ == Phase::output_data) {
            if (output_bits_ == 0) {
                target_address_ = (target_address_ + 1) & 0x7f;
                load_output_word(target_address_);
            }
            data_out_ = ((data_shift_ >> (--output_bits_)) & 1U) != 0;
            return false;
        }
        if (phase_ == Phase::input_data) {
            data_shift_ = (data_shift_ << 1U) | (data_in_ ? 1U : 0U);
            if (++data_bits_ == 16) return finish_data();
            return false;
        }
        if (phase_ == Phase::await_start) {
            if (!data_in_) return false;
            command_ = 1; command_bits_ = 1; phase_ = Phase::command;
            return false;
        }
        command_ = (command_ << 1U) | (data_in_ ? 1U : 0U);
        if (++command_bits_ == 11) return decode_command();
        return false;
    }

    [[nodiscard]] bool decode_command() noexcept {
        const int opcode = static_cast<int>((command_ >> 8U) & 0x03U);
        const int address = static_cast<int>(command_ & 0x7fU);
        target_address_ = address;
        if (opcode == 2) { // READ
            load_output_word(address);
            phase_ = Phase::output_data; data_out_ = false;
            return false;
        }
        if (opcode == 1) { // WRITE
            pending_ = Pending::write; data_shift_ = 0; data_bits_ = 0;
            phase_ = Phase::input_data; return false;
        }
        if (opcode == 3) return erase_word(address);

        switch ((command_ >> 6U) & 0x03U) {
        case 0: write_enabled_ = false; break; // EWDS
        case 1: pending_ = Pending::write_all; data_shift_ = 0; data_bits_ = 0;
                phase_ = Phase::input_data; return false; // WRAL
        case 2: return erase_all(); // ERAL
        case 3: write_enabled_ = true; break; // EWEN
        }
        phase_ = Phase::await_start; data_out_ = true;
        return false;
    }

    [[nodiscard]] bool finish_data() noexcept {
        bool changed{};
        if (write_enabled_) {
            const auto high = static_cast<std::uint8_t>(data_shift_ >> 8U);
            const auto low = static_cast<std::uint8_t>(data_shift_);
            if (pending_ == Pending::write) {
                const auto offset = static_cast<std::size_t>(target_address_ * 2);
                changed = storage_[offset] != high || storage_[offset + 1U] != low;
                storage_[offset] = high; storage_[offset + 1U] = low;
            } else {
                for (std::size_t offset = 0; offset < storage_.size(); offset += 2) {
                    changed = changed || storage_[offset] != high || storage_[offset + 1] != low;
                    storage_[offset] = high; storage_[offset + 1] = low;
                }
            }
            begin_busy();
        } else {
            pending_ = Pending::none; phase_ = Phase::await_start; data_out_ = true;
        }
        return changed;
    }

    [[nodiscard]] bool erase_word(int address) noexcept {
        bool changed{};
        if (write_enabled_) {
            const auto offset = static_cast<std::size_t>(address * 2);
            changed = storage_[offset] != 0xff || storage_[offset + 1] != 0xff;
            storage_[offset] = storage_[offset + 1] = 0xff; begin_busy();
        } else { phase_ = Phase::await_start; data_out_ = true; }
        return changed;
    }

    [[nodiscard]] bool erase_all() noexcept {
        bool changed{};
        if (write_enabled_) {
            changed = std::any_of(storage_.begin(), storage_.end(),
                                  [](std::uint8_t value) { return value != 0xff; });
            storage_.fill(0xff); begin_busy();
        } else { phase_ = Phase::await_start; data_out_ = true; }
        return changed;
    }

    void begin_busy() noexcept {
        pending_ = Pending::none; busy_dots_ = write_busy_dots;
        phase_ = Phase::busy; data_out_ = false;
    }

    void load_output_word(int address) noexcept {
        const std::size_t offset = static_cast<std::size_t>(address * 2);
        data_shift_ = (static_cast<std::uint32_t>(storage_[offset]) << 8U) |
            storage_[offset + 1U];
        output_bits_ = 16;
    }

    std::array<std::uint8_t, storage_size> storage_{};
    bool write_enabled_{}; bool cs_{}; bool clock_{}; bool data_in_{};
    bool data_out_{true}; Phase phase_{Phase::await_start}; Pending pending_{Pending::none};
    std::uint32_t command_{}; int command_bits_{}; std::uint32_t data_shift_{};
    int data_bits_{}; int output_bits_{}; int target_address_{}; int busy_dots_{};
};

} // namespace ravenemu::cgb
