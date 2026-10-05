#pragma once

#include "cpu/cpu.hpp"
#include "apu/apu.hpp"
#include "timers/timers.hpp"
#include "dma/dma_controller.hpp"

namespace ravenemu::gba {

class Bios {
public:
    struct WaitState { int interrupt_mask{}; bool discard_old_flags{}; };
    Bios(Cpu& cpu, Bus& bus) : cpu_(cpu), bus_(bus) { install_irq_handler(); }

    void handle_swi(int number) {
        auto& registers = cpu_.state.regs; bus_.diagnostics.swi(number);
        switch (number) {
        case 0x00: soft_reset(); break; case 0x01: register_ram_reset(registers[0]); break;
        case 0x02: case 0x03: wait_state_.reset(); cpu_.state.halted = true; break;
        case 0x04: intr_wait(registers[0] != 0, registers[1] & 0x3fff); break;
        case 0x05: intr_wait(true, 1 << InterruptController::vblank); break;
        case 0x06: divide(registers[0], registers[1]); break; case 0x07: divide(registers[1], registers[0]); break;
        case 0x08: registers[0] = integer_sqrt(u32(registers[0])); break;
        case 0x09: registers[0] = arc_tan(registers[0]); break; case 0x0a: registers[0] = arc_tan2(registers[0], registers[1]); break;
        case 0x0b: cpu_set(registers[0], registers[1], registers[2]); break;
        case 0x0c: cpu_fast_set(registers[0], registers[1], registers[2]); break;
        case 0x0e: bg_affine_set(registers[0], registers[1], registers[2]); break;
        case 0x0f: obj_affine_set(registers[0], registers[1], registers[2], registers[3]); break;
        case 0x10: bit_unpack(registers[0], registers[1], registers[2]); break;
        case 0x11: lz77(registers[0], registers[1], false); break; case 0x12: lz77(registers[0], registers[1], true); break;
        case 0x13: huffman(registers[0], registers[1]); break;
        case 0x14: run_length(registers[0], registers[1], false); break; case 0x15: run_length(registers[0], registers[1], true); break;
        case 0x16: differential(registers[0], registers[1], false, false); break;
        case 0x17: differential(registers[0], registers[1], false, true); break;
        case 0x18: differential(registers[0], registers[1], true, true); break;
        default:
            bus_.diagnostics.report(DiagnosticEvent::unsupported_swi,
                "appel logiciel GBA non pris en charge " + std::to_string(number));
            break;
        }
    }
    void interrupt_raised(int mask) { write_flags(flags() | mask); }
    [[nodiscard]] bool should_resume() {
        if (!bus_.interrupts) return true;
        if (!wait_state_) return (bus_.interrupts->enable & bus_.interrupts->flags & 0x3fff) != 0;
        if ((flags() & wait_state_->interrupt_mask) == 0) return false;
        clear_flags(wait_state_->interrupt_mask); wait_state_.reset(); return true;
    }
    [[nodiscard]] const std::optional<WaitState>& wait_state() const noexcept { return wait_state_; }
    void restore_wait_state(std::optional<WaitState> value) noexcept { wait_state_ = value; }

private:
    static constexpr std::int32_t interrupt_flags_address = i32(0x03007ff8U);
    static constexpr std::size_t max_output = 1U << 20U;
    void install_irq_handler() noexcept {
        constexpr std::array<std::uint32_t, 6> handler{
            0xe92d500fU, 0xe3a00301U, 0xe28fe000U, 0xe510f004U, 0xe8bd500fU, 0xe25ef004U,
        };
        auto offset = 0x18U;
        for (const auto word : handler) for (unsigned byte = 0; byte < 4; ++byte) {
            bus_.bios[offset++] = static_cast<std::uint8_t>(word >> (byte * 8U));
        }
    }
    int flags() { return bus_.read32(interrupt_flags_address); }
    void write_flags(int value) { bus_.write32(interrupt_flags_address, value); }
    void clear_flags(int mask) { write_flags(flags() & ~mask); }
    void intr_wait(bool discard, int mask) {
        if (bus_.interrupts) bus_.interrupts->master_enable = true;
        if (mask == 0) return;
        if (discard) clear_flags(mask);
        else if ((flags() & mask) != 0) { clear_flags(mask); return; }
        wait_state_ = WaitState{mask, discard}; cpu_.state.halted = true;
    }
    void soft_reset() { cpu_.reset(i32(0x08000000U)); cpu_.branch_to(i32(0x08000000U)); }
    void register_ram_reset(int flags) {
        if ((flags & 1) != 0) bus_.ewram.fill(0);
        if ((flags & 2) != 0) std::fill(bus_.iwram.begin(), bus_.iwram.end() - 0x200, 0);
        if ((flags & 4) != 0) bus_.palette.fill(0);
        if ((flags & 8) != 0) bus_.vram.fill(0);
        if ((flags & 0x10) != 0) bus_.oam.fill(0);
        if ((flags & 0x20) != 0) std::fill(bus_.io.begin() + 0x120, bus_.io.begin() + 0x160, 0);
        if ((flags & 0x40) != 0) { std::fill(bus_.io.begin() + 0x60, bus_.io.begin() + 0x0a8, 0); if (bus_.apu) bus_.apu->reset(); }
        if ((flags & 0x80) != 0) {
            std::fill(bus_.io.begin(), bus_.io.begin() + 0x60, 0);
            std::fill(bus_.io.begin() + 0x0a8, bus_.io.begin() + 0x120, 0);
            std::fill(bus_.io.begin() + 0x160, bus_.io.end(), 0);
            if (bus_.timers) bus_.timers->reset();
            if (bus_.interrupts) bus_.interrupts->reset();
            if (bus_.dma) bus_.dma->reset();
            bus_.reset_affine_matrices();
        }
    }
    void divide(std::int32_t numerator, std::int32_t denominator) noexcept {
        if (denominator == 0) return;
        const auto quotient64 = static_cast<std::int64_t>(numerator) / denominator;
        const auto remainder64 = static_cast<std::int64_t>(numerator) % denominator;
        const auto quotient = i32(static_cast<std::uint32_t>(quotient64));
        cpu_.state.regs[0] = quotient; cpu_.state.regs[1] = i32(static_cast<std::uint32_t>(remainder64));
        cpu_.state.regs[3] = quotient < 0 ? i32(0U - u32(quotient)) : quotient;
    }
    static int integer_sqrt(std::uint32_t value) noexcept {
        auto root = static_cast<std::uint32_t>(std::sqrt(static_cast<double>(value)));
        while (static_cast<std::uint64_t>(root) * root > value) --root;
        while (static_cast<std::uint64_t>(root + 1U) * (root + 1U) <= value) ++root;
        return static_cast<int>(root);
    }
    static int arc_tan(int value) noexcept {
        const auto fixed = static_cast<double>(static_cast<std::int16_t>(value)) / 16384.0;
        return static_cast<int>(std::atan(fixed) * 32768.0 / std::numbers::pi) & 0xffff;
    }
    static int arc_tan2(int x, int y) noexcept {
        const auto fx = static_cast<double>(static_cast<std::int16_t>(x)); const auto fy = static_cast<double>(static_cast<std::int16_t>(y));
        if (fx == 0.0 && fy == 0.0) return 0;
        auto angle = std::atan2(fy, fx);
        if (angle < 0.0) angle += 2.0 * std::numbers::pi;
        return static_cast<int>(angle * 32768.0 / std::numbers::pi) & 0xffff;
    }
    void cpu_set(std::int32_t source, std::int32_t destination, std::int32_t control) {
        const auto count = static_cast<int>(u32(control) & 0x1fffffU); const auto fixed = (u32(control) & (1U << 24U)) != 0;
        const auto words = (u32(control) & (1U << 26U)) != 0; const auto unit = words ? 4 : 2;
        for (int index = 0; index < count; ++index) {
            if (words) bus_.write32(destination, bus_.read32(source)); else bus_.write16(destination, bus_.read16(source));
            if (!fixed) source = add32(source, unit);
            destination = add32(destination, unit);
        }
    }
    void cpu_fast_set(std::int32_t source, std::int32_t destination, std::int32_t control) {
        const auto count = static_cast<int>(u32(control) & 0x1fffffU); const auto fixed = (u32(control) & (1U << 24U)) != 0;
        for (int index = 0; index < count; ++index) { bus_.write32(destination, bus_.read32(source)); if (!fixed) source = add32(source, 4); destination = add32(destination, 4); }
    }
    void bg_affine_set(std::int32_t source, std::int32_t destination, int count) {
        count = std::clamp(count, 0, 512);
        for (int entry = 0; entry < count; ++entry) {
            const auto origin_x = bus_.read32(source); const auto origin_y = bus_.read32(add32(source, 4));
            const auto screen_x = static_cast<std::int16_t>(bus_.read16(add32(source, 8)));
            const auto screen_y = static_cast<std::int16_t>(bus_.read16(add32(source, 10)));
            const auto scale_x = static_cast<std::int16_t>(bus_.read16(add32(source, 12)));
            const auto scale_y = static_cast<std::int16_t>(bus_.read16(add32(source, 14)));
            const auto angle = static_cast<double>((bus_.read16(add32(source, 16)) >> 8) & 0xff) * 2.0 * std::numbers::pi / 256.0;
            source = add32(source, 20); const auto cosine = std::cos(angle); const auto sine = std::sin(angle);
            const auto pa = static_cast<std::int32_t>(cosine * scale_x); const auto pb = static_cast<std::int32_t>(-sine * scale_x);
            const auto pc = static_cast<std::int32_t>(sine * scale_y); const auto pd = static_cast<std::int32_t>(cosine * scale_y);
            bus_.write16(destination, pa); bus_.write16(add32(destination, 2), pb); bus_.write16(add32(destination, 4), pc); bus_.write16(add32(destination, 6), pd);
            bus_.write32(add32(destination, 8), add32(origin_x, i32(0U - u32(i32(u32(pa) * static_cast<std::uint32_t>(screen_x) + u32(pb) * static_cast<std::uint32_t>(screen_y))))));
            bus_.write32(add32(destination, 12), add32(origin_y, i32(0U - u32(i32(u32(pc) * static_cast<std::uint32_t>(screen_x) + u32(pd) * static_cast<std::uint32_t>(screen_y))))));
            destination = add32(destination, 16);
        }
    }
    void obj_affine_set(std::int32_t source, std::int32_t destination, int count, int stride) {
        count = std::clamp(count, 0, 512);
        for (int entry = 0; entry < count; ++entry) {
            const auto scale_x = static_cast<std::int16_t>(bus_.read16(source)); const auto scale_y = static_cast<std::int16_t>(bus_.read16(add32(source, 2)));
            const auto angle = static_cast<double>((bus_.read16(add32(source, 4)) >> 8) & 0xff) * 2.0 * std::numbers::pi / 256.0;
            source = add32(source, 8); const auto cosine = std::cos(angle); const auto sine = std::sin(angle);
            bus_.write16(destination, static_cast<int>(cosine * scale_x)); bus_.write16(add32(destination, stride), static_cast<int>(-sine * scale_x));
            bus_.write16(add32(destination, stride * 2), static_cast<int>(sine * scale_y)); bus_.write16(add32(destination, stride * 3), static_cast<int>(cosine * scale_y));
            destination = add32(destination, stride * 4);
        }
    }
    [[nodiscard]] int output_size(std::int32_t source) { return static_cast<int>((u32(bus_.read32(source)) >> 8U) & 0x00ff'ffffU); }
    bool validate_size(int size) {
        if (size > 0 && static_cast<std::size_t>(size) <= max_output) return true;
        bus_.diagnostics.report(DiagnosticEvent::decompression_error, "taille de décompression GBA invalide"); return false;
    }
    void decompression_error(std::string message) { bus_.diagnostics.report(DiagnosticEvent::decompression_error, std::move(message)); }
    void write_output(std::int32_t destination, std::span<const std::uint8_t> output, bool halfwords) {
        if (!halfwords) { for (std::size_t index = 0; index < output.size(); ++index) bus_.write8(add32(destination, static_cast<int>(index)), output[index]); return; }
        std::size_t index{}; for (; index + 1 < output.size(); index += 2) bus_.write16(add32(destination, static_cast<int>(index)), output[index] | output[index + 1] << 8);
        if (index < output.size()) bus_.write16(add32(destination, static_cast<int>(index)), output[index]);
    }
    void lz77(std::int32_t source, std::int32_t destination, bool vram) {
        const auto size = output_size(source); if (!validate_size(size)) return; std::vector<std::uint8_t> output(static_cast<std::size_t>(size));
        auto input = add32(source, 4); int written{};
        while (written < size) { const auto flags = bus_.read8(input); input = add32(input, 1);
            for (int bit = 7; bit >= 0 && written < size; --bit) {
                if (((flags >> bit) & 1) == 0) { output[static_cast<std::size_t>(written++)] = static_cast<std::uint8_t>(bus_.read8(input)); input = add32(input, 1); continue; }
                const auto first = bus_.read8(input); const auto second = bus_.read8(add32(input, 1)); input = add32(input, 2);
                const auto length = (first >> 4) + 3; const auto distance = ((first & 15) << 8 | second) + 1; auto from = written - distance;
                if (from < 0) { decompression_error("référence LZ77 GBA invalide"); return; }
                for (int count = 0; count < length && written < size; ++count) output[static_cast<std::size_t>(written++)] = output[static_cast<std::size_t>(from++)];
            }
        }
        write_output(destination, output, vram);
    }
    void run_length(std::int32_t source, std::int32_t destination, bool vram) {
        const auto size = output_size(source); if (!validate_size(size)) return; std::vector<std::uint8_t> output(static_cast<std::size_t>(size));
        auto input = add32(source, 4); int written{};
        while (written < size) { const auto flag = bus_.read8(input); input = add32(input, 1); const auto length = (flag & 0x7f) + ((flag & 0x80) != 0 ? 3 : 1);
            if ((flag & 0x80) != 0) { const auto value = static_cast<std::uint8_t>(bus_.read8(input)); input = add32(input, 1); for (int i = 0; i < length && written < size; ++i) output[static_cast<std::size_t>(written++)] = value; }
            else for (int i = 0; i < length && written < size; ++i) { output[static_cast<std::size_t>(written++)] = static_cast<std::uint8_t>(bus_.read8(input)); input = add32(input, 1); }
        }
        write_output(destination, output, vram);
    }
    void huffman(std::int32_t source, std::int32_t destination) {
        const auto header = bus_.read32(source); const auto size = static_cast<int>((u32(header) >> 8U) & 0xffffffU); const auto symbol_bits = static_cast<int>(u32(header) & 15U);
        if (!validate_size(size) || (symbol_bits != 4 && symbol_bits != 8)) { decompression_error("flux Huffman GBA invalide"); return; }
        const auto tree_start = add32(source, 4); const auto tree_bytes = (bus_.read8(tree_start) + 1) * 2;
        const auto root = add32(tree_start, 1); const auto tree_end = add32(tree_start, tree_bytes); auto stream = tree_end;
        std::vector<std::uint8_t> output(static_cast<std::size_t>(size)); int written{}; bool high{}; int pending{};
        auto node = root; std::uint32_t word{}; int bits{}; std::int64_t budget = static_cast<std::int64_t>(size) * 8 * 32 + 64;
        while (written < size) {
            if (--budget <= 0) { decompression_error("flux Huffman GBA sans fin"); return; }
            if (bits == 0) { word = u32(bus_.read32(stream)); stream = add32(stream, 4); bits = 32; }
            const auto bit = static_cast<int>(word >> 31U); word <<= 1U; --bits; const auto node_value = bus_.read8(node);
            const auto next = add32(i32(u32(node) & ~1U), ((node_value & 0x3f) + 1) * 2 + bit);
            const auto leaf = bit == 0 ? (node_value & 0x80) != 0 : (node_value & 0x40) != 0;
            if (u32(next) < u32(root) || u32(next) >= u32(tree_end)) { decompression_error("arbre Huffman GBA invalide"); return; }
            if (!leaf) { node = next; continue; } const auto symbol = bus_.read8(next); node = root;
            if (symbol_bits == 8) output[static_cast<std::size_t>(written++)] = static_cast<std::uint8_t>(symbol);
            else if (!high) { pending = symbol & 15; high = true; }
            else { output[static_cast<std::size_t>(written++)] = static_cast<std::uint8_t>(pending | (symbol & 15) << 4); high = false; }
        }
        write_output(destination, output, true);
    }
    void differential(std::int32_t source, std::int32_t destination, bool wide, bool vram) {
        const auto size = output_size(source); if (!validate_size(size)) return; std::vector<std::uint8_t> output(static_cast<std::size_t>(size)); auto input = add32(source, 4);
        if (wide) { int previous{}; for (int index = 0; index + 1 < size; index += 2) { const auto delta = bus_.read8(input) | bus_.read8(add32(input, 1)) << 8; input = add32(input, 2); previous = (previous + delta) & 0xffff; output[static_cast<std::size_t>(index)] = static_cast<std::uint8_t>(previous); output[static_cast<std::size_t>(index + 1)] = static_cast<std::uint8_t>(previous >> 8); } }
        else { int previous{}; for (int index = 0; index < size; ++index) { previous = (previous + bus_.read8(input)) & 0xff; input = add32(input, 1); output[static_cast<std::size_t>(index)] = static_cast<std::uint8_t>(previous); } }
        write_output(destination, output, vram);
    }
    void bit_unpack(std::int32_t source, std::int32_t destination, std::int32_t parameters) {
        const auto length = bus_.read16(parameters); const auto source_width = bus_.read8(add32(parameters, 2)); const auto destination_width = bus_.read8(add32(parameters, 3));
        const auto offset_word = u32(bus_.read32(add32(parameters, 4))); const auto data_offset = offset_word & 0x7fffffffU; const auto offset_zero = (offset_word & 0x80000000U) != 0;
        if (length <= 0 || source_width <= 0 || source_width > 8 || destination_width <= 0 || destination_width > 32 || 8 % source_width != 0) { decompression_error("paramètres BitUnPack GBA invalides"); return; }
        std::uint64_t buffer{}; int bits{}; const auto mask = (1U << static_cast<unsigned>(source_width)) - 1U;
        for (int index = 0; index < length; ++index) { const auto byte = static_cast<unsigned>(bus_.read8(add32(source, index)));
            for (int consumed = 0; consumed < 8; consumed += source_width) { const auto value = (byte >> static_cast<unsigned>(consumed)) & mask; const auto expanded = value == 0 && !offset_zero ? 0U : value + data_offset; buffer |= static_cast<std::uint64_t>(expanded) << static_cast<unsigned>(bits); bits += destination_width;
                if (bits >= 32) { bus_.write32(destination, i32(static_cast<std::uint32_t>(buffer))); destination = add32(destination, 4); buffer >>= 32U; bits -= 32; }
            }
        }
        if (bits > 0) bus_.write32(destination, i32(static_cast<std::uint32_t>(buffer)));
    }

    Cpu& cpu_;
    Bus& bus_;
    std::optional<WaitState> wait_state_;
};

} // namespace ravenemu::gba
