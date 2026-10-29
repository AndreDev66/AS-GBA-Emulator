#pragma once

#include "cartridge/mbc0.hpp"
#include "cartridge/mbc1.hpp"
#include "cartridge/mbc2.hpp"
#include "cartridge/mbc3.hpp"
#include "cartridge/mbc5.hpp"
#include "cartridge/mbc6.hpp"
#include "cartridge/mbc7.hpp"
#include "cartridge/mmm01.hpp"
#include "cartridge/huc1.hpp"
#include "cartridge/huc3.hpp"

namespace ravenemu::cgb {

// Seul point qui connaît la liste des contrôleurs : le type lu dans l'en-tête
// décide, et une cartouche non gérée est refusée plutôt que muette.
inline
std::unique_ptr<Cartridge> Cartridge::create(RomImage rom, Clock clock) {
    const auto header = CartridgeHeader::parse(*rom);
    switch (header.mbc) {
    case MbcType::none: return std::make_unique<Mbc0>(std::move(rom), header);
    case MbcType::mbc1: return std::make_unique<Mbc1>(std::move(rom), header);
    case MbcType::mmm01: return std::make_unique<Mmm01>(std::move(rom), header);
    case MbcType::mbc2: return std::make_unique<Mbc2>(std::move(rom), header);
    case MbcType::mbc3: return std::make_unique<Mbc3>(std::move(rom), header, std::move(clock));
    case MbcType::mbc5: return std::make_unique<Mbc5>(std::move(rom), header);
    case MbcType::mbc6: return std::make_unique<Mbc6>(std::move(rom), header);
    case MbcType::mbc7: return std::make_unique<Mbc7>(std::move(rom), header);
    case MbcType::huc1: return std::make_unique<Huc1>(std::move(rom), header);
    case MbcType::huc3: return std::make_unique<Huc3>(std::move(rom), header, std::move(clock));
    case MbcType::unsupported:
        throw RomLoadError("Type de cartouche non pris en charge");
    }
    throw RomLoadError("Type de cartouche non pris en charge");
}

} // namespace ravenemu::cgb
