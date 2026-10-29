#pragma once

#include "save/save_memory.hpp"

namespace ravenemu::gba {

class Flash final : public SaveMemory {
public:
    explicit Flash(GbaSaveType type) : SaveMemory(type) {
        std::fill(data().begin(), data().end(), 0xffU);
    }
    int read(int address) override {
        const auto offset = static_cast<std::size_t>(address) & 0xffffU;
        if (id_mode_) {
            if (offset == 0) return type() == GbaSaveType::flash_128k ? 0x62 : 0x32;
            if (offset == 1) return type() == GbaSaveType::flash_128k ? 0x13 : 0x1b;
            return 0;
        }
        return data()[static_cast<std::size_t>(bank_) * bank_size + offset];
    }
    void write(int address, int value) override {
        const auto offset = static_cast<std::size_t>(address) & 0xffffU;
        const auto byte = static_cast<std::uint8_t>(value);
        if (write_armed_) {
            data()[static_cast<std::size_t>(bank_) * bank_size + offset] = byte;
            write_armed_ = false;
            written();
            return;
        }
        if (bank_switch_armed_) {
            bank_ = static_cast<int>(byte & 1U);
            bank_switch_armed_ = false;
            return;
        }
        if (command_step_ == 0 && offset == 0x5555U && byte == 0xaaU) {
            command_step_ = 1;
        } else if (command_step_ == 1 && offset == 0x2aaaU && byte == 0x55U) {
            command_step_ = 2;
        } else if (command_step_ == 2 && byte == 0x30U && erase_armed_) {
            command_step_ = 0;
            erase_armed_ = false;
            const auto start = static_cast<std::size_t>(bank_) * bank_size + (offset & 0xf000U);
            std::fill_n(data().begin() + static_cast<std::ptrdiff_t>(start), sector_size, 0xffU);
            written();
        } else if (command_step_ == 2 && offset == 0x5555U) {
            command_step_ = 0;
            switch (byte) {
            case 0x90: id_mode_ = true; break;
            case 0xf0: id_mode_ = false; break;
            case 0x80: erase_armed_ = true; break;
            case 0xa0: write_armed_ = true; break;
            case 0xb0:
                if (type() == GbaSaveType::flash_128k) bank_switch_armed_ = true;
                break;
            case 0x10:
                if (erase_armed_) {
                    std::fill(data().begin(), data().end(), 0xffU);
                    erase_armed_ = false;
                    written();
                }
                break;
            default: break;
            }
        } else {
            command_step_ = 0;
        }
    }

private:
    static constexpr std::size_t bank_size = 64U * 1024U;
    static constexpr std::size_t sector_size = 4096U;
    int command_step_{};
    bool id_mode_{};
    bool erase_armed_{};
    bool write_armed_{};
    bool bank_switch_armed_{};
    int bank_{};
};

} // namespace ravenemu::gba
