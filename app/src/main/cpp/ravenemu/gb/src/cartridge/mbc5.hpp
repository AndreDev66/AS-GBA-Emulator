#pragma once

#include "cartridge/cartridge.hpp"

namespace ravenemu::cgb {

class Mbc5 final : public Cartridge {
public:
    Mbc5(RomImage rom, CartridgeHeader header)
        : Cartridge(std::move(rom), header), has_rumble_(header.cartridge_type >= 0x1c && header.cartridge_type <= 0x1e) {}
    int read_rom(int address) const override {
        const int bank = address < rom_bank_size ? 0 : normalize_rom_bank((rom_bank_high_ << 8) | rom_bank_low_);
        const auto offset = static_cast<std::size_t>(bank * rom_bank_size + (address & (rom_bank_size - 1)));
        return offset < rom_->size() ? (*rom_)[offset] : 0xff;
    }
    void write_control(int address, int value) override {
        if (address <= 0x1fff) ram_enabled_ = (value & 0x0f) == 0x0a;
        else if (address <= 0x2fff) rom_bank_low_ = byte(value);
        else if (address <= 0x3fff) rom_bank_high_ = value & 1;
        else if (address <= 0x5fff) {
            if (has_rumble_) {
                rumble_active_ = (value & 0x08) != 0;
                ram_bank_ = value & 0x07;
            } else {
                ram_bank_ = value & 0x0f;
            }
        }
    }
    [[nodiscard]] bool rumble_active() const noexcept override { return has_rumble_ && rumble_active_; }
    int read_ram(int address) const override {
        if (!ram_enabled_ || ram_.empty()) return 0xff;
        const auto offset = static_cast<std::size_t>(ram_bank_ * ram_bank_size + address - 0xa000);
        return offset < ram_.size() ? ram_[offset] : 0xff;
    }
    void write_ram(int address, int value) override {
        if (!ram_enabled_ || ram_.empty()) return;
        const auto offset = static_cast<std::size_t>(ram_bank_ * ram_bank_size + address - 0xa000);
        if (offset < ram_.size()) { ram_[offset] = static_cast<std::uint8_t>(value); mark_written(); }
    }
    void save_state(BinaryWriter& out) const override {
        out.boolean(ram_enabled_); out.i32(rom_bank_low_); out.i32(rom_bank_high_);
        out.i32(ram_bank_); out.boolean(rumble_active_); out.raw(ram_);
    }
    void load_state(BinaryReader& in) override {
        ram_enabled_ = in.boolean(); rom_bank_low_ = in.i32(); rom_bank_high_ = in.i32();
        ram_bank_ = in.i32(); rumble_active_ = in.boolean(); in.raw(ram_);
    }
private:
    bool ram_enabled_{};
    int rom_bank_low_{1};
    int rom_bank_high_{};
    int ram_bank_{};
    bool has_rumble_{};
    bool rumble_active_{};
};

} // namespace ravenemu::cgb
