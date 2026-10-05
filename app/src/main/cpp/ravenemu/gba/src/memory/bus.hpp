#pragma once

#include "memory/memory_timing.hpp"
#include "cartridge/cartridge.hpp"
#include "interrupts/interrupt_controller.hpp"
#include "input/keypad.hpp"
#include "diagnostics/diagnostics.hpp"

namespace ravenemu::gba {

class Ppu;
class Timers;
class DmaController;
class Apu;

int ppu_dispstat_low(const Ppu*) noexcept;
int ppu_vcount(const Ppu*) noexcept;
void ppu_affine_reference_write(Ppu*, int) noexcept;
void dma_control_write(DmaController*, int, int);

class Bus {
public:
    explicit Bus(Cartridge& cartridge);

    int read8(std::int32_t address);
    int read16(std::int32_t address);
    std::int32_t read32(std::int32_t address);
    int fetch16(std::int32_t address);
    std::int32_t fetch32(std::int32_t address);
    void write8(std::int32_t address, int value);
    void write16(std::int32_t address, int value);
    void write32(std::int32_t address, std::int32_t value);
    int take_wait_cycles() noexcept { return std::exchange(wait_cycles, 0); }
    void break_access_sequence() noexcept { next_sequential_address = -1; }
    [[nodiscard]] std::int32_t sequential_address() const noexcept { return next_sequential_address; }
    void restore_sequential_address(std::int32_t address) noexcept { next_sequential_address = address; }
    void reset_affine_matrices();
    void sync_timing_from_io();
    [[nodiscard]] Eeprom* eeprom() noexcept { return dynamic_cast<Eeprom*>(cartridge.save()); }
    [[nodiscard]] SaveMemory* save_memory() noexcept { return cartridge.save(); }

    Cartridge& cartridge;
    Keypad keypad;
    std::array<std::uint8_t, 0x4000> bios{};
    std::array<std::uint8_t, 0x40000> ewram{};
    std::array<std::uint8_t, 0x8000> iwram{};
    std::array<std::uint8_t, 0x400> io{};
    std::array<std::uint8_t, 0x400> palette{};
    std::array<std::uint8_t, 0x18000> vram{};
    std::array<std::uint8_t, 0x400> oam{};
    std::array<std::uint8_t, 0x10000> fallback_sram{};
    Ppu* ppu{};
    InterruptController* interrupts{};
    Timers* timers{};
    DmaController* dma{};
    Apu* apu{};
    MemoryTiming timing;
    Diagnostics diagnostics;
    int wait_cycles{};

private:
    static constexpr int io_mask = 0x3ff;
    void account(std::int32_t address, int width, bool fetch = false) noexcept;
    [[nodiscard]] int read8_raw(std::int32_t address);
    void write8_raw(std::int32_t address, int value);
    void write16_raw(std::int32_t address, int value);
    [[nodiscard]] int read_io(int offset);
    [[nodiscard]] int read_timer_byte(int offset);
    [[nodiscard]] int io_half(int offset) const noexcept;
    void write_io_half(int offset, int value) noexcept;
    void propagate_io_write(int offset, std::int32_t value, int bytes);
    void handle_io_write(int offset, int value);
    [[nodiscard]] bool eeprom_window(std::int32_t address) const noexcept;
    [[nodiscard]] bool gpio_register(std::int32_t address) const noexcept;
    [[nodiscard]] static int rom_offset(std::int32_t address) noexcept { return static_cast<int>(u32(address) & 0x01ff'ffffU); }
    [[nodiscard]] static int vram_offset(std::int32_t address) noexcept {
        auto offset = static_cast<int>(u32(address) & 0x1ffffU);
        if (offset >= 0x18000) offset -= 0x8000;
        return offset;
    }
    std::int32_t next_sequential_address{-1};
};

} // namespace ravenemu::gba
