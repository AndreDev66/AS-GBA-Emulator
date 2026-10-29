#pragma once

#include "support/bits.hpp"

namespace ravenemu::gba {

class InterruptController {
public:
    // El bit 7 es la IRQ serie (SIO): la dispara `Serial` al terminar una
    // transferencia con bit 14 de SIOCNT activo.
    enum Bit { vblank = 0, hblank = 1, vcount = 2, timer0 = 3, serial = 7, dma0 = 8 };
    void request(int bit) {
        const auto mask = 1 << bit;
        flags |= mask;
        if (on_request) on_request(mask);
    }
    void acknowledge(int value) noexcept { flags &= ~value; }
    [[nodiscard]] bool pending() const noexcept {
        return master_enable && (enable & flags & 0x3fff) != 0;
    }
    void reset() noexcept { enable = 0; flags = 0; master_enable = false; }
    int enable{};
    int flags{};
    bool master_enable{};
    std::function<void(int)> on_request;
};

} // namespace ravenemu::gba
