#pragma once

#include "support/bits.hpp"

namespace ravenemu::gba {

class MemoryTiming {
public:
    [[nodiscard]] int bus_width(std::int32_t address) const noexcept {
        const auto region = (u32(address) >> 24U) & 0xffU;
        if (region == 0x02U || region == 0x05U || region == 0x06U ||
            (region >= 0x08U && region <= 0x0dU)) return 2;
        if (region == 0x0eU || region == 0x0fU) return 1;
        return 4;
    }
    [[nodiscard]] int wait_states(std::int32_t address, int width, bool sequential) const noexcept {
        const auto first = unit_wait(address, sequential);
        const auto extra = width / bus_width(address) - 1;
        return extra <= 0 ? first : first + extra * (unit_wait(address, true) + 1);
    }
    [[nodiscard]] int instruction_wait(std::int32_t address, int width, bool sequential) const noexcept {
        const auto region = (u32(address) >> 24U) & 0xffU;
        if (sequential && (wait_control & 0x4000) != 0 && region >= 0x08U && region <= 0x0dU) return 0;
        return wait_states(address, width, sequential);
    }
    int wait_control{};

private:
    [[nodiscard]] int unit_wait(std::int32_t address, bool sequential) const noexcept {
        static constexpr std::array n_wait{4, 3, 2, 8};
        const auto raw = u32(address);
        const auto region = (raw >> 24U) & 0xffU;
        if (region == 0x02U) return 2;
        if (region == 0x0eU || region == 0x0fU) return n_wait[static_cast<std::size_t>(wait_control & 3)];
        if (region < 0x08U || region > 0x0dU) return 0;
        switch ((raw >> 25U) & 3U) {
        case 0: return sequential ? ((wait_control & 0x10) != 0 ? 1 : 2)
                                  : n_wait[static_cast<std::size_t>((wait_control >> 2) & 3)];
        case 1: return sequential ? ((wait_control & 0x80) != 0 ? 1 : 4)
                                  : n_wait[static_cast<std::size_t>((wait_control >> 5) & 3)];
        default: return sequential ? ((wait_control & 0x400) != 0 ? 1 : 8)
                                    : n_wait[static_cast<std::size_t>((wait_control >> 8) & 3)];
        }
    }
};

} // namespace ravenemu::gba
