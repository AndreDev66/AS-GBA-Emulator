#pragma once

#include "save/save_memory.hpp"

namespace ravenemu::gba {

class Sram final : public SaveMemory {
public:
    Sram() : SaveMemory(GbaSaveType::sram) {}
    int read(int address) override {
        return data()[static_cast<std::size_t>(address) & (data().size() - 1U)];
    }
    void write(int address, int value) override {
        data()[static_cast<std::size_t>(address) & (data().size() - 1U)] =
            static_cast<std::uint8_t>(value);
        written();
    }
};

} // namespace ravenemu::gba
