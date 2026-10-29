#pragma once

#include <cstdint>

namespace ravenemu::gb {

/** Modèle physique demandé à la fabrique publique. */
enum class HardwareModel : std::uint8_t {
    automatic,
    dmg,
    cgb,
};

/** Mode matériel effectif après inspection de l'en-tête de cartouche. */
enum class HardwareMode : std::uint8_t {
    dmg,
    cgb_native,
    cgb_compatibility,
};

[[nodiscard]] constexpr bool is_cgb_hardware(HardwareMode mode) noexcept {
    return mode != HardwareMode::dmg;
}

[[nodiscard]] constexpr bool cgb_features_enabled(HardwareMode mode) noexcept {
    return mode == HardwareMode::cgb_native;
}

/** Le panneau LCD reste couleur en mode de compatibilité DMG du CGB. */
[[nodiscard]] constexpr bool cgb_color_output(HardwareMode mode) noexcept {
    return is_cgb_hardware(mode);
}

[[nodiscard]] constexpr bool uses_dmg_rendering(HardwareMode mode) noexcept {
    return mode != HardwareMode::cgb_native;
}

/** Le firmware CGB s'exécute en mode natif avant de sélectionner le mode DMG. */
[[nodiscard]] constexpr HardwareMode boot_execution_mode(HardwareMode final_mode,
                                                         bool boot_rom_mapped) noexcept {
    return boot_rom_mapped && final_mode == HardwareMode::cgb_compatibility
        ? HardwareMode::cgb_native : final_mode;
}

} // namespace ravenemu::gb
