#pragma once

#include "cartridge/gpio.hpp"
#include "save/save_factory.hpp"

namespace ravenemu::gba {

class Cartridge {
public:
    static constexpr std::size_t max_rom_size = 0x0200'0000U;
    Cartridge(
        RomImage rom,
        std::optional<GbaSaveType> forced,
        std::optional<bool> forced_rtc,
        Rtc::Clock clock
    )
        : rom_(std::move(rom)),
          save_type_(forced.value_or(detect_save(std::span<const std::uint8_t>{*rom_}))),
          save_(make_save(save_type_)) {
        const auto view = std::span<const std::uint8_t>{*rom_};
        if (view.size() > max_rom_size) throw RomLoadError("ROM GBA trop volumineuse");
        if (view.size() < 0xc0U) throw RomLoadError("ROM GBA trop courte");
        if (view[0xb2] != 0x96U) throw RomLoadError("Marqueur GBA 0x96 absent");
        // La détection cherche la bibliothèque Seiko que les cartouches d'origine
        // embarquent. Elle ne peut rien affirmer d'une ROM modifiée : un jeu peut
        // piloter le composant sans porter cette chaîne, et une ROM peut la
        // contenir sans que le matériel soit présent. Le choix explicite de
        // l'appelant prime donc, dans les deux sens.
        constexpr std::string_view rtc_marker = "SIIRTC_V";
        rtc_detected_ = std::search(view.begin(), view.end(), rtc_marker.begin(), rtc_marker.end()) != view.end();
        if (forced_rtc.value_or(rtc_detected_)) {
            gpio_ = std::make_unique<Gpio>(std::move(clock));
        }
    }
    int read8(int offset) const noexcept {
        const auto index = static_cast<std::size_t>(offset);
        return index < rom_->size() ? (*rom_)[index] : 0;
    }
    [[nodiscard]] GbaSaveType save_type() const noexcept { return save_type_; }
    [[nodiscard]] SaveMemory* save() noexcept { return save_.get(); }
    [[nodiscard]] const SaveMemory* save() const noexcept { return save_.get(); }
    [[nodiscard]] Gpio* gpio() noexcept { return gpio_.get(); }
    [[nodiscard]] const Gpio* gpio() const noexcept { return gpio_.get(); }
    /** Vrai si la ROM porte la signature de la bibliothèque Seiko. */
    [[nodiscard]] bool rtc_detected() const noexcept { return rtc_detected_; }

    /**
     * Guarda la partida recibida para un momento en que aún no hay memoria.
     *
     * Sin esto, abrir una ROM cuyo tipo de guardado no se reconoce tiraba los
     * bytes venidos del disco: la partida existía, pero no había dónde ponerla.
     */
    void adopt_loaded_battery(std::span<const std::uint8_t> battery) {
        pending_battery_.assign(battery.begin(), battery.end());
    }
    [[nodiscard]] const std::vector<std::uint8_t>& pending_battery() const noexcept { return pending_battery_; }

    /**
     * Convierte una captura en SRAM de verdad, y devuelve el puntero sólo si
     * acaba de crearla.
     *
     * Una ROM sin identificador no puede decirnos si guarda. Si el juego
     * escribe en la ventana de la SRAM es que sí guarda, y quedarse con los
     * bytes en un búfer que nadie vuelca a disco es exactamente cómo se
     * pierde una partida: el juego cree que ha guardado y no es cierto. Aquí la
     * memoria pasa a existir, con lo que ya se había capturado del juego y lo
     * que había del disco, y a partir de ahí se persiste como cualquier otra.
     */
    Sram* promote_untyped_sram(std::span<const std::uint8_t> captured) {
        if (save_) return nullptr;
        auto sram = std::make_unique<Sram>();
        // Primero lo que venía del disco, y encima lo que el juego escribió
        // esta sesión: sobre una partida anterior, el juego manda.
        if (!pending_battery_.empty()) sram->import(pending_battery_);
        auto& target = sram->data();
        const auto count = std::min(captured.size(), target.size());
        std::copy_n(captured.begin(), static_cast<std::ptrdiff_t>(count), target.begin());
        // Lo capturado viene del juego, no del disco: está pendiente de guardar.
        sram->mark_modified();
        save_ = std::move(sram);
        save_type_ = GbaSaveType::sram;
        return static_cast<Sram*>(save_.get());
    }

private:
    RomImage rom_;
    GbaSaveType save_type_;
    std::unique_ptr<SaveMemory> save_;
    std::unique_ptr<Gpio> gpio_;
    std::vector<std::uint8_t> pending_battery_;
    bool rtc_detected_{};
};

} // namespace ravenemu::gba
