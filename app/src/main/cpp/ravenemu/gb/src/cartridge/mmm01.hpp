#pragma once

#include "cartridge/cartridge.hpp"

namespace ravenemu::cgb {

/**
 * Contrôleur de cartouche MMM01.
 *
 * Le menu démarre avec les deux dernières banques visibles. Avant de céder la
 * main à un jeu, il choisit ses bits hauts, masque les bits qui appartiennent
 * à cette sélection, puis arme une transition irréversible vers le mode MBC1.
 */
class Mmm01 final : public Cartridge {
public:
    using Cartridge::Cartridge;

    int read_rom(int address) const override {
        if (!mapped_) {
            const auto menu_base = rom_->size() - CartridgeHeader::min_rom_size;
            const auto offset = menu_base + static_cast<std::size_t>(address & 0x7fff);
            return (*rom_)[offset];
        }

        const int bank = address < rom_bank_size ? lower_rom_bank() : upper_rom_bank();
        const auto offset = static_cast<std::size_t>(bank * rom_bank_size +
                                                     (address & (rom_bank_size - 1)));
        return offset < rom_->size() ? (*rom_)[offset] : 0xff;
    }

    void write_control(int address, int value) override {
        const int data = value & 0x7f;
        if (address <= 0x1fff) {
            ram_enabled_ = (data & 0x0f) == 0x0a;
            if (!mapped_) {
                // Pan Docs ne tranche pas l'écriture simultanée du masque et
                // du bit de mapping. Appliquer d'abord le masque correspond à
                // la séquence à deux écritures utilisée par le matériel publié
                // et garde le résultat déterministe pour les ROMs maison.
                ram_bank_mask_ = (data >> 4) & 0x03;
                if ((data & 0x40) != 0) mapped_ = true;
            }
            return;
        }
        if (address <= 0x3fff) {
            const int incoming_low = data & 0x1f;
            rom_bank_low_ = (rom_bank_low_ & rom_bank_mask_) |
                (incoming_low & (~rom_bank_mask_ & 0x1f));
            if (!mapped_) rom_bank_mid_ = (data >> 5) & 0x03;
            return;
        }
        if (address <= 0x5fff) {
            const int incoming_low = data & 0x03;
            ram_bank_low_ = (ram_bank_low_ & ram_bank_mask_) |
                (incoming_low & (~ram_bank_mask_ & 0x03));
            if (!mapped_) {
                ram_bank_high_ = (data >> 2) & 0x03;
                rom_bank_high_ = (data >> 4) & 0x03;
                mode_write_locked_ = (data & 0x40) != 0;
            }
            return;
        }

        if (!mode_write_locked_) mode_select_ = (data & 0x01) != 0;
        if (!mapped_) {
            // Le bit le plus bas du masque matériel est câblé à zéro : le bit
            // 0 du registre ROM reste toujours modifiable.
            rom_bank_mask_ = ((data >> 1) & 0x1f) & 0x1e;
            multiplex_ = (data & 0x40) != 0;
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
        ram_[offset] = static_cast<std::uint8_t>(value);
        mark_written();
    }

    void save_state(BinaryWriter& out) const override {
        out.i32(state_layout);
        out.u8(mapped_ ? 1U : 0U);
        out.u8(ram_enabled_ ? 1U : 0U);
        out.u8(static_cast<std::uint8_t>(rom_bank_low_));
        out.u8(static_cast<std::uint8_t>(rom_bank_mid_));
        out.u8(static_cast<std::uint8_t>(rom_bank_high_));
        out.u8(static_cast<std::uint8_t>(ram_bank_low_));
        out.u8(static_cast<std::uint8_t>(ram_bank_high_));
        out.u8(static_cast<std::uint8_t>(rom_bank_mask_));
        out.u8(static_cast<std::uint8_t>(ram_bank_mask_));
        out.u8(mode_select_ ? 1U : 0U);
        out.u8(multiplex_ ? 1U : 0U);
        out.u8(mode_write_locked_ ? 1U : 0U);
        out.raw(ram_);
    }

    void load_state(BinaryReader& in) override {
        if (in.i32() != state_layout) {
            throw SaveStateError("État instantané corrompu (MMM01 incompatible)");
        }
        mapped_ = read_boolean(in);
        ram_enabled_ = read_boolean(in);
        rom_bank_low_ = in.u8();
        rom_bank_mid_ = in.u8();
        rom_bank_high_ = in.u8();
        ram_bank_low_ = in.u8();
        ram_bank_high_ = in.u8();
        rom_bank_mask_ = in.u8();
        ram_bank_mask_ = in.u8();
        mode_select_ = read_boolean(in);
        multiplex_ = read_boolean(in);
        mode_write_locked_ = read_boolean(in);
        if (rom_bank_low_ > 0x1f || rom_bank_mid_ > 3 || rom_bank_high_ > 3 ||
            ram_bank_low_ > 3 || ram_bank_high_ > 3 ||
            (rom_bank_mask_ & ~0x1e) != 0 || ram_bank_mask_ > 3) {
            throw SaveStateError("État instantané corrompu (registres MMM01)");
        }
        in.raw(ram_);
    }

private:
    static constexpr int state_layout = 1;

    static bool read_boolean(BinaryReader& in) {
        const int value = in.u8();
        if (value > 1) throw SaveStateError("État instantané corrompu (booléen MMM01)");
        return value != 0;
    }

    [[nodiscard]] int selected_upper_low() const noexcept {
        int low = rom_bank_low_;
        if ((low & (~rom_bank_mask_ & 0x1f)) == 0) low |= 1;
        return low;
    }

    [[nodiscard]] int lower_rom_bank() const noexcept {
        const int low = rom_bank_low_ & rom_bank_mask_;
        const int middle = multiplex_
            ? (mode_select_ ? ram_bank_low_ : (ram_bank_low_ & ram_bank_mask_))
            : rom_bank_mid_;
        return normalize_rom_bank((rom_bank_high_ << 7) | (middle << 5) | low);
    }

    [[nodiscard]] int upper_rom_bank() const noexcept {
        const int middle = multiplex_ ? ram_bank_low_ : rom_bank_mid_;
        return normalize_rom_bank((rom_bank_high_ << 7) | (middle << 5) |
                                  selected_upper_low());
    }

    [[nodiscard]] int selected_ram_bank() const noexcept {
        if (multiplex_) return (ram_bank_high_ << 2) | rom_bank_mid_;
        const int low = mode_select_ ? ram_bank_low_ : (ram_bank_low_ & ram_bank_mask_);
        return (ram_bank_high_ << 2) | low;
    }

    [[nodiscard]] std::size_t ram_offset(int address) const noexcept {
        return static_cast<std::size_t>(selected_ram_bank() * ram_bank_size +
                                        address - 0xa000);
    }

    bool mapped_{};
    bool ram_enabled_{};
    int rom_bank_low_{};
    int rom_bank_mid_{};
    int rom_bank_high_{};
    int ram_bank_low_{};
    int ram_bank_high_{};
    int rom_bank_mask_{};
    int ram_bank_mask_{};
    bool mode_select_{};
    bool multiplex_{};
    bool mode_write_locked_{};
};

} // namespace ravenemu::cgb
