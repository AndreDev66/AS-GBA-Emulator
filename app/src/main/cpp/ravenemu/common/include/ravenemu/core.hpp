#pragma once

#include <ravenemu/link_endpoint.hpp>
#include <ravenemu/infrared_endpoint.hpp>

#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace ravenemu {

enum class Console : std::uint8_t {
    game_boy = 0,
    game_boy_advance = 2,
};

enum class Button : std::uint8_t {
    up,
    down,
    left,
    right,
    a,
    b,
    start,
    select,
    l,
    r,
};

enum class FramebufferFormat : std::uint8_t {
    argb_8888,
    indexed_4,
};

enum class GbaSaveType : std::uint8_t {
    none,
    sram,
    flash_64k,
    flash_128k,
    eeprom_512,
    eeprom_8k,
};

struct VideoSpec {
    int width{};
    int height{};
    double refresh_rate_hz{};

    [[nodiscard]] std::size_t pixel_count() const noexcept {
        return static_cast<std::size_t>(width) * static_cast<std::size_t>(height);
    }
};

struct AudioSpec {
    int sample_rate_hz{};
    int channel_count{};
};

struct BatterySnapshot {
    std::vector<std::uint8_t> data;
    std::uint64_t generation{};
};

enum class DiagnosticEvent : std::uint8_t {
    unsupported_swi,
    undefined_instruction,
    unsupported_access,
    missing_interrupt,
    decompression_error,
};

struct DiagnosticMessage {
    DiagnosticEvent event{};
    std::string detail;
};

/** POD mirror of the Kotlin GbaDebugSnapshot contract. */
struct GbaDebugSnapshot {
    std::int32_t instructions_per_frame{};
    std::int32_t program_counter{};
    bool thumb{};
    bool halted{};
    std::int32_t last_swi{-1};
    std::int32_t last_interrupt_mask{};
    std::int32_t vcount{};
    std::int32_t last_dma_channel{-1};
    bool dma_active{};
    std::int32_t fifo_a_size{};
    std::int32_t fifo_b_size{};
    std::int32_t fifo_a_empty_reads{};
    std::int32_t fifo_b_empty_reads{};
    std::int32_t audio_underruns{};
    std::int32_t unsupported_swi_count{};
    std::int32_t undefined_instruction_count{};
    std::int32_t unsupported_access_count{};
    std::int32_t missing_interrupt_count{};
    std::int32_t decompression_error_count{};
    std::int32_t first_unsupported_address{};
    std::int32_t dispcnt{};
    std::int32_t bg0_control{};
    std::int32_t bg1_control{};
    std::int32_t bg2_control{};
    std::int32_t bg3_control{};
    std::int32_t blend_control{};
    std::int32_t blend_alpha{};
    std::int32_t blend_brightness{};
    std::int32_t window_inside{};
    std::int32_t window_outside{};
    std::int32_t luma_min{};
    std::int32_t luma_max{};
    std::int32_t luma_mean{};
    std::vector<std::int32_t> layer_pixels;
    std::int32_t bg2_reference_x{};
    std::int32_t bg2_reference_y{};
    std::int32_t bg2_scale_x{};
    std::int32_t bg2_scale_y{};
    std::int32_t bg2_matrix_writes{};
    std::int32_t bg2_reference_writes{};
    std::vector<std::int32_t> swi_counts;
    double ppu_millis{};
    double dma_millis{};
    double apu_millis{};
};

class Error : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

class RomLoadError final : public Error {
public:
    using Error::Error;
};

class SaveStateError final : public Error {
public:
    using Error::Error;
};

class Core {
public:
    virtual ~Core() = default;

    [[nodiscard]] virtual Console console() const noexcept = 0;
    [[nodiscard]] virtual VideoSpec video_spec() const noexcept = 0;
    [[nodiscard]] virtual AudioSpec audio_spec() const noexcept = 0;
    [[nodiscard]] virtual FramebufferFormat framebuffer_format() const noexcept = 0;
    [[nodiscard]] virtual bool supports_video_frame_skipping() const noexcept { return false; }

    virtual void load_rom(
        std::span<const std::uint8_t> rom,
        std::span<const std::uint8_t> battery_ram
    ) = 0;
    virtual void reset() = 0;
    virtual void run_frame(std::span<std::int32_t> framebuffer, bool render_video) = 0;
    virtual void set_button(Button button, bool pressed) = 0;
    virtual std::size_t read_audio(std::span<std::int16_t> destination) = 0;
    [[nodiscard]] virtual bool connect_link_endpoint(LinkEndpoint*) noexcept { return false; }
    [[nodiscard]] virtual bool connect_infrared_endpoint(InfraredEndpoint*) noexcept { return false; }
    [[nodiscard]] virtual bool rumble_active() const noexcept { return false; }
    /** Inclinaison cartouche en unités brutes autour du repos (0, 0). */
    virtual void set_game_boy_acceleration(int, int) noexcept {}

    [[nodiscard]] virtual bool has_battery_ram() const noexcept = 0;
    [[nodiscard]] virtual bool battery_ram_dirty() const noexcept = 0;
    [[nodiscard]] virtual std::optional<BatterySnapshot> snapshot_battery_ram() = 0;
    virtual void acknowledge_battery_ram_saved(std::uint64_t generation) noexcept = 0;

    [[nodiscard]] virtual std::vector<std::uint8_t> save_state() const = 0;
    virtual void load_state(std::span<const std::uint8_t> state) = 0;

    virtual void set_clock_epoch(std::optional<std::int64_t>) noexcept {}
    virtual void set_measuring_time(bool) noexcept {}
    [[nodiscard]] virtual bool measuring_time() const noexcept { return false; }
    [[nodiscard]] virtual std::optional<GbaDebugSnapshot> debug_snapshot() const { return std::nullopt; }
    [[nodiscard]] virtual std::vector<DiagnosticMessage> drain_diagnostics() { return {}; }
    [[nodiscard]] virtual GbaSaveType gba_save_type() const noexcept { return GbaSaveType::none; }
    virtual void set_gba_forced_save_type(std::optional<GbaSaveType>) noexcept {}
    /** Horloge de cartouche réellement présente sur le jeu chargé. */
    [[nodiscard]] virtual bool gba_rtc_active() const noexcept { return false; }
    /**
     * Impose la présence ou l'absence de l'horloge de cartouche, ou rend la
     * main à la détection avec `std::nullopt`. Pris en compte au prochain
     * chargement de ROM ou à la prochaine réinitialisation.
     */
    virtual void set_gba_forced_rtc(std::optional<bool>) noexcept {}
};

[[nodiscard]] std::unique_ptr<Core> make_game_boy_core();
[[nodiscard]] std::unique_ptr<Core> make_gba_core(std::optional<GbaSaveType> forced_save_type);

} // namespace ravenemu
