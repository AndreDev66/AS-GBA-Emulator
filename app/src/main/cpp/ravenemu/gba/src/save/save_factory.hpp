#pragma once

#include "save/sram.hpp"
#include "save/flash.hpp"
#include "save/eeprom.hpp"

#include <algorithm>
#include <array>
#include <optional>
#include <span>
#include <string_view>

namespace ravenemu::gba {

// Seul point qui connaît la liste des mémoires de sauvegarde : la chaîne
// d'identification laissée dans la ROM par l'éditeur décide du type, et un
// réglage imposé par le joueur prime au prochain reset.
inline std::unique_ptr<SaveMemory> make_save(GbaSaveType type) {
    switch (type) {
    case GbaSaveType::sram: return std::make_unique<Sram>();
    case GbaSaveType::flash_64k:
    case GbaSaveType::flash_128k: return std::make_unique<Flash>(type);
    case GbaSaveType::eeprom_512:
    case GbaSaveType::eeprom_8k: return std::make_unique<Eeprom>(type);
    case GbaSaveType::none: return nullptr;
    }
    return nullptr;
}

// Los identificadores que las herramientas de desarrollo dejan en la ROM son un
// prefijo más tres dígitos de versión. Sólo se buscan en direcciones alineadas a
// cuatro bytes, que es como se emiten y como los aceptado el resto de
// herramientas de referencia.
inline bool has_aligned_marker(std::span<const std::uint8_t> rom, std::string_view marker) {
    for (std::size_t offset = 0; offset + marker.size() <= rom.size(); offset += 4) {
        if (std::equal(marker.begin(), marker.end(), rom.begin() + static_cast<std::ptrdiff_t>(offset))) {
            return true;
        }
    }
    return false;
}

/**
 * Tamaño que corresponde a una EEPROM según su versión.
 *
 * Este detalle decide si una partida cabe. La V111 es el modelo de 512 bytes y
 * las V12x (V120 a V126) son el de 8 KiB: tratar todas como de 512 hacía que
 * un juego con la versión nueva perdiera 7,5 KiB de partida al guardar, porque
 * sus escrituras caían fuera de una memoria demasiado pequeña.
 */
inline std::optional<GbaSaveType> detect_eeprom(std::span<const std::uint8_t> rom) {
    constexpr std::string_view prefix{"EEPROM_V"};
    for (std::size_t offset = 0; offset + prefix.size() <= rom.size(); offset += 4) {
        if (!std::equal(prefix.begin(), prefix.end(), rom.begin() + static_cast<std::ptrdiff_t>(offset))) {
            continue;
        }
        int version = 0;
        bool digits = true;
        for (int i = 0; i < 3; ++i) {
            const auto pos = offset + prefix.size() + static_cast<std::size_t>(i);
            if (pos >= rom.size()) {
                digits = false;
                break;
            }
            const char c = static_cast<char>(rom[pos]);
            if (c < '0' || c > '9') {
                digits = false;
                break;
            }
            version = version * 10 + (c - '0');
        }
        // Sin versión legible se asume la de 512 bytes, que es la mayoritaria
        // entre los juegos con EEPROM.
        if (!digits || version == 0) return GbaSaveType::eeprom_512;
        return version == 111 ? GbaSaveType::eeprom_512 : GbaSaveType::eeprom_8k;
    }
    return std::nullopt;
}

inline GbaSaveType detect_save(std::span<const std::uint8_t> rom) {
    constexpr std::array flash_markers{
        std::pair{std::string_view{"FLASH1M_V"}, GbaSaveType::flash_128k},
        std::pair{std::string_view{"FLASH512_V"}, GbaSaveType::flash_64k},
        std::pair{std::string_view{"FLASH_V"}, GbaSaveType::flash_64k},
    };
    for (const auto& [marker, type] : flash_markers) {
        if (has_aligned_marker(rom, marker)) return type;
    }
    // La EEPROM va antes que la SRAM, como en la versión anterior de esta
    // detección: su versión es lo que aporta, no su mera presencia.
    if (const auto eeprom = detect_eeprom(rom)) return *eeprom;
    constexpr std::array sram_markers{
        std::pair{std::string_view{"SRAM_F_V"}, GbaSaveType::sram},
        std::pair{std::string_view{"SRAM_V"}, GbaSaveType::sram},
    };
    for (const auto& [marker, type] : sram_markers) {
        if (has_aligned_marker(rom, marker)) return type;
    }
    return GbaSaveType::none;
}

} // namespace ravenemu::gba
