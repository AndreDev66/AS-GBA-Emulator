#pragma once

#include "save/save_memory.hpp"

namespace ravenemu::gba {

class Eeprom final : public SaveMemory {
public:
    explicit Eeprom(GbaSaveType type)
        : SaveMemory(type), address_bits_(type == GbaSaveType::eeprom_8k ? 14 : 6) {}

    void hint_transfer_length(int words) noexcept override {
        if (words == 9 || words == 73) address_bits_ = 6;
        if (words == 17 || words == 81) address_bits_ = 14;
    }
    int read(int) override {
        if (state_ != State::reading) return 1;
        ++read_bits_sent_;
        if (read_bits_sent_ <= 4) return 0;
        const auto bit_index = 64 - (read_bits_sent_ - 4);
        const auto bit = static_cast<int>((read_shift_ >> bit_index) & 1U);
        if (read_bits_sent_ - 4 >= 64) {
            state_ = State::idle;
            read_bits_sent_ = 0;
        }
        return bit;
    }
    void write(int, int value) override {
        const auto bit = static_cast<std::uint64_t>(value & 1);
        switch (state_) {
        case State::idle:
            bit_buffer_ = bit;
            bit_count_ = 1;
            state_ = State::command;
            break;
        case State::command:
            bit_buffer_ = (bit_buffer_ << 1U) | bit;
            if (++bit_count_ == 2 + address_bits_) begin_transfer();
            break;
        case State::writing:
            bit_buffer_ = (bit_buffer_ << 1U) | bit;
            if (++bit_count_ == 64) {
                commit_write();
                state_ = State::write_stop;
            }
            break;
        case State::write_stop: state_ = State::idle; break;
        case State::reading: break;
        }
    }

private:
    enum class State { idle, command, writing, write_stop, reading };
    void begin_transfer() {
        const auto command = static_cast<int>((bit_buffer_ >> address_bits_) & 3U);
        address_ = static_cast<int>(bit_buffer_ & ((1ULL << address_bits_) - 1ULL)) * 8;
        if (command == 3) {
            const auto base = static_cast<std::size_t>(address_) & (data().size() - 1U);
            read_shift_ = 0;
            for (std::size_t i = 0; i < 8; ++i) read_shift_ = (read_shift_ << 8U) | data()[base + i];
            read_bits_sent_ = 0;
            state_ = State::reading;
        } else {
            bit_buffer_ = 0;
            bit_count_ = 0;
            state_ = State::writing;
        }
    }
    void commit_write() {
        const auto base = static_cast<std::size_t>(address_) & (data().size() - 1U);
        for (std::size_t i = 0; i < 8; ++i) {
            data()[base + i] = static_cast<std::uint8_t>(bit_buffer_ >> ((7U - i) * 8U));
        }
        written();
    }

    int address_bits_;
    State state_{State::idle};
    std::uint64_t bit_buffer_{};
    int bit_count_{};
    int address_{};
    std::uint64_t read_shift_{};
    int read_bits_sent_{};
};

} // namespace ravenemu::gba
