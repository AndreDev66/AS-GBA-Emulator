#pragma once
#include <ravenemu/core.hpp>

namespace ravenemu::gba {
inline std::unique_ptr<Core> make_core() { return make_gba_core(); }
}
