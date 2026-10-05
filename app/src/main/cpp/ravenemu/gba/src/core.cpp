#include "machine/machine.hpp"

#include "ravenemu/sha256.hpp"

namespace ravenemu {
namespace gba {

class GbaCore final : public Core {
public:
    explicit GbaCore(std::optional<GbaSaveType> forced) : forced_(forced) {}
    [[nodiscard]] Console console() const noexcept override { return Console::game_boy_advance; }
    [[nodiscard]] VideoSpec video_spec() const noexcept override { return {240, 160, 16'777'216.0 / 280'896.0}; }
    [[nodiscard]] AudioSpec audio_spec() const noexcept override { return {Apu::sample_rate, 2}; }
    [[nodiscard]] FramebufferFormat framebuffer_format() const noexcept override { return FramebufferFormat::argb_8888; }
    [[nodiscard]] bool supports_video_frame_skipping() const noexcept override { return true; }

    void load_rom(std::span<const std::uint8_t> rom, std::span<const std::uint8_t> battery) override {
        if (rom.size() > Cartridge::max_rom_size) throw RomLoadError("ROM GBA trop volumineuse");
        if (rom.size() < 0xc0U) throw RomLoadError("ROM GBA trop courte");
        if (rom[0xb2] != 0x96U) throw RomLoadError("Marqueur GBA 0x96 absent");
        auto image = std::make_shared<const std::vector<std::uint8_t>>(rom.begin(), rom.end());
        auto replacement = new_machine(image);
        // Si el tipo aún no se conoce, la partida se guarda aparte en vez de
        // tirarse: en cuanto el juego escriba, habrá dónde ponerla.
        replacement->cartridge.adopt_loaded_battery(battery);
        if (!battery.empty() && replacement->cartridge.save()) replacement->cartridge.save()->import(battery);
        loaded_rom_ = std::move(image); rom_hash_ = detail::sha256(rom); machine_ = std::move(replacement);
    }
    void reset() override {
        require_loaded(); std::vector<std::uint8_t> battery;
        if (const auto* save = machine_->cartridge.save()) battery = save->data();
        const auto pending = machine_->cartridge.pending_battery();
        auto replacement = new_machine(loaded_rom_);
        replacement->cartridge.adopt_loaded_battery(pending);
        if (!battery.empty() && replacement->cartridge.save()) replacement->cartridge.save()->import(battery);
        configure_measurement(*replacement, measuring_time_); machine_ = std::move(replacement);
    }
    void run_frame(std::span<std::int32_t> framebuffer, bool render_video) override {
        require_loaded();
        if (framebuffer.size() < Ppu::screen_width * Ppu::screen_height) throw std::invalid_argument("Framebuffer trop petit");
        machine_->ppu.render_enabled = render_video;
        try { machine_->run_frame(); } catch (...) { machine_->ppu.render_enabled = true; throw; }
        machine_->ppu.render_enabled = true;
        if (render_video) std::copy(machine_->ppu.frame.begin(), machine_->ppu.frame.end(), framebuffer.begin());
    }
    void set_button(Button button, bool pressed) override { if (machine_) machine_->bus.keypad.set_button(button, pressed); }
    std::size_t read_audio(std::span<std::int16_t> destination) override {
        return machine_ ? machine_->apu.read_samples(destination) : 0;
    }
    [[nodiscard]] bool has_battery_ram() const noexcept override {
        return machine_ && machine_->cartridge.save();
    }
    [[nodiscard]] bool battery_ram_dirty() const noexcept override {
        return machine_ && machine_->cartridge.save() && machine_->cartridge.save()->dirty();
    }
    std::optional<BatterySnapshot> snapshot_battery_ram() override {
        if (!machine_ || !machine_->cartridge.save()) return std::nullopt;
        const auto* save = machine_->cartridge.save(); return BatterySnapshot{save->data(), save->generation()};
    }
    void acknowledge_battery_ram_saved(std::uint64_t generation) noexcept override {
        if (machine_ && machine_->cartridge.save()) machine_->cartridge.save()->acknowledge(generation);
    }
    [[nodiscard]] GbaSaveType gba_save_type() const noexcept override {
        return machine_ ? machine_->cartridge.save_type() : GbaSaveType::none;
    }
    void set_gba_forced_save_type(std::optional<GbaSaveType> value) noexcept override {
        forced_ = value;
    }
    /**
     * Horloge réellement présente sur la cartouche émulée.
     *
     * Distinct du réglage : il dit ce que l'appelant demande, celui-ci ce que le
     * jeu voit. C'est cette valeur-là qu'une interface doit montrer.
     */
    [[nodiscard]] bool gba_rtc_active() const noexcept override {
        return machine_ != nullptr && machine_->cartridge.gpio() != nullptr;
    }
    void set_gba_forced_rtc(std::optional<bool> value) noexcept override {
        forced_rtc_ = value;
    }
    void set_clock_epoch(std::optional<std::int64_t> value) noexcept override { clock_override_ = value; }
    void set_measuring_time(bool value) noexcept override {
        measuring_time_ = value; if (machine_) configure_measurement(*machine_, value);
    }
    [[nodiscard]] bool measuring_time() const noexcept override { return measuring_time_; }
    [[nodiscard]] std::vector<DiagnosticMessage> drain_diagnostics() override {
        return machine_ ? machine_->bus.diagnostics.drain() : std::vector<DiagnosticMessage>{};
    }
    [[nodiscard]] std::optional<GbaDebugSnapshot> debug_snapshot() const override {
        if (!machine_) return std::nullopt;
        const auto& m = *machine_;
        GbaDebugSnapshot result{};
        result.instructions_per_frame = m.bus.diagnostics.instructions_last_frame; result.program_counter = m.cpu.state.regs[15];
        result.thumb = m.cpu.state.thumb; result.halted = m.cpu.state.halted; result.last_swi = m.bus.diagnostics.last_swi;
        result.last_interrupt_mask = m.bus.diagnostics.last_interrupt_mask; result.vcount = m.ppu.vcount;
        result.last_dma_channel = m.dma.last_channel; result.dma_active = m.dma.active();
        result.fifo_a_size = m.apu.fifo_size(0); result.fifo_b_size = m.apu.fifo_size(1);
        result.fifo_a_empty_reads = m.apu.fifo_empty_reads(0); result.fifo_b_empty_reads = m.apu.fifo_empty_reads(1);
        result.audio_underruns = m.apu.underruns; result.unsupported_swi_count = m.bus.diagnostics.count(DiagnosticEvent::unsupported_swi);
        result.undefined_instruction_count = m.bus.diagnostics.count(DiagnosticEvent::undefined_instruction);
        result.unsupported_access_count = m.bus.diagnostics.count(DiagnosticEvent::unsupported_access);
        result.missing_interrupt_count = m.bus.diagnostics.count(DiagnosticEvent::missing_interrupt);
        result.decompression_error_count = m.bus.diagnostics.count(DiagnosticEvent::decompression_error);
        result.first_unsupported_address = m.bus.diagnostics.first_unsupported_address;
        result.dispcnt = io_half(m.bus, 0); result.bg0_control = io_half(m.bus, 8); result.bg1_control = io_half(m.bus, 0x0a);
        result.bg2_control = io_half(m.bus, 0x0c); result.bg3_control = io_half(m.bus, 0x0e);
        result.blend_control = io_half(m.bus, 0x50); result.blend_alpha = io_half(m.bus, 0x52);
        result.blend_brightness = io_half(m.bus, 0x54); result.window_inside = io_half(m.bus, 0x48); result.window_outside = io_half(m.bus, 0x4a);
        result.luma_min = m.ppu.frame_luma_min; result.luma_max = m.ppu.frame_luma_max; result.luma_mean = m.ppu.frame_luma_mean;
        result.layer_pixels.assign(m.ppu.layer_pixels.begin(), m.ppu.layer_pixels.end());
        result.bg2_reference_x = signed28(io_word(m.bus, 0x28)) >> 8; result.bg2_reference_y = signed28(io_word(m.bus, 0x2c)) >> 8;
        result.bg2_scale_x = io_half(m.bus, 0x20); result.bg2_scale_y = io_half(m.bus, 0x26);
        result.bg2_matrix_writes = m.bus.diagnostics.bg2_matrix_writes; result.bg2_reference_writes = m.bus.diagnostics.bg2_reference_writes;
        result.swi_counts.assign(m.bus.diagnostics.swi_counts.begin(), m.bus.diagnostics.swi_counts.end());
        result.ppu_millis = static_cast<double>(m.bus.diagnostics.ppu_nanos_last_frame) / 1'000'000.0;
        result.dma_millis = static_cast<double>(m.bus.diagnostics.dma_nanos_last_frame) / 1'000'000.0;
        result.apu_millis = static_cast<double>(m.bus.diagnostics.apu_nanos_last_frame) / 1'000'000.0;
        return result;
    }

    [[nodiscard]] std::vector<std::uint8_t> save_state() const override {
        require_loaded(); auto& m = *machine_; BinaryWriter out(768U * 1024U);
        out.u32(0x52564e53U); out.u16(11); out.u8(2); out.raw(rom_hash_);
        const auto banks = m.cpu.state.export_banks(); for (const auto value : m.cpu.state.regs) out.i32(value);
        out.i32(m.cpu.state.cpsr()); out.boolean(m.cpu.state.halted); out.i32(static_cast<int>(banks.size()));
        for (const auto value : banks) out.i32(value);
        out.raw(m.bus.ewram); out.raw(m.bus.iwram); out.raw(m.bus.io); out.raw(m.bus.palette);
        out.raw(m.bus.vram); out.raw(m.bus.oam); out.raw(m.bus.fallback_sram); out.i32(m.bus.keypad.pressed_bits);
        const auto ppu_state = m.ppu.state_fields(); out.i32(static_cast<int>(ppu_state.size()));
        for (const auto value : ppu_state) out.i32(value);
        for (const auto pixel : m.ppu.frame) out.i32(pixel);
        out.i32(m.interrupts.enable); out.i32(m.interrupts.flags); out.boolean(m.interrupts.master_enable);
        for (const auto value : m.timers.export_state()) out.i32(value);
        for (const auto value : m.dma.export_state()) out.i32(value);
        // L'APU avant tout le reste : sans lui, l'état décrit une console
        // muette dont le jeu, lui, croyait le son en marche depuis le
        // démarrage — et il n'y réécrit plus rien pour la rattraper.
        for (const auto value : m.apu.export_state()) out.i32(value);
        const auto& wait = m.bios.wait_state(); out.boolean(wait.has_value()); out.i32(wait ? wait->interrupt_mask : 0); out.boolean(wait && wait->discard_old_flags);
        const auto* save = m.cartridge.save(); out.i32(save ? static_cast<int>(save->data().size()) : 0); if (save) out.raw(save->data());
        const auto* gpio = m.cartridge.gpio(); out.boolean(gpio != nullptr); if (gpio) for (const auto value : gpio->export_state()) out.i32(value);
        out.i32(m.bus.wait_cycles); out.i32(m.bus.sequential_address());
        return std::move(out).take();
    }
    void load_state(std::span<const std::uint8_t> bytes) override {
        require_loaded(); if (bytes.size() > (1U << 20U)) throw SaveStateError("État GBA trop volumineux");
        BinaryReader in(bytes); if (in.u32() != 0x52564e53U) throw SaveStateError("Ce fichier n'est pas un état RavenEmu");
        const auto version = in.u16();
        if (version != 9 && version != 10 && version != 11) throw SaveStateError("Version d'état GBA non prise en charge");
        if (in.u8() != 2) throw SaveStateError("État issu d'une autre console");
        std::array<std::uint8_t, 32> hash{}; in.raw(hash); if (hash != rom_hash_) throw SaveStateError("État issu d'une autre ROM");
        auto replacement = new_machine(loaded_rom_); auto& m = *replacement;
        std::array<std::int32_t, 16> registers{}; for (auto& value : registers) value = in.i32();
        const auto cpsr = in.i32(); m.cpu.state.halted = in.boolean(); if (in.i32() != 28) throw SaveStateError("État GBA corrompu (banques)");
        std::array<std::int32_t, 28> banks{}; for (auto& value : banks) value = in.i32(); m.cpu.state.import_banks(banks); m.cpu.state.set_control_raw(cpsr); m.cpu.state.regs = registers;
        read_array(in, m.bus.ewram); read_array(in, m.bus.iwram); read_array(in, m.bus.io); m.bus.sync_timing_from_io();
        read_array(in, m.bus.palette); read_array(in, m.bus.vram); read_array(in, m.bus.oam); read_array(in, m.bus.fallback_sram); m.bus.keypad.pressed_bits = in.i32();
        if (in.i32() != Ppu::state_field_count) throw SaveStateError("État GBA corrompu (PPU)");
        std::array<std::int32_t, Ppu::state_field_count> ppu_state{}; for (auto& value : ppu_state) value = in.i32(); m.ppu.restore_state(ppu_state);
        for (auto& pixel : m.ppu.frame) pixel = in.i32();
        m.interrupts.enable = in.i32();
        m.interrupts.flags = in.i32();
        m.interrupts.master_enable = in.boolean();
        std::array<std::int32_t, 16> timer_state{}; for (auto& value : timer_state) value = in.i32(); m.timers.import_state(timer_state);
        std::array<std::int32_t, DmaController::state_words> dma_state{};
        for (auto& value : dma_state) value = in.i32();
        m.dma.import_state(dma_state);
        // Le même ordre que dans save_state : un seul mot d'écart ici ferait lire à
        // chaque champ suivant celui de son voisin.
        if (version >= 10) {
            std::array<std::int32_t, Apu::state_words> apu_state{}; for (auto& value : apu_state) value = in.i32();
            m.apu.import_state(apu_state);
        } else {
            // État 9 : l'APU n'y figurait pas. La machine neuve sort du silence
            // alors que le jeu croit le son actif depuis le démarrage, et
            // n'écrira plus SOUNDCNT_X pour le redresser — il se tait donc
            // jusqu'à la fin. Rejouer les écritures de son encore présentes
            // dans le bus ne restitue pas la phase des canaux, mais remet la
            // sortie dans l'état attendu : le son repart à la note suivante.
            m.apu.replay_from_io(m.bus);
        }
        const auto waiting = in.boolean(); const auto wait_mask = in.i32(); const auto wait_discard = in.boolean();
        m.bios.restore_wait_state(waiting ? std::optional{Bios::WaitState{wait_mask, wait_discard}} : std::nullopt);
        const auto save_size = in.i32(); const auto expected_size = m.cartridge.save() ? static_cast<int>(m.cartridge.save()->data().size()) : 0;
        if (save_size != expected_size) throw SaveStateError("État GBA corrompu (sauvegarde)");
        if (save_size > 0) m.cartridge.save()->import(in.raw(static_cast<std::size_t>(save_size)));
        auto* gpio = m.cartridge.gpio(); if (in.boolean() != (gpio != nullptr)) throw SaveStateError("État GBA corrompu (GPIO)");
        if (gpio) { std::array<std::int32_t, Gpio::state_words> gpio_state{}; for (auto& value : gpio_state) value = in.i32(); gpio->import_state(gpio_state); }
        if (version >= 11) { m.bus.wait_cycles = in.i32(); m.bus.restore_sequential_address(in.i32()); }
        if (!in.exhausted()) throw SaveStateError("État GBA corrompu (données excédentaires)");
        configure_measurement(m, measuring_time_); machine_ = std::move(replacement);
    }

private:
    template <std::size_t N>
    static void read_array(BinaryReader& in, std::array<std::uint8_t, N>& output) { in.raw(std::span<std::uint8_t>{output}); }
    static int io_half(const Bus& bus, int offset) noexcept {
        return bus.io[static_cast<std::size_t>(offset)] | bus.io[static_cast<std::size_t>(offset + 1)] << 8;
    }
    static std::int32_t io_word(const Bus& bus, int offset) noexcept {
        return i32(static_cast<std::uint32_t>(io_half(bus, offset)) | static_cast<std::uint32_t>(io_half(bus, offset + 2)) << 16U);
    }
    static std::int32_t signed28(std::int32_t value) noexcept { return sign_extend(u32(value) & 0x0fff'ffffU, 28); }
    void configure_measurement(Machine& machine, bool value) const noexcept {
        machine.bus.diagnostics.measuring_time = value; machine.apu.set_measuring(value);
        machine.apu.on_batch_nanos = [&machine](std::int64_t nanos) { machine.bus.diagnostics.apu_nanos += nanos; };
        machine.ppu.collect_layer_stats = value; machine.ppu.collect_frame_stats = value;
    }
    [[nodiscard]] std::int64_t current_epoch() const noexcept {
        if (clock_override_) return *clock_override_;
        return std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count();
    }
    [[nodiscard]] std::unique_ptr<Machine> new_machine(RomImage rom) const {
        return std::make_unique<Machine>(rom, forced_, forced_rtc_, [this] { return current_epoch(); });
    }
    void require_loaded() const { if (!machine_) throw std::logic_error("Aucune ROM chargée"); }

    std::optional<GbaSaveType> forced_;
    std::optional<bool> forced_rtc_;
    std::unique_ptr<Machine> machine_;
    RomImage loaded_rom_;
    std::array<std::uint8_t, 32> rom_hash_{};
    std::optional<std::int64_t> clock_override_;
    bool measuring_time_{};
};


} // namespace gba


std::unique_ptr<Core> make_gba_core(std::optional<GbaSaveType> forced_save_type) {
    return std::make_unique<gba::GbaCore>(forced_save_type);
}
} // namespace ravenemu
