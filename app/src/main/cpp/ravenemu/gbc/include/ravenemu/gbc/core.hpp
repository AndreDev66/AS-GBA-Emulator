#pragma once
#include <ravenemu/core.hpp>

namespace ravenemu::gbc {
/** Cœur GBC public. L'identité persistée reste GAME_BOY tant que GB/GBC partagent
 * le contrat de bibliothèque, mais la cible et les composants CGB sont compilés
 * séparément afin de poursuivre l'extraction sans duplication. */
[[nodiscard]] std::unique_ptr<Core> make_core(std::span<const std::uint8_t> boot_rom = {});
}
