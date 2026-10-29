#pragma once

#include "support/bits.hpp"

namespace ravenemu::cgb {

enum class Interrupt : std::uint8_t { vblank, stat, timer, serial, joypad };

constexpr int interrupt_mask(Interrupt interrupt) noexcept {
    return 1 << static_cast<int>(interrupt);
}

constexpr int interrupt_vector(Interrupt interrupt) noexcept {
    return 0x40 + static_cast<int>(interrupt) * 8;
}

class InterruptController {
public:
    int flags{0xe1};
    int enable{};

    void request(Interrupt interrupt) noexcept { flags |= interrupt_mask(interrupt); }
    void acknowledge(Interrupt interrupt) noexcept { flags &= ~interrupt_mask(interrupt); }
    [[nodiscard]] int pending() const noexcept { return enable & flags & 0x1f; }

    [[nodiscard]] std::optional<Interrupt> highest_pending() const noexcept {
        const auto value = pending();
        for (int index = 0; index < 5; ++index) {
            if ((value & (1 << index)) != 0) return static_cast<Interrupt>(index);
        }
        return std::nullopt;
    }

    [[nodiscard]] int read_flags() const noexcept { return flags | 0xe0; }
    void write_flags(int value) noexcept { flags = value & 0x1f; }
    [[nodiscard]] int read_enable() const noexcept { return enable; }
    void write_enable(int value) noexcept { enable = byte(value); }
};

} // namespace ravenemu::cgb
