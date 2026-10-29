#pragma once

#include "cartridge/cartridge.hpp"
#include "cartridge/huc3_mcu.hpp"
#include "infrared/cartridge_infrared_port.hpp"

namespace ravenemu::cgb {

/** Mapper Hudson HuC3 : banking, SRAM, MCU RTC/sémaphore et infrarouge. */
class Huc3 final : public Cartridge {
public:
    static constexpr std::size_t battery_footer_size = Huc3Mcu::battery_footer_size;

    Huc3(RomImage rom, CartridgeHeader header, Clock clock)
        : Cartridge(std::move(rom), header), mcu_(std::move(clock)) {}

    int read_rom(int address) const override {
        const int bank = address < rom_bank_size ? 0 : normalize_rom_bank(rom_bank_);
        const auto offset = static_cast<std::size_t>(
            bank * rom_bank_size + (address & (rom_bank_size - 1))
        );
        return offset < rom_->size() ? (*rom_)[offset] : 0xff;
    }

    void write_control(int address, int value) override {
        if (address <= 0x1fff) mode_ = byte(value) & 0x0f;
        else if (address <= 0x3fff) rom_bank_ = byte(value) & 0x7f;
        else if (address <= 0x5fff) ram_bank_ = byte(value) & 0x03;
        // Aucune fonction observable n'est attribuée à $6000-$7FFF.
    }

    int read_ram(int address) const override {
        switch (mode_) {
        case 0x0: case 0xa: return read_sram(address);
        case 0xb: return mcu_.read_mailbox();
        case 0xc: return mcu_.read_response();
        case 0xd: return mcu_.read_semaphore();
        case 0xe: return mcu_.read_infrared(infrared_.light_detected());
        default: return 0xff;
        }
    }

    void write_ram(int address, int value) override {
        switch (mode_) {
        case 0xa:
            write_sram(address, value);
            break;
        case 0xb:
            mcu_.write_mailbox(value);
            break;
        case 0xd:
            mcu_.write_semaphore(value);
            break;
        case 0xe:
            infrared_.set_led((byte(value) & 1) != 0);
            break;
        default:
            // Mode 0 : SRAM en lecture seule. C et les modes non décodés
            // ignorent les écritures.
            break;
        }
    }

    void tick(int dots) override {
        if (mcu_.tick(dots)) mark_written();
    }

    [[nodiscard]] bool connect_infrared_endpoint(InfraredEndpoint* endpoint) noexcept override {
        return infrared_.connect(endpoint);
    }

    void disconnect_infrared_endpoint() noexcept override {
        infrared_.disconnect();
    }

    std::optional<std::vector<std::uint8_t>> export_battery() override {
        if (!header_.has_battery) return std::nullopt;
        auto footer = mcu_.export_battery_footer();
        std::vector<std::uint8_t> output;
        output.reserve(ram_.size() + footer.size());
        output.insert(output.end(), ram_.begin(), ram_.end());
        output.insert(output.end(), footer.begin(), footer.end());
        return output;
    }

    void import_battery(std::span<const std::uint8_t> data) override {
        if (data.size() == ram_.size()) {
            std::copy(data.begin(), data.end(), ram_.begin());
            mcu_.reset_persistent_state();
            mark_clean();
            return;
        }
        if (data.size() != ram_.size() + battery_footer_size) return;
        if (!mcu_.import_battery_footer(data.subspan(ram_.size()))) return;
        std::copy_n(data.begin(), static_cast<std::ptrdiff_t>(ram_.size()), ram_.begin());
        mark_clean();
    }

    void save_state(BinaryWriter& out) const override {
        out.i32(state_layout);
        out.u8(static_cast<std::uint8_t>(mode_));
        out.u8(static_cast<std::uint8_t>(rom_bank_));
        out.u8(static_cast<std::uint8_t>(ram_bank_));
        out.u8(infrared_.led_on() ? 1U : 0U);
        out.u8(infrared_.light_detected() ? 1U : 0U);
        mcu_.save_state(out);
        out.raw(ram_);
    }

    void load_state(BinaryReader& in) override {
        if (in.i32() != state_layout) throw SaveStateError("État HuC3 incompatible");
        mode_ = in.u8();
        rom_bank_ = in.u8();
        ram_bank_ = in.u8();
        const bool transmitter_on = read_bool(in);
        const bool light_detected = read_bool(in);
        if (mode_ > 0x0f || rom_bank_ > 0x7f || ram_bank_ > 3) {
            throw SaveStateError("État instantané corrompu (registres HuC3)");
        }
        mcu_.load_state(in);
        in.raw(ram_);
        infrared_.restore(transmitter_on, light_detected);
    }

private:
    static constexpr int state_layout = 1;

    static bool read_bool(BinaryReader& in) {
        const int value = in.u8();
        if (value > 1) throw SaveStateError("État instantané corrompu (booléen HuC3)");
        return value != 0;
    }

    [[nodiscard]] int read_sram(int address) const noexcept {
        const auto offset = static_cast<std::size_t>(
            ram_bank_ * ram_bank_size + address - 0xa000
        );
        return offset < ram_.size() ? ram_[offset] : 0xff;
    }

    void write_sram(int address, int value) noexcept {
        const auto offset = static_cast<std::size_t>(
            ram_bank_ * ram_bank_size + address - 0xa000
        );
        if (offset >= ram_.size()) return;
        ram_[offset] = static_cast<std::uint8_t>(value);
        mark_written();
    }

    Huc3Mcu mcu_;
    CartridgeInfraredPort infrared_;
    int mode_{};
    int rom_bank_{1};
    int ram_bank_{};
};

} // namespace ravenemu::cgb
