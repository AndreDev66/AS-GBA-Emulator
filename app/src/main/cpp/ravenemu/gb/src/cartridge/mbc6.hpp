#pragma once

#include "cartridge/cartridge.hpp"
#include "cartridge/mbc6_flash.hpp"

namespace ravenemu::cgb {

/** MBC6 avec deux fenêtres ROM/flash de 8 Kio et deux fenêtres RAM de 4 Kio. */
class Mbc6 final : public Cartridge {
public:
    static constexpr int rom_window_size = 0x2000;
    static constexpr int ram_window_size = 0x1000;
    static constexpr std::size_t sram_size = 32U * 1024U;
    static constexpr std::size_t battery_footer_size = 8;
    static constexpr std::size_t battery_image_size =
        sram_size + Mbc6Flash::storage_size + Mbc6Flash::hidden_size +
        battery_footer_size;

    Mbc6(RomImage rom, CartridgeHeader header)
        : Cartridge(std::move(rom), header) {}

    int read_rom(int address) const override {
        if (address < 0x4000) {
            const auto offset = static_cast<std::size_t>(address);
            return offset < rom_->size() ? (*rom_)[offset] : 0xff;
        }

        const bool window_a = address < 0x6000;
        const bool flash_selected = window_a ? flash_selected_a_ : flash_selected_b_;
        const int bank = window_a ? rom_flash_bank_a_ : rom_flash_bank_b_;
        if (flash_selected) {
            if (!flash_enabled_) return 0xff;
            return flash_.read(flash_address(bank, address));
        }

        const int normalized = normalize_rom_bank(bank);
        const auto offset = static_cast<std::size_t>(normalized * rom_window_size +
                                                     (address & (rom_window_size - 1)));
        return offset < rom_->size() ? (*rom_)[offset] : 0xff;
    }

    void write_control(int address, int value) override {
        if (address <= 0x03ff) {
            ram_enabled_ = (value & 0x0f) == 0x0a;
        } else if (address <= 0x07ff) {
            ram_bank_a_ = value & 0x07;
        } else if (address <= 0x0bff) {
            ram_bank_b_ = value & 0x07;
        } else if (address <= 0x0fff) {
            flash_enabled_ = (value & 0x01) != 0;
        } else if (address == 0x1000) {
            flash_write_enabled_ = (value & 0x01) != 0;
        } else if (address >= 0x2000 && address <= 0x27ff) {
            rom_flash_bank_a_ = value & 0x7f;
        } else if (address >= 0x2800 && address <= 0x2fff) {
            flash_selected_a_ = (value & 0x08) != 0;
        } else if (address >= 0x3000 && address <= 0x37ff) {
            rom_flash_bank_b_ = value & 0x7f;
        } else if (address >= 0x3800 && address <= 0x3fff) {
            flash_selected_b_ = (value & 0x08) != 0;
        } else if (address >= 0x4000 && address <= 0x7fff) {
            const bool window_a = address < 0x6000;
            const bool selected = window_a ? flash_selected_a_ : flash_selected_b_;
            if (!flash_enabled_ || !selected) return;
            const int bank = window_a ? rom_flash_bank_a_ : rom_flash_bank_b_;
            if (flash_.write(flash_address(bank, address), value,
                             flash_write_enabled_)) {
                mark_written();
            }
        }
    }

    int read_ram(int address) const override {
        if (!ram_enabled_ || ram_.empty()) return 0xff;
        const auto offset = ram_offset(address);
        return offset < ram_.size() ? ram_[offset] : 0xff;
    }

    void write_ram(int address, int value) override {
        if (!ram_enabled_ || ram_.empty()) return;
        const auto offset = ram_offset(address);
        if (offset >= ram_.size()) return;
        const auto data = static_cast<std::uint8_t>(value);
        if (ram_[offset] == data) return;
        ram_[offset] = data;
        mark_written();
    }

    std::optional<std::vector<std::uint8_t>> export_battery() override {
        if (!header_.has_battery) return std::nullopt;
        std::vector<std::uint8_t> output(battery_image_size);
        std::size_t position{};
        std::copy(ram_.begin(), ram_.end(), output.begin());
        position += ram_.size();
        std::copy(flash_.storage().begin(), flash_.storage().end(),
                  output.begin() + static_cast<std::ptrdiff_t>(position));
        position += flash_.storage().size();
        std::copy(flash_.hidden().begin(), flash_.hidden().end(),
                  output.begin() + static_cast<std::ptrdiff_t>(position));
        position += flash_.hidden().size();
        std::copy(battery_magic.begin(), battery_magic.end(),
                  output.begin() + static_cast<std::ptrdiff_t>(position));
        position += battery_magic.size();
        output[position++] = battery_layout;
        output[position++] = flash_.sector_zero_protected() ? 1U : 0U;
        output[position++] = 0;
        output[position] = 0;
        return output;
    }

