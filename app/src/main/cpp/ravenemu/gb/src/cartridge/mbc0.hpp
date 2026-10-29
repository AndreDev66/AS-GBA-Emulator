#pragma once

#include "cartridge/cartridge.hpp"

namespace ravenemu::cgb {

class Mbc0 final : public Cartridge {
public:
    using Cartridge::Cartridge;
    int read_rom(int address) const override {
        return static_cast<std::size_t>(address) < rom_->size()
            ? (*rom_)[static_cast<std::size_t>(address)] : 0xff;
    }
    void write_control(int, int) override {}
    int read_ram(int address) const override {
        const auto offset = static_cast<std::size_t>(address - 0xa000);
        return offset < ram_.size() ? ram_[offset] : 0xff;
    }
    void write_ram(int address, int value) override {
        const auto offset = static_cast<std::size_t>(address - 0xa000);
        if (offset < ram_.size()) { ram_[offset] = static_cast<std::uint8_t>(value); mark_written(); }
    }
    void save_state(BinaryWriter& out) const override { out.raw(ram_); }
    void load_state(BinaryReader& in) override { in.raw(ram_); }
};

} // namespace ravenemu::cgb
