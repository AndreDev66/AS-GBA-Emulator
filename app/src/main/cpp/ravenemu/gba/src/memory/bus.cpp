#include "memory/bus.hpp"

#include "ppu/ppu.hpp"
#include "timers/timers.hpp"
#include "dma/dma_controller.hpp"
#include "apu/apu.hpp"

namespace ravenemu::gba {

void Bus::account(std::int32_t address, int width, bool fetch) noexcept {
    const auto sequential = address == next_sequential_address;
    wait_cycles += fetch ? timing.instruction_wait(address, width, sequential)
                         : timing.wait_states(address, width, sequential);
    next_sequential_address = add32(address, width);
}

bool Bus::eeprom_window(std::int32_t address) const noexcept {
    return dynamic_cast<const Eeprom*>(cartridge.save()) != nullptr && (u32(address) >> 24U) == 0x0dU;
}

bool Bus::gpio_register(std::int32_t address) const noexcept {
    const auto region = (u32(address) >> 24U) & 0xffU;
    return region >= 0x08U && region <= 0x0dU && Gpio::covers(rom_offset(address));
}

int Bus::read_io(int offset) {
    if (offset == 0x130) return keypad.input() & 0xff;
    if (offset == 0x131) return (keypad.input() >> 8) & 0xff;
    if (offset == 0x004 && ppu) return ppu_dispstat_low(ppu);
    if (offset == 0x006 && ppu) return ppu_vcount(ppu);
    if (offset == 0x007) return 0;
    if (timers && offset >= 0x100 && offset <= 0x10f) return read_timer_byte(offset);
    if (interrupts) {
        if (offset == 0x200) return interrupts->enable & 0xff;
        if (offset == 0x201) return (interrupts->enable >> 8) & 0xff;
        if (offset == 0x202) return interrupts->flags & 0xff;
        if (offset == 0x203) return (interrupts->flags >> 8) & 0xff;
        if (offset == 0x208) return interrupts->master_enable ? 1 : 0;
        if (offset == 0x209) return 0;
    }
    return io[static_cast<std::size_t>(offset)];
}

int Bus::read_timer_byte(int offset) {
    const auto timer = (offset - 0x100) / 4;
    const auto byte = (offset - 0x100) % 4;
    const auto value = byte < 2 ? timers->counter(timer) : timers->control(timer);
    return byte % 2 == 0 ? value & 0xff : (value >> 8) & 0xff;
}

int Bus::read8_raw(std::int32_t address) {
    const auto raw = u32(address);
    const auto region = (raw >> 24U) & 0xffU;
    switch (region) {
    case 0x00: case 0x01: return bios[raw & 0x3fffU];
    case 0x02: return ewram[raw & 0x3ffffU];
    case 0x03: return iwram[raw & 0x7fffU];
    case 0x04: return read_io(static_cast<int>(raw & 0x3ffU));
    case 0x05: return palette[raw & 0x3ffU];
    case 0x06: return vram[static_cast<std::size_t>(vram_offset(address))];
    case 0x07: return oam[raw & 0x3ffU];
    case 0x08: case 0x09: case 0x0a: case 0x0b: case 0x0c: case 0x0d: {
        auto* port = cartridge.gpio();
        if (port && port->readable() && gpio_register(address)) {
            const auto offset = rom_offset(address);
            const auto half = port->read16(offset & ~1);
            return (offset & 1) == 0 ? half & 0xff : (half >> 8) & 0xff;
        }
        return cartridge.read8(rom_offset(address));
    }
    case 0x0e: case 0x0f: {
        auto* save = cartridge.save();
        if (dynamic_cast<Sram*>(save) || dynamic_cast<Flash*>(save)) return save->read(static_cast<int>(raw));
        return fallback_sram[raw & 0xffffU];
    }
    default:
        diagnostics.unsupported_access(address);
        return 0;
    }
}

int Bus::read8(std::int32_t address) {
    account(address, 1);
    return read8_raw(address);
}

int Bus::read16(std::int32_t address) {
    const auto aligned = i32(u32(address) & ~1U);
    account(aligned, 2);
    if (auto* memory = eeprom(); memory && eeprom_window(aligned)) return memory->read(0);
    if (auto* port = cartridge.gpio(); port && port->readable() && gpio_register(aligned)) {
        return port->read16(rom_offset(aligned));
    }
    return read8_raw(aligned) | read8_raw(add32(aligned, 1)) << 8;
}

std::int32_t Bus::read32(std::int32_t address) {
    const auto aligned = i32(u32(address) & ~3U);
    account(aligned, 4);
    auto result = static_cast<std::uint32_t>(read8_raw(aligned));
    result |= static_cast<std::uint32_t>(read8_raw(add32(aligned, 1))) << 8U;
    result |= static_cast<std::uint32_t>(read8_raw(add32(aligned, 2))) << 16U;
    result |= static_cast<std::uint32_t>(read8_raw(add32(aligned, 3))) << 24U;
    return i32(result);
}

int Bus::fetch16(std::int32_t address) {
    const auto aligned = i32(u32(address) & ~1U);
    account(aligned, 2, true);
    return read8_raw(aligned) | read8_raw(add32(aligned, 1)) << 8;
}

std::int32_t Bus::fetch32(std::int32_t address) {
    const auto aligned = i32(u32(address) & ~3U);
    account(aligned, 4, true);
    auto result = static_cast<std::uint32_t>(read8_raw(aligned));
    result |= static_cast<std::uint32_t>(read8_raw(add32(aligned, 1))) << 8U;
    result |= static_cast<std::uint32_t>(read8_raw(add32(aligned, 2))) << 16U;
    result |= static_cast<std::uint32_t>(read8_raw(add32(aligned, 3))) << 24U;
    return i32(result);
}

void Bus::write8_raw(std::int32_t address, int value) {
    const auto raw = u32(address);
    const auto region = (raw >> 24U) & 0xffU;
    const auto byte = static_cast<std::uint8_t>(value);
    switch (region) {
    case 0x00: case 0x01: break;
    case 0x02: ewram[raw & 0x3ffffU] = byte; break;
    case 0x03: iwram[raw & 0x7fffU] = byte; break;
    case 0x04: io[raw & 0x3ffU] = byte; break;
    case 0x05: palette[raw & 0x3ffU] = byte; break;
    case 0x06: vram[static_cast<std::size_t>(vram_offset(address))] = byte; break;
    case 0x07: oam[raw & 0x3ffU] = byte; break;
    case 0x08: case 0x09: case 0x0a: case 0x0b: case 0x0c: case 0x0d:
        if (auto* port = cartridge.gpio(); port && gpio_register(address) && (rom_offset(address) & 1) == 0) {
            port->write16(rom_offset(address), value);
        }
        break;
    case 0x0e: case 0x0f: {
        auto* save = cartridge.save();
        if (auto* sram = dynamic_cast<Sram*>(save); sram) {
            sram->write(static_cast<int>(raw), value);
        } else if (auto* flash = dynamic_cast<Flash*>(save); flash) {
            flash->write(static_cast<int>(raw), value);
        } else if (save == nullptr) {
            // Sin tipo reconocido, escribir aquí significa que el juego sí
            // guarda: se le da SRAM de verdad con lo ya capturado en vez de
            // tragarse los bytes.
            fallback_sram[raw & 0xffffU] = byte;
            if (auto* promoted = cartridge.promote_untyped_sram(fallback_sram)) {
                promoted->write(static_cast<int>(raw), value);
            }
        }
        // Con una EEPROM presente el juego no usa esta ventana: sus accesos
        // van por 0x0d y aquí no hay nada que hacer.
        break;
    }
    default: diagnostics.unsupported_access(address); break;
    }
}

void Bus::write8(std::int32_t address, int value) {
    account(address, 1);
    const auto region = (u32(address) >> 24U) & 0xffU;
    if (region == 0x05U || region == 0x06U) {
        const auto aligned = i32(u32(address) & ~1U);
        write8_raw(aligned, value); write8_raw(add32(aligned, 1), value);
    } else if (region == 0x07U) {
        return;
    } else {
        write8_raw(address, value);
        if (region == 0x04U) propagate_io_write(static_cast<int>(u32(address) & 0x3ffU), value, 1);
    }
}

void Bus::write16_raw(std::int32_t address, int value) {
    if (auto* memory = eeprom(); memory && eeprom_window(address)) { memory->write(0, value); return; }
    if (auto* port = cartridge.gpio(); port && gpio_register(address)) {
        port->write16(rom_offset(address), value & 0xffff); return;
    }
    write8_raw(address, value); write8_raw(add32(address, 1), value >> 8);
    if ((u32(address) >> 24U) == 0x04U) propagate_io_write(static_cast<int>(u32(address) & 0x3ffU), value, 2);
}

void Bus::write16(std::int32_t address, int value) {
    const auto aligned = i32(u32(address) & ~1U);
    account(aligned, 2); write16_raw(aligned, value);
}

void Bus::write32(std::int32_t address, std::int32_t value) {
    const auto aligned = i32(u32(address) & ~3U);
    account(aligned, 4);
    if ((u32(aligned) >> 24U) == 0x04U) {
        const auto offset = static_cast<int>(u32(aligned) & 0x3ffU);
        for (unsigned index = 0; index < 4; ++index) {
            io[static_cast<std::size_t>(offset) + index] = static_cast<std::uint8_t>(u32(value) >> (index * 8U));
        }
        propagate_io_write(offset, value, 4);
    } else {
        write16_raw(aligned, static_cast<int>(u32(value) & 0xffffU));
        write16_raw(add32(aligned, 2), static_cast<int>(u32(value) >> 16U));
    }
}

int Bus::io_half(int offset) const noexcept {
    return io[static_cast<std::size_t>(offset)] | io[static_cast<std::size_t>(offset + 1)] << 8;
}

void Bus::write_io_half(int offset, int value) noexcept {
    io[static_cast<std::size_t>(offset)] = static_cast<std::uint8_t>(value);
    io[static_cast<std::size_t>(offset + 1)] = static_cast<std::uint8_t>(value >> 8);
}

void Bus::propagate_io_write(int offset, std::int32_t value, int bytes) {
    if (offset >= 0x0a0 && offset <= 0x0a7) {
        if (apu) apu->push_fifo(offset < 0x0a4 ? 0 : 1, value, bytes);
        return;
    }
    auto reg = offset & ~1;
    const auto end = offset + bytes;
    while (reg < end) { handle_io_write(reg, io_half(reg)); reg += 2; }
}

void Bus::handle_io_write(int offset, int value) {
    if (offset >= 0x020 && offset <= 0x027) diagnostics.bg2_matrix_write();
    else if (offset >= 0x028 && offset <= 0x02f) { diagnostics.bg2_reference_write(); ppu_affine_reference_write(ppu, offset); }
    else if (offset >= 0x038 && offset <= 0x03f) ppu_affine_reference_write(ppu, offset);
    else if (offset == 0x200 && interrupts) interrupts->enable = value;
    else if (offset == 0x202 && interrupts) interrupts->acknowledge(value);
    else if (offset == 0x208 && interrupts) interrupts->master_enable = (value & 1) != 0;
    else if (offset == 0x204) timing.wait_control = value;
    else if (timers && (offset == 0x100 || offset == 0x104 || offset == 0x108 || offset == 0x10c)) {
        timers->reload_write((offset - 0x100) / 4, value);
    } else if (timers && (offset == 0x102 || offset == 0x106 || offset == 0x10a || offset == 0x10e)) {
        timers->control_write((offset - 0x100) / 4, value);
    } else if (dma && (offset == 0x0ba || offset == 0x0c6 || offset == 0x0d2 || offset == 0x0de)) {
        const auto channel = offset == 0x0ba ? 0 : offset == 0x0c6 ? 1 : offset == 0x0d2 ? 2 : 3;
        dma_control_write(dma, channel, value);
    } else if (apu && offset >= 0x060 && offset <= 0x09f) {
        apu->write_register(offset, value);
    }
}

void Bus::reset_affine_matrices() {
    for (const auto base : {0x020, 0x030}) {
        write_io_half(base, 0x0100); write_io_half(base + 2, 0);
        write_io_half(base + 4, 0); write_io_half(base + 6, 0x0100);
    }
}

void Bus::sync_timing_from_io() { timing.wait_control = io_half(0x204); }

} // namespace ravenemu::gba
