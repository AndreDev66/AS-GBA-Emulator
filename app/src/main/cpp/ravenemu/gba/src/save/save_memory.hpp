#pragma once

#include "support/bits.hpp"

namespace ravenemu::gba {

constexpr std::size_t save_size(GbaSaveType type) noexcept {
    switch (type) {
    case GbaSaveType::sram: return 32U * 1024U;
    case GbaSaveType::flash_64k: return 64U * 1024U;
    case GbaSaveType::flash_128k: return 128U * 1024U;
    case GbaSaveType::eeprom_512: return 512U;
    case GbaSaveType::eeprom_8k: return 8U * 1024U;
    case GbaSaveType::none: return 0;
    }
    return 0;
}

class SaveMemory {
public:
    explicit SaveMemory(GbaSaveType type) : type_(type), data_(save_size(type)) {}
    virtual ~SaveMemory() = default;

    [[nodiscard]] GbaSaveType type() const noexcept { return type_; }
    [[nodiscard]] bool dirty() const noexcept { return generation_ != saved_generation_; }
    [[nodiscard]] std::uint64_t generation() const noexcept { return generation_; }
    [[nodiscard]] const std::vector<std::uint8_t>& data() const noexcept { return data_; }
    [[nodiscard]] std::vector<std::uint8_t>& data() noexcept { return data_; }

    void import(std::span<const std::uint8_t> saved) {
        const auto count = std::min(saved.size(), data_.size());
        std::copy_n(saved.begin(), static_cast<std::ptrdiff_t>(count), data_.begin());
        std::fill(data_.begin() + static_cast<std::ptrdiff_t>(count), data_.end(), 0);
        saved_generation_ = generation_;
    }
    void acknowledge(std::uint64_t generation) noexcept {
        if (generation == generation_) saved_generation_ = generation;
    }
    /** Marca el contenido actual como pendiente de volcar a disco. */
    void mark_modified() noexcept { ++generation_; }
    virtual int read(int address) = 0;
    virtual void write(int address, int value) = 0;
    virtual void hint_transfer_length(int) noexcept {}

protected:
    void written() noexcept { ++generation_; }

private:
    GbaSaveType type_;
    std::vector<std::uint8_t> data_;
    std::uint64_t generation_{};
    std::uint64_t saved_generation_{};
};

} // namespace ravenemu::gba
