#pragma once

#include "cartridge/cartridge.hpp"

namespace ravenemu::cgb {

class Mbc3 final : public Cartridge {
public:
    static constexpr std::size_t rtc_footer_size = 48;
    static constexpr std::size_t rtc_footer_min_size = 44;

    Mbc3(RomImage rom, CartridgeHeader header, Clock clock)
        : Cartridge(std::move(rom), header), clock_(std::move(clock)),
          last_sync_epoch_(clock_()), has_rtc_(header.has_rtc) {}

    int read_rom(int address) const override {
        const int bank = address < rom_bank_size ? 0 : normalize_rom_bank(rom_bank_);
        const auto offset = static_cast<std::size_t>(bank * rom_bank_size + (address & (rom_bank_size - 1)));
        return offset < rom_->size() ? (*rom_)[offset] : 0xff;
    }
    void write_control(int address, int value) override {
        if (address <= 0x1fff) ram_enabled_ = (value & 0x0f) == 0x0a;
        else if (address <= 0x3fff) { const int v = value & 0x7f; rom_bank_ = v == 0 ? 1 : v; }
        else if (address <= 0x5fff) ram_bank_or_rtc_ = value & 0x0f;
        else {
            if (latch_armed_ && value == 1 && has_rtc_) latch_rtc();
            latch_armed_ = value == 0;
        }
    }
    int read_ram(int address) const override {
        if (!ram_enabled_) return 0xff;
        if (ram_bank_or_rtc_ <= 3) {
            const auto offset = static_cast<std::size_t>(ram_bank_or_rtc_ * ram_bank_size + address - 0xa000);
            return offset < ram_.size() ? ram_[offset] : 0xff;
        }
        if (!has_rtc_) return 0xff;
        switch (ram_bank_or_rtc_) {
        case 0x08: return latch_seconds_; case 0x09: return latch_minutes_;
        case 0x0a: return latch_hours_; case 0x0b: return latch_day_low_;
        case 0x0c: return latch_day_high_; default: return 0xff;
        }
    }
    void write_ram(int address, int value) override {
        if (!ram_enabled_) return;
        if (ram_bank_or_rtc_ <= 3) {
            const auto offset = static_cast<std::size_t>(ram_bank_or_rtc_ * ram_bank_size + address - 0xa000);
            if (offset < ram_.size()) { ram_[offset] = static_cast<std::uint8_t>(value); mark_written(); }
            return;
        }
        if (!has_rtc_) return;
        switch (ram_bank_or_rtc_) {
        case 0x08: sync_rtc(); rtc_seconds_ = value & 0x3f; mark_written(); break;
        case 0x09: sync_rtc(); rtc_minutes_ = value & 0x3f; mark_written(); break;
        case 0x0a: sync_rtc(); rtc_hours_ = value & 0x1f; mark_written(); break;
        case 0x0b: sync_rtc(); rtc_days_ = (rtc_days_ & 0x100) | byte(value); mark_written(); break;
        case 0x0c:
            sync_rtc(); rtc_days_ = (rtc_days_ & 0xff) | ((value & 1) << 8);
            rtc_halt_ = (value & 0x40) != 0; rtc_day_carry_ = (value & 0x80) != 0;
            mark_written(); break;
        default: break;
        }
    }

    std::optional<std::vector<std::uint8_t>> export_battery() override {
        if (!header_.has_battery) return std::nullopt;
        if (!has_rtc_) return Cartridge::export_battery();
        sync_rtc(); latch_rtc();
        std::vector<std::uint8_t> output(ram_.size() + rtc_footer_size);
        std::copy(ram_.begin(), ram_.end(), output.begin());
        std::size_t position = ram_.size();
        const std::array values{
            rtc_seconds_, rtc_minutes_, rtc_hours_, rtc_days_ & 0xff,
            ((rtc_days_ >> 8) & 1) | (rtc_halt_ ? 0x40 : 0) | (rtc_day_carry_ ? 0x80 : 0),
            latch_seconds_, latch_minutes_, latch_hours_, latch_day_low_, latch_day_high_,
        };
        for (const int value : values) {
            for (int i = 0; i < 4; ++i) output[position++] = static_cast<std::uint8_t>(value >> (i * 8));
        }
        auto timestamp = static_cast<std::uint64_t>(last_sync_epoch_);
        for (int i = 0; i < 8; ++i) { output[position++] = static_cast<std::uint8_t>(timestamp); timestamp >>= 8U; }
        return output;
    }

