#pragma once

#include "support/bits.hpp"

namespace ravenemu::cgb {

enum class MbcType : std::uint8_t {
    none, mbc1, mmm01, mbc2, mbc3, mbc5, mbc6, mbc7, huc1, huc3, unsupported
};

struct CartridgeHeader {
    int cartridge_type{};
    MbcType mbc{MbcType::unsupported};
    bool has_ram{};
    bool has_battery{};
    bool has_rtc{};
    bool has_flash{};
    int ram_size{};
    bool uses_color{};
    bool requires_color{};
    bool mbc1_multicart{};

    static constexpr std::size_t min_rom_size = 0x8000;
    static constexpr std::size_t max_rom_size = 8U * 1024U * 1024U;

    static CartridgeHeader parse(std::span<const std::uint8_t> rom) {
        if (rom.size() < min_rom_size) {
            throw RomLoadError("ROM trop petite (minimum 32768 octets)");
        }
        if (rom.size() > max_rom_size) {
            throw RomLoadError("ROM trop grande (maximum 8388608 octets)");
        }

        CartridgeHeader header;
        const auto header_base = locate_header(rom);
        header.cartridge_type = rom[header_base + 0x147];
        switch (header.cartridge_type) {
        case 0x00: case 0x08: case 0x09: header.mbc = MbcType::none; break;
        case 0x01: case 0x02: case 0x03: header.mbc = MbcType::mbc1; break;
        case 0x0b: case 0x0c: case 0x0d: header.mbc = MbcType::mmm01; break;
        case 0x05: case 0x06: header.mbc = MbcType::mbc2; break;
        case 0x0f: case 0x10: case 0x11: case 0x12: case 0x13: header.mbc = MbcType::mbc3; break;
        case 0x19: case 0x1a: case 0x1b: case 0x1c: case 0x1d: case 0x1e:
            header.mbc = MbcType::mbc5; break;
        case 0x20: header.mbc = MbcType::mbc6; break;
        case 0x22: header.mbc = MbcType::mbc7; break;
        case 0xfe: header.mbc = MbcType::huc3; break;
        case 0xff: header.mbc = MbcType::huc1; break;
        default: header.mbc = MbcType::unsupported; break;
        }

        switch (header.cartridge_type) {
        case 0x02: case 0x03: case 0x05: case 0x06: case 0x08: case 0x09:
        case 0x0c: case 0x0d:
        case 0x10: case 0x12: case 0x13: case 0x1a: case 0x1b: case 0x1d: case 0x1e:
        case 0xfe: case 0xff:
            header.has_ram = true; break;
        default: break;
        }
        switch (header.cartridge_type) {
        case 0x03: case 0x06: case 0x09: case 0x0d: case 0x0f: case 0x10: case 0x13:
        case 0x1b: case 0x1e: case 0xfe: case 0xff: header.has_battery = true; break;
        default: break;
        }
        header.has_rtc = header.cartridge_type == 0x0f || header.cartridge_type == 0x10 ||
            header.mbc == MbcType::huc3;
        header.has_flash = header.mbc == MbcType::mbc6;
        if (header.mbc == MbcType::huc3) {
            // Les cartes HuC3 documentées portent toujours quatre banques de
            // SRAM (32 Kio), en plus de l'état du MCU alimenté par la pile.
            header.has_ram = true;
            header.has_battery = true;
            header.ram_size = 32 * 1024;
        } else if (header.mbc == MbcType::mbc7) {
            header.has_ram = true;
            header.has_battery = true;
            header.ram_size = 256;
        } else if (header.mbc == MbcType::mbc6) {
            // Le type $20 n'a pas de variantes d'en-tête +RAM/+BATTERY : le
            // matériel MBC6 porte toujours 32 Kio de SRAM et 1 Mio de flash.
            header.has_ram = true;
            header.has_battery = true;
            header.ram_size = 32 * 1024;
        } else if (header.mbc == MbcType::mbc2) {
            header.has_ram = true;
            header.ram_size = 512;
        } else if (header.has_ram) {
            switch (rom[header_base + 0x149]) {
            case 0x01: header.ram_size = 2 * 1024; break;
            case 0x02: header.ram_size = 8 * 1024; break;
            case 0x03: header.ram_size = 32 * 1024; break;
            case 0x04: header.ram_size = 128 * 1024; break;
            case 0x05: header.ram_size = 64 * 1024; break;
            default: header.ram_size = 0; break;
            }
        }
        const auto cgb_flag = rom[header_base + 0x143];
        header.uses_color = cgb_flag == 0x80 || cgb_flag == 0xc0;
        header.requires_color = cgb_flag == 0xc0;
        // MBC1M n'a pas de type d'en-tête distinct. Son câblage 1 Mio se
        // reconnaît à un second en-tête autonome dans la banque $10. Le
        // checksum suffit ici : RavenEmu ne stocke ni ne compare le logo
        // protégé présent dans les cartouches commerciales.
        header.mbc1_multicart = header.mbc == MbcType::mbc1 && rom.size() == 0x100000 &&
            valid_header_checksum(rom, 0) && valid_header_checksum(rom, 0x40000);
        return header;
    }

private:
    static bool is_mmm01_type(int type) noexcept {
        return type >= 0x0b && type <= 0x0d;
    }

    static std::size_t locate_header(std::span<const std::uint8_t> rom) noexcept {
        // Au reset, MMM01 présente les 32 derniers Kio de la ROM : son en-tête
        // matériel se trouve donc à la fin de l'image et non nécessairement à
        // l'offset zéro. Le checksum évite de confondre une donnée quelconque
        // de la dernière sous-ROM avec un en-tête MMM01.
        const std::size_t tail_base = rom.size() - min_rom_size;
        if (is_mmm01_type(rom[tail_base + 0x147]) &&
            valid_header_checksum(rom, tail_base)) {
            return tail_base;
        }
        return 0;
    }

    static bool valid_header_checksum(std::span<const std::uint8_t> rom,
                                      std::size_t base) noexcept {
        if (base + 0x14d >= rom.size()) return false;
        std::uint8_t checksum{};
        for (std::size_t offset = 0x134; offset <= 0x14c; ++offset) {
            checksum = static_cast<std::uint8_t>(checksum - rom[base + offset] - 1U);
        }
        return checksum == rom[base + 0x14d];
    }
};

} // namespace ravenemu::cgb
