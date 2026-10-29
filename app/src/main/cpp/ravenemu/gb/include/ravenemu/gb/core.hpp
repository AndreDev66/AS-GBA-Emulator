#pragma once
#include <ravenemu/core.hpp>
#include <ravenemu/gb/hardware_mode.hpp>

namespace ravenemu::gb {
/** Fabrique GB/GBC explicite. Le mode automatique conserve le contrat JNI. */
[[nodiscard]] std::unique_ptr<Core> make_core(
    HardwareModel model = HardwareModel::automatic,
    std::span<const std::uint8_t> boot_rom = {}
);
}
