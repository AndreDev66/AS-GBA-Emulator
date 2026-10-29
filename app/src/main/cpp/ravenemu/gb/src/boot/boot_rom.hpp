#pragma once

#include <ravenemu/binary_io.hpp>
#include <ravenemu/gb/hardware_mode.hpp>
#include <ravenemu/sha256.hpp>

namespace ravenemu::cgb {

/**
 * Boot ROM optionnelle fournie par l'utilisateur.
 *
 * Aucune image n'est embarquée dans RavenEmu. Le contrôleur ne connaît que le
 * mapping matériel et le verrou irréversible FF50 jusqu'au prochain reset.
 */
class BootRom {
public:
    BootRom(gb::HardwareMode hardware_mode, std::span<const std::uint8_t> image)
        : hardware_mode_(hardware_mode), image_(image.begin(), image.end()) {
        if (image_.empty()) return;
        const bool valid_cgb = gb::is_cgb_hardware(hardware_mode_) &&
            (image_.size() == cgb_size || image_.size() == cgb_mapped_size);
        const bool valid_dmg = !gb::is_cgb_hardware(hardware_mode_) && image_.size() == dmg_size;
        if (!valid_cgb && !valid_dmg) {
            throw RomLoadError(gb::is_cgb_hardware(hardware_mode_)
                ? "La boot ROM CGB doit contenir 2048 octets compacts ou 2304 octets mappes"
                : "La boot ROM DMG doit contenir exactement 256 octets");
        }
        image_hash_ = detail::sha256(image_);
        mapped_ = true;
    }

    [[nodiscard]] bool supplied() const noexcept { return !image_.empty(); }
    [[nodiscard]] bool mapped() const noexcept { return mapped_; }

    [[nodiscard]] bool contains(int address) const noexcept {
        if (!mapped_ || address < 0) return false;
        if (address <= 0x00ff) return true;
        return gb::is_cgb_hardware(hardware_mode_) && address >= 0x0200 && address <= 0x08ff;
    }

    [[nodiscard]] int read(int address) const noexcept {
        const bool mapped_layout = image_.size() == cgb_mapped_size;
        const int index = address <= 0x00ff || mapped_layout ? address : address - 0x0100;
        return image_[static_cast<std::size_t>(index)];
    }

    void write_ff50(int value) noexcept {
        if (mapped_ && (value & 0xff) != 0) mapped_ = false;
    }

    void save(detail::BinaryWriter& out) const {
        out.boolean(supplied());
        if (supplied()) out.raw(image_hash_);
        out.boolean(mapped_);
    }

    void load(detail::BinaryReader& in) {
        if (in.boolean() != supplied()) {
            throw SaveStateError("État de boot ROM incompatible avec la configuration actuelle");
        }
        if (supplied()) {
            std::array<std::uint8_t, 32> state_hash{};
            in.raw(state_hash);
            if (state_hash != image_hash_) {
                throw SaveStateError("État issu d'une autre boot ROM");
            }
        }
        const bool state_mapped = in.boolean();
        if (state_mapped && !supplied()) {
            throw SaveStateError("État demandant une boot ROM absente");
        }
        mapped_ = state_mapped;
    }

    static constexpr std::size_t dmg_size = 0x100;
    static constexpr std::size_t cgb_size = 0x800;
    static constexpr std::size_t cgb_mapped_size = 0x900;

private:
    gb::HardwareMode hardware_mode_{gb::HardwareMode::dmg};
    std::vector<std::uint8_t> image_;
    std::array<std::uint8_t, 32> image_hash_{};
    bool mapped_{};
};

} // namespace ravenemu::cgb
