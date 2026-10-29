#pragma once

#include "cartridge/cartridge.hpp"
#include "infrared/cartridge_infrared_port.hpp"

namespace ravenemu::cgb {

/** Mapper Hudson HuC1 avec RAM toujours accessible et transceiver IR. */
class Huc1 final : public Cartridge {
public:
    Huc1(RomImage rom, CartridgeHeader header)
        : Cartridge(std::move(rom), header) {}

    int read_rom(int address) const override {
        const int bank = address < rom_bank_size ? 0 : normalize_rom_bank(rom_bank_);
        const auto offset = static_cast<std::size_t>(
            bank * rom_bank_size + (address & (rom_bank_size - 1))
        );
        return offset < rom_->size() ? (*rom_)[offset] : 0xff;
    }

    void write_control(int address, int value) override {
        if (address <= 0x1fff) {
            ir_mode_ = byte(value) == 0x0e;
        } else if (address <= 0x3fff) {
            // Les mesures publiques établissent au moins six lignes de banque.
            rom_bank_ = byte(value) & 0x3f;
        } else if (address <= 0x5fff) {
            ram_bank_ = byte(value) & 0x03;
        }
        // $6000-$7FFF n'a pas de registre HuC1 observable documenté.
    }

    int read_ram(int address) const override {
        if (ir_mode_) return 0xc0 | (infrared_.light_detected() ? 1 : 0);
        if (ram_.empty()) return 0xff;
        const auto offset = static_cast<std::size_t>(
            ram_bank_ * ram_bank_size + address - 0xa000
        );
        return offset < ram_.size() ? ram_[offset] : 0xff;
    }

    void write_ram(int address, int value) override {
        if (ir_mode_) {
            infrared_.set_led((byte(value) & 1) != 0);
            return;
        }
        if (ram_.empty()) return;
        const auto offset = static_cast<std::size_t>(
            ram_bank_ * ram_bank_size + address - 0xa000
        );
        if (offset >= ram_.size()) return;
        ram_[offset] = static_cast<std::uint8_t>(value);
        mark_written();
    }

    [[nodiscard]] bool connect_infrared_endpoint(InfraredEndpoint* endpoint) noexcept override {
        return infrared_.connect(endpoint);
    }

    void disconnect_infrared_endpoint() noexcept override {
        infrared_.disconnect();
    }

    void save_state(BinaryWriter& out) const override {
        out.i32(state_layout);
        out.boolean(ir_mode_);
        out.i32(rom_bank_);
        out.i32(ram_bank_);
        out.boolean(infrared_.led_on());
        out.boolean(infrared_.light_detected());
        out.raw(ram_);
    }

    void load_state(BinaryReader& in) override {
        if (in.i32() != state_layout) throw SaveStateError("État HuC1 incompatible");
        ir_mode_ = read_bool(in);
        rom_bank_ = in.i32();
        ram_bank_ = in.i32();
        const bool transmitter_on = read_bool(in);
        const bool light_detected = read_bool(in);
        if (rom_bank_ < 0 || rom_bank_ > 0x3f || ram_bank_ < 0 || ram_bank_ > 3) {
            throw SaveStateError("État instantané corrompu (registres HuC1)");
        }
        in.raw(ram_);
        infrared_.restore(transmitter_on, light_detected);
    }

private:
    static constexpr int state_layout = 1;

    static bool read_bool(BinaryReader& in) {
        const int value = in.u8();
        if (value > 1) throw SaveStateError("État instantané corrompu (booléen HuC1)");
        return value != 0;
    }

    bool ir_mode_{};
    int rom_bank_{1};
    int ram_bank_{};
    CartridgeInfraredPort infrared_;
};

} // namespace ravenemu::cgb