    void import_battery(std::span<const std::uint8_t> data) override {
        Cartridge::import_battery(data);
        if (!has_rtc_ || data.size() < ram_.size() + rtc_footer_min_size) return;
        std::size_t position = ram_.size();
        const auto read_u32_le = [&]() {
            std::uint32_t value{};
            for (unsigned i = 0; i < 4; ++i) value |= static_cast<std::uint32_t>(data[position++]) << (i * 8U);
            return value;
        };
        rtc_seconds_ = static_cast<int>(read_u32_le() & 0x3fU);
        rtc_minutes_ = static_cast<int>(read_u32_le() & 0x3fU);
        rtc_hours_ = static_cast<int>(read_u32_le() & 0x1fU);
        const int day_low = static_cast<int>(read_u32_le() & 0xffU);
        const auto day_high = read_u32_le();
        rtc_days_ = day_low | (static_cast<int>(day_high & 1U) << 8);
        rtc_halt_ = (day_high & 0x40U) != 0; rtc_day_carry_ = (day_high & 0x80U) != 0;
        latch_seconds_ = static_cast<int>(read_u32_le() & 0x3fU);
        latch_minutes_ = static_cast<int>(read_u32_le() & 0x3fU);
        latch_hours_ = static_cast<int>(read_u32_le() & 0x1fU);
        latch_day_low_ = static_cast<int>(read_u32_le() & 0xffU);
        latch_day_high_ = static_cast<int>(read_u32_le() & 0xffU);
        const int timestamp_bytes = data.size() >= ram_.size() + rtc_footer_size ? 8 : 4;
        std::uint64_t timestamp{};
        for (unsigned i = 0; i < static_cast<unsigned>(timestamp_bytes); ++i) {
            timestamp |= static_cast<std::uint64_t>(data[position + static_cast<std::size_t>(i)]) << (i * 8U);
        }
        last_sync_epoch_ = static_cast<std::int64_t>(timestamp);
        sync_rtc(); mark_clean();
    }

    void save_state(BinaryWriter& out) const override {
        out.boolean(ram_enabled_); out.i32(rom_bank_); out.i32(ram_bank_or_rtc_);
        out.boolean(latch_armed_); out.i32(rtc_seconds_); out.i32(rtc_minutes_);
        out.i32(rtc_hours_); out.i32(rtc_days_); out.boolean(rtc_halt_);
        out.boolean(rtc_day_carry_); out.i32(latch_seconds_); out.i32(latch_minutes_);
        out.i32(latch_hours_); out.i32(latch_day_low_); out.i32(latch_day_high_);
        out.i64(last_sync_epoch_); out.raw(ram_);
    }
    void load_state(BinaryReader& in) override {
        ram_enabled_ = in.boolean(); rom_bank_ = in.i32(); ram_bank_or_rtc_ = in.i32();
        latch_armed_ = in.boolean(); rtc_seconds_ = in.i32(); rtc_minutes_ = in.i32();
        rtc_hours_ = in.i32(); rtc_days_ = in.i32(); rtc_halt_ = in.boolean();
        rtc_day_carry_ = in.boolean(); latch_seconds_ = in.i32(); latch_minutes_ = in.i32();
        latch_hours_ = in.i32(); latch_day_low_ = in.i32(); latch_day_high_ = in.i32();
        last_sync_epoch_ = in.i64(); in.raw(ram_);
    }

private:
    void sync_rtc() {
        const auto now = clock_();
        auto elapsed = now - last_sync_epoch_;
        last_sync_epoch_ = now;
        if (rtc_halt_ || elapsed <= 0) return;
        elapsed += rtc_seconds_ + 60LL * rtc_minutes_ + 3600LL * rtc_hours_ + 86400LL * rtc_days_;
        rtc_seconds_ = static_cast<int>(elapsed % 60); elapsed /= 60;
        rtc_minutes_ = static_cast<int>(elapsed % 60); elapsed /= 60;
        rtc_hours_ = static_cast<int>(elapsed % 24); elapsed /= 24;
        rtc_days_ = static_cast<int>(elapsed & 0x1ff);
        if (elapsed > 0x1ff) rtc_day_carry_ = true;
    }
    void latch_rtc() {
        sync_rtc(); latch_seconds_ = rtc_seconds_; latch_minutes_ = rtc_minutes_;
        latch_hours_ = rtc_hours_; latch_day_low_ = rtc_days_ & 0xff;
        latch_day_high_ = ((rtc_days_ >> 8) & 1) | (rtc_halt_ ? 0x40 : 0) | (rtc_day_carry_ ? 0x80 : 0);
    }

    Clock clock_;
    bool ram_enabled_{};
    int rom_bank_{1};
    int ram_bank_or_rtc_{};
    bool latch_armed_{};
    int rtc_seconds_{};
    int rtc_minutes_{};
    int rtc_hours_{};
    int rtc_days_{};
    bool rtc_halt_{};
    bool rtc_day_carry_{};
    int latch_seconds_{};
    int latch_minutes_{};
    int latch_hours_{};
    int latch_day_low_{};
    int latch_day_high_{};
    std::int64_t last_sync_epoch_{};
    bool has_rtc_{};
};

} // namespace ravenemu::cgb