    void import_battery(std::span<const std::uint8_t> data) override {
        if (data.size() != battery_image_size) return;
        const std::size_t footer = sram_size + Mbc6Flash::storage_size +
            Mbc6Flash::hidden_size;
        if (!std::equal(battery_magic.begin(), battery_magic.end(),
                        data.begin() + static_cast<std::ptrdiff_t>(footer)) ||
            data[footer + 4] != battery_layout || data[footer + 5] > 1 ||
            data[footer + 6] != 0 || data[footer + 7] != 0) {
            return;
        }

        std::copy_n(data.begin(), static_cast<std::ptrdiff_t>(sram_size), ram_.begin());
        const auto flash_begin = data.subspan(sram_size, Mbc6Flash::storage_size);
        const auto hidden_begin = data.subspan(
            sram_size + Mbc6Flash::storage_size, Mbc6Flash::hidden_size
        );
        flash_.import_persistent(flash_begin, hidden_begin, data[footer + 5] != 0);
        mark_clean();
    }

    void save_state(BinaryWriter& out) const override {
        out.i32(state_layout);
        out.u8(ram_enabled_ ? 1U : 0U);
        out.u8(static_cast<std::uint8_t>(ram_bank_a_));
        out.u8(static_cast<std::uint8_t>(ram_bank_b_));
        out.u8(flash_enabled_ ? 1U : 0U);
        out.u8(flash_write_enabled_ ? 1U : 0U);
        out.u8(static_cast<std::uint8_t>(rom_flash_bank_a_));
        out.u8(flash_selected_a_ ? 1U : 0U);
        out.u8(static_cast<std::uint8_t>(rom_flash_bank_b_));
        out.u8(flash_selected_b_ ? 1U : 0U);
        flash_.save_state(out);
        out.raw(ram_);
    }

    void load_state(BinaryReader& in) override {
        if (in.i32() != state_layout) {
            throw SaveStateError("État instantané corrompu (MBC6 incompatible)");
        }
        ram_enabled_ = read_boolean(in);
        ram_bank_a_ = in.u8();
        ram_bank_b_ = in.u8();
        flash_enabled_ = read_boolean(in);
        flash_write_enabled_ = read_boolean(in);
        rom_flash_bank_a_ = in.u8();
        flash_selected_a_ = read_boolean(in);
        rom_flash_bank_b_ = in.u8();
        flash_selected_b_ = read_boolean(in);
        if (ram_bank_a_ > 7 || ram_bank_b_ > 7 ||
            rom_flash_bank_a_ > 0x7f || rom_flash_bank_b_ > 0x7f) {
            throw SaveStateError("État instantané corrompu (registres MBC6)");
        }
        flash_.load_state(in);
        in.raw(ram_);
    }

private:
    static constexpr int state_layout = 1;
    static constexpr std::uint8_t battery_layout = 1;
    static constexpr std::array<std::uint8_t, 4> battery_magic{'R', 'V', 'M', '6'};

    static bool read_boolean(BinaryReader& in) {
        const int value = in.u8();
        if (value > 1) throw SaveStateError("État instantané corrompu (booléen MBC6)");
        return value != 0;
    }

    [[nodiscard]] int normalize_rom_bank(int bank) const noexcept {
        const int bank_count = std::max(
            4, static_cast<int>(rom_->size() / static_cast<std::size_t>(rom_window_size))
        );
        return bank % bank_count;
    }

    [[nodiscard]] static std::size_t flash_address(int bank, int address) noexcept {
        return static_cast<std::size_t>(bank * rom_window_size +
                                        (address & (rom_window_size - 1)));
    }

    [[nodiscard]] std::size_t ram_offset(int address) const noexcept {
        const int bank = address < 0xb000 ? ram_bank_a_ : ram_bank_b_;
        return static_cast<std::size_t>(bank * ram_window_size +
                                        (address & (ram_window_size - 1)));
    }

    Mbc6Flash flash_;
    bool ram_enabled_{};
    int ram_bank_a_{};
    int ram_bank_b_{1};
    bool flash_enabled_{};
    bool flash_write_enabled_{};
    int rom_flash_bank_a_{2};
    bool flash_selected_a_{};
    int rom_flash_bank_b_{3};
    bool flash_selected_b_{};
};

} // namespace ravenemu::cgb
