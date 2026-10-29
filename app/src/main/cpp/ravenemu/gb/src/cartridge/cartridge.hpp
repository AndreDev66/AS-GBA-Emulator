#pragma once

#include "cartridge/cartridge_header.hpp"
#include <ravenemu/infrared_endpoint.hpp>

namespace ravenemu::cgb {

class Cartridge {
public:
    static constexpr int rom_bank_size = 0x4000;
    static constexpr int ram_bank_size = 0x2000;
    using Clock = std::function<std::int64_t()>;

    Cartridge(RomImage rom, CartridgeHeader header)
        : rom_(std::move(rom)), header_(header), ram_(static_cast<std::size_t>(header.ram_size)) {}
    virtual ~Cartridge() = default;

    [[nodiscard]] virtual int read_rom(int address) const = 0;
    virtual void write_control(int address, int value) = 0;
    [[nodiscard]] virtual int read_ram(int address) const = 0;
    virtual void write_ram(int address, int value) = 0;
    virtual void tick(int) {}
    [[nodiscard]] virtual bool rumble_active() const noexcept { return false; }
    virtual void set_acceleration(int, int) noexcept {}
    [[nodiscard]] virtual bool connect_infrared_endpoint(InfraredEndpoint*) noexcept {
        return true;
    }
    virtual void disconnect_infrared_endpoint() noexcept {}
    virtual void save_state(BinaryWriter& out) const = 0;
    virtual void load_state(BinaryReader& in) = 0;

    [[nodiscard]] const CartridgeHeader& header() const noexcept { return header_; }
    [[nodiscard]] bool dirty() const noexcept { return generation_ != saved_generation_; }
    [[nodiscard]] std::uint64_t generation() const noexcept { return generation_; }
    [[nodiscard]] bool has_persistent_data() const noexcept {
        return header_.has_battery && (!ram_.empty() || header_.has_rtc);
    }

    virtual std::optional<std::vector<std::uint8_t>> export_battery() {
        if (!header_.has_battery || ram_.empty()) return std::nullopt;
        return ram_;
    }

    virtual void import_battery(std::span<const std::uint8_t> data) {
        if (ram_.empty()) return;
        std::copy_n(data.begin(), static_cast<std::ptrdiff_t>(std::min(data.size(), ram_.size())), ram_.begin());
        mark_clean();
    }

    void acknowledge_saved(std::uint64_t generation) noexcept {
        if (generation == generation_) saved_generation_ = generation;
    }

    [[nodiscard]] static std::unique_ptr<Cartridge> create(
        RomImage rom,
        Clock clock
    );

protected:
    [[nodiscard]] int rom_bank_count() const noexcept {
        return std::max(2, static_cast<int>(rom_->size() / rom_bank_size));
    }
    [[nodiscard]] int normalize_rom_bank(int bank) const noexcept { return bank % rom_bank_count(); }
    void mark_written() noexcept { ++generation_; }
    void mark_clean() noexcept { saved_generation_ = generation_; }

    RomImage rom_;
    CartridgeHeader header_;
    std::vector<std::uint8_t> ram_;

private:
    std::uint64_t generation_{};
    std::uint64_t saved_generation_{};
};

} // namespace ravenemu::cgb
