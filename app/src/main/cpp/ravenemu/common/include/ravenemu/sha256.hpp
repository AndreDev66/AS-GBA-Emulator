#pragma once

#include <array>
#include <cstdint>
#include <span>

namespace ravenemu::detail {

[[nodiscard]] std::array<std::uint8_t, 32> sha256(std::span<const std::uint8_t> data);

} // namespace ravenemu::detail
