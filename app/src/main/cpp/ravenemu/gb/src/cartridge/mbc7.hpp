#pragma once

#include "cartridge/cartridge.hpp"
#include "cartridge/mbc7_eeprom.hpp"

namespace ravenemu::cgb {

class Mbc7 final : public Cartridge {
public:
    static constexpr std::size_t eeprom_size = Mbc7Eeprom::storage_size;

    Mbc7(RomImage rom, CartridgeHeader header)
        : Cartridge(std::move(rom), header) {}

    int read_rom(int address) const override {
        const int bank = address < rom_bank_size ? 0 : normalize_rom_bank(rom_bank_);
        const auto offset = static_cast<std::size_t>(bank * rom_bank_size +
                                                     (address & (rom_bank_size - 1)));
        return offset < rom_->size() ? (*rom_)[offset] : 0xff;
    }

    void write_control(int address, int value) override {
        if (address <= 0x1fff) enabled_1_ = (value & 0x0f) == 0x0a;
        else if (address <= 0x3fff) rom_bank_ = value & 0x7f;
        else if (address <= 0x5fff) enabled_2_ = byte(value) == 0x40;
    }

    int read_ram(int address) const override {
        if (!registers_enabled() || address >= 0xb000) return 0xff;
        switch ((address >> 4) & 0x0f) {
        case 0x0: case 0x1: return 0xff;
        case 0x2: return latched_x_ & 0xff;
        case 0x3: return (latched_x_ >> 8) & 0xff;
        case 0x4: return latched_y_ & 0xff;
        case 0x5: return (latched_y_ >> 8) & 0xff;
        case 0x6: return 0x00;
        case 0x7: return 0xff;
        case 0x8: return eeprom_.pins();
        default: return 0xff;
        }
    }

    void write_ram(int address, int value) override {
        if (!registers_enabled() || address >= 0xb000) return;
        switch ((address >> 4) & 0x0f) {
        case 0x0:
            if (byte(value) == 0x55) {
                latch_armed_ = true; latched_x_ = latched_y_ = 0x8000;
            }
            break;
        case 0x1:
            if (byte(value) == 0xaa && latch_armed_) {
                latched_x_ = clamp_sample(acceleration_x_);
                latched_y_ = clamp_sample(acceleration_y_);
                latch_armed_ = false;
            }
            break;
        case 0x8:
            if (eeprom_.write_pins(value)) mark_written();
            break;
        default: break;
        }
    }

    void tick(int dots) override { eeprom_.tick(dots); }
    void set_acceleration(int x, int y) noexcept override {
        acceleration_x_ = std::clamp(x, -0x8000, 0x7fff);
        acceleration_y_ = std::clamp(y, -0x8000, 0x7fff);
    }

    std::optional<std::vector<std::uint8_t>> export_battery() override {
        return std::vector<std::uint8_t>(eeprom_.storage().begin(), eeprom_.storage().end());
    }

    void import_battery(std::span<const std::uint8_t> data) override {
        if (data.size() != eeprom_size) return;
        eeprom_.import(data); mark_clean();
    }

    void save_state(BinaryWriter& out) const override {
        out.i32(state_layout); out.u8(enabled_1_ ? 1U : 0U);
        out.u8(enabled_2_ ? 1U : 0U); out.u8(static_cast<std::uint8_t>(rom_bank_));
        out.u8(latch_armed_ ? 1U : 0U); out.i32(latched_x_); out.i32(latched_y_);
        out.i32(acceleration_x_); out.i32(acceleration_y_);
        eeprom_.save_state(out);
    }

    void load_state(BinaryReader& in) override {
        if (in.i32() != state_layout) throw SaveStateError("État MBC7 incompatible");
        enabled_1_ = read_bool(in); enabled_2_ = read_bool(in); rom_bank_ = in.u8();
        latch_armed_ = read_bool(in); latched_x_ = in.i32(); latched_y_ = in.i32();
        acceleration_x_ = in.i32(); acceleration_y_ = in.i32();
        if (rom_bank_ > 0x7f || latched_x_ < 0 || latched_x_ > 0xffff ||
            latched_y_ < 0 || latched_y_ > 0xffff || acceleration_x_ < -0x8000 ||
            acceleration_x_ > 0x7fff || acceleration_y_ < -0x8000 ||
            acceleration_y_ > 0x7fff) {
            throw SaveStateError("État instantané corrompu (registres MBC7)");
        }
        eeprom_.load_state(in);
    }

private:
    static constexpr int state_layout = 1;
    static bool read_bool(BinaryReader& in) {
        const int value = in.u8();
        if (value > 1) throw SaveStateError("État instantané corrompu (booléen MBC7)");
        return value != 0;
    }
    [[nodiscard]] bool registers_enabled() const noexcept { return enabled_1_ && enabled_2_; }
    [[nodiscard]] static int clamp_sample(int delta) noexcept {
        return std::clamp(0x81d0 + delta, 0, 0xffff);
    }

    Mbc7Eeprom eeprom_;
    bool enabled_1_{}; bool enabled_2_{}; int rom_bank_{1};
    bool latch_armed_{}; int latched_x_{0x8000}; int latched_y_{0x8000};
    int acceleration_x_{}; int acceleration_y_{};
};

} // namespace ravenemu::cgb
