#pragma once

#include "cartridge/cartridge.hpp"

namespace ravenemu::cgb {

class Mbc2 final : public Cartridge {
public:
    using Cartridge::Cartridge;
    int read_rom(int address) const override {
        const int bank = address < rom_bank_size ? 0 : normalize_rom_bank(rom_bank_);
        const auto offset = static_cast<std::size_t>(bank * rom_bank_size + (address & (rom_bank_size - 1)));
        return offset < rom_->size() ? (*rom_)[offset] : 0xff;
    }
    void write_control(int address, int value) override {
        if (address > 0x3fff) return;
        if ((address & 0x100) == 0) ram_enabled_ = (value & 0x0f) == 0x0a;
        else { const int v = value & 0x0f; rom_bank_ = v == 0 ? 1 : v; }
    }
    int read_ram(int address) const override {
        if (!ram_enabled_) return 0xff;
        return 0xf0 | (ram_[static_cast<std::size_t>((address - 0xa000) & 0x1ff)] & 0x0f);
    }
    void write_ram(int address, int value) override {
        if (!ram_enabled_) return;
        ram_[static_cast<std::size_t>((address - 0xa000) & 0x1ff)] = static_cast<std::uint8_t>(value & 0x0f);
        mark_written();
    }
    void save_state(BinaryWriter& out) const override {
        out.boolean(ram_enabled_); out.i32(rom_bank_); out.raw(ram_);
    }
    void load_state(BinaryReader& in) override {
        ram_enabled_ = in.boolean(); rom_bank_ = in.i32(); in.raw(ram_);
    }
private:
    bool ram_enabled_{};
    int rom_bank_{1};
};

} // namespace ravenemu::cgb
