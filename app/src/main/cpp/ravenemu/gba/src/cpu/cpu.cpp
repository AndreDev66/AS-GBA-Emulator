#include "cpu/cpu.hpp"

namespace ravenemu::gba {

int Cpu::execute_arm(std::int32_t instruction) {
    const auto raw = u32(instruction);
    if ((raw & 0x0fff'fff0U) == 0x012f'ff10U) { branch_exchange(read_reg(static_cast<int>(raw & 15U))); return 3; }
    switch ((raw >> 25U) & 7U) {
    case 0:
        if ((raw & 0x0fb0'0ff0U) == 0x0100'0090U) return arm_swap(instruction);
        if ((raw & 0x0fc0'00f0U) == 0x0000'0090U) return arm_multiply(instruction);
        if ((raw & 0x0f80'00f0U) == 0x0080'0090U) return arm_multiply_long(instruction);
        if ((raw & 0x0e00'0090U) == 0x0000'0090U && (raw & 0x60U) != 0) return arm_halfword(instruction);
        return arm_data_or_psr(instruction);
    case 1: return arm_data_or_psr(instruction);
    case 2: case 3: return arm_single_transfer(instruction);
    case 4: return arm_block(instruction);
    case 5: return arm_branch(instruction);
    case 7: return ((raw >> 24U) & 1U) != 0 ? arm_swi(instruction) : arm_undefined(instruction);
    default: return arm_undefined(instruction);
    }
}

int Cpu::arm_undefined(std::int32_t instruction) {
    bus.diagnostics.report(DiagnosticEvent::undefined_instruction,
        "instruction ARM indéfinie " + std::to_string(u32(instruction)));
    return 1;
}

int Cpu::arm_branch(std::int32_t instruction) {
    const auto raw = u32(instruction);
    const auto offset = sign_extend((raw & 0x00ff'ffffU) << 2U, 26);
    if ((raw & 0x0100'0000U) != 0) write_reg(14, add32(state.regs[15], 4));
    branch_to(add32(read_reg(15), offset));
    return 3;
}

int Cpu::arm_data_or_psr(std::int32_t instruction) {
    const auto raw = u32(instruction);
    const auto immediate = ((raw >> 25U) & 1U) != 0;
    const auto opcode = static_cast<int>((raw >> 21U) & 15U);
    const auto set_flags = ((raw >> 20U) & 1U) != 0;
    if (!set_flags && opcode >= 8 && opcode <= 11) return arm_psr(instruction);
    return arm_data(instruction, immediate, opcode, set_flags);
}

int Cpu::arm_data(std::int32_t instruction, bool immediate, int opcode, bool set_flags) {
    const auto raw = u32(instruction);
    const auto rn = static_cast<int>((raw >> 16U) & 15U);
    const auto rd = static_cast<int>((raw >> 12U) & 15U);
    const auto register_shift = !immediate && ((raw >> 4U) & 1U) != 0;
    const auto pc_extra = register_shift ? 4 : 0;
    const auto a = add32(read_reg(rn), rn == 15 ? pc_extra : 0);
    const auto b = arm_operand2(instruction, immediate, pc_extra);
    bool arithmetic{};
    std::int32_t result{};
    switch (opcode) {
    case 0: result = a & b; break; case 1: result = a ^ b; break;
    case 2: arithmetic = true; result = add_with_carry(a, ~b, 1); break;
    case 3: arithmetic = true; result = add_with_carry(b, ~a, 1); break;
    case 4: arithmetic = true; result = add_with_carry(a, b, 0); break;
    case 5: arithmetic = true; result = add_with_carry(a, b, state.carry ? 1 : 0); break;
    case 6: arithmetic = true; result = add_with_carry(a, ~b, state.carry ? 1 : 0); break;
    case 7: arithmetic = true; result = add_with_carry(b, ~a, state.carry ? 1 : 0); break;
    case 8: result = a & b; break; case 9: result = a ^ b; break;
    case 10: arithmetic = true; result = add_with_carry(a, ~b, 1); break;
    case 11: arithmetic = true; result = add_with_carry(a, b, 0); break;
    case 12: result = a | b; break; case 13: result = b; break;
    case 14: result = a & ~b; break; default: result = ~b; break;
    }
    const auto writes = opcode < 8 || opcode > 11;
    if (set_flags) {
        if (rd == 15 && writes) { if (state.has_spsr()) state.set_cpsr(state.spsr(), true); }
        else { set_nz(result); state.carry = arithmetic ? last_carry : shifter_carry; if (arithmetic) state.overflow = last_overflow; }
    }
    if (writes) write_reg(rd, result);
    return rd == 15 && writes ? 3 : 1;
}

std::int32_t Cpu::arm_operand2(std::int32_t instruction, bool immediate, int pc_extra) {
    const auto raw = u32(instruction);
    if (immediate) {
        const auto value = raw & 0xffU;
        const auto rotate = static_cast<int>(((raw >> 8U) & 15U) * 2U);
        if (rotate == 0) { shifter_carry = state.carry; return i32(value); }
        const auto result = std::rotr(value, rotate);
        shifter_carry = (result & 0x8000'0000U) != 0;
        return i32(result);
    }
    const auto rm = static_cast<int>(raw & 15U);
    const auto type = static_cast<int>((raw >> 5U) & 3U);
    if (((raw >> 4U) & 1U) != 0) {
        const auto rs = static_cast<int>((raw >> 8U) & 15U);
        return shift_register(add32(read_reg(rm), rm == 15 ? pc_extra : 0), type, read_reg(rs) & 0xff);
    }
    return shift_immediate(read_reg(rm), type, static_cast<int>((raw >> 7U) & 31U));
}

int Cpu::arm_psr(std::int32_t instruction) {
    const auto raw = u32(instruction);
    const auto use_spsr = ((raw >> 22U) & 1U) != 0;
    if (((raw >> 21U) & 1U) == 0) {
        write_reg(static_cast<int>((raw >> 12U) & 15U), use_spsr ? state.spsr() : state.cpsr());
        return 1;
    }
    std::int32_t operand{};
    if (((raw >> 25U) & 1U) != 0) {
        operand = i32(std::rotr(raw & 0xffU, static_cast<int>(((raw >> 8U) & 15U) * 2U)));
    } else operand = read_reg(static_cast<int>(raw & 15U));
    const auto fields = static_cast<int>((raw >> 16U) & 15U);
    std::uint32_t mask{};
    if ((fields & 1) != 0) mask |= 0x000000ffU;
    if ((fields & 2) != 0) mask |= 0x0000ff00U;
    if ((fields & 4) != 0) mask |= 0x00ff0000U;
    if ((fields & 8) != 0) mask |= 0xff000000U;
    if (use_spsr) {
        if (state.has_spsr()) state.set_spsr(i32((u32(state.spsr()) & ~mask) | (u32(operand) & mask)));
    } else {
        if (state.mode == CpuState::mode_user) mask &= 0xff000000U;
        state.set_cpsr(i32((u32(state.cpsr()) & ~mask) | (u32(operand) & mask)), (mask & 0xffU) != 0);
    }
    return 1;
}

int Cpu::arm_single_transfer(std::int32_t instruction) {
    const auto raw = u32(instruction);
    const auto register_offset = ((raw >> 25U) & 1U) != 0;
    const auto pre = ((raw >> 24U) & 1U) != 0; const auto add = ((raw >> 23U) & 1U) != 0;
    const auto byte = ((raw >> 22U) & 1U) != 0; const auto write_back = ((raw >> 21U) & 1U) != 0;
    const auto load = ((raw >> 20U) & 1U) != 0;
    const auto rn = static_cast<int>((raw >> 16U) & 15U); const auto rd = static_cast<int>((raw >> 12U) & 15U);
    auto offset = i32(raw & 0xfffU);
    if (register_offset) offset = shift_immediate(read_reg(static_cast<int>(raw & 15U)),
        static_cast<int>((raw >> 5U) & 3U), static_cast<int>((raw >> 7U) & 31U));
    const auto base = read_reg(rn); const auto delta = add ? offset : i32(0U - u32(offset));
    const auto address = pre ? add32(base, delta) : base; const auto new_base = add32(base, delta);
    if (load) {
        const auto value = byte ? i32(static_cast<std::uint32_t>(bus.read8(address))) : load_word_rotated(address);
        if ((!pre || write_back) && rn != rd) write_reg(rn, new_base);
        write_reg(rd, value);
    } else {
        if (byte) bus.write8(address, read_reg(rd) & 0xff); else bus.write32(i32(u32(address) & ~3U), read_reg(rd));
        if (!pre || write_back) write_reg(rn, new_base);
    }
    return 2;
}

int Cpu::arm_multiply(std::int32_t instruction) {
    const auto raw = u32(instruction); const auto rd = static_cast<int>((raw >> 16U) & 15U);
    auto result = i32(u32(read_reg(static_cast<int>(raw & 15U))) * u32(read_reg(static_cast<int>((raw >> 8U) & 15U))));
    if (((raw >> 21U) & 1U) != 0) result = add32(result, read_reg(static_cast<int>((raw >> 12U) & 15U)));
    write_reg(rd, result); if (((raw >> 20U) & 1U) != 0) set_nz(result); return 2;
}

int Cpu::arm_multiply_long(std::int32_t instruction) {
    const auto raw = u32(instruction); const auto hi = static_cast<int>((raw >> 16U) & 15U);
    const auto lo = static_cast<int>((raw >> 12U) & 15U); const auto rs = static_cast<int>((raw >> 8U) & 15U);
    const auto rm = static_cast<int>(raw & 15U); const auto signed_multiply = ((raw >> 22U) & 1U) != 0;
    std::uint64_t product{};
    if (signed_multiply) product = static_cast<std::uint64_t>(static_cast<std::int64_t>(read_reg(rm)) * read_reg(rs));
    else product = static_cast<std::uint64_t>(u32(read_reg(rm))) * u32(read_reg(rs));
    if (((raw >> 21U) & 1U) != 0) product += (static_cast<std::uint64_t>(u32(read_reg(hi))) << 32U) | u32(read_reg(lo));
    write_reg(lo, i32(static_cast<std::uint32_t>(product))); write_reg(hi, i32(static_cast<std::uint32_t>(product >> 32U)));
    if (((raw >> 20U) & 1U) != 0) { state.negative = (product >> 63U) != 0; state.zero = product == 0; }
    return 3;
}

int Cpu::arm_swap(std::int32_t instruction) {
    const auto raw = u32(instruction); const auto address = read_reg(static_cast<int>((raw >> 16U) & 15U));
    const auto rd = static_cast<int>((raw >> 12U) & 15U); const auto source = read_reg(static_cast<int>(raw & 15U));
    if (((raw >> 22U) & 1U) != 0) { const auto old = bus.read8(address); bus.write8(address, source); write_reg(rd, old); }
    else { const auto old = load_word_rotated(address); bus.write32(i32(u32(address) & ~3U), source); write_reg(rd, old); }
    return 4;
}

int Cpu::arm_halfword(std::int32_t instruction) {
    const auto raw = u32(instruction); const auto pre = ((raw >> 24U) & 1U) != 0;
    const auto add = ((raw >> 23U) & 1U) != 0; const auto immediate = ((raw >> 22U) & 1U) != 0;
    const auto write_back = ((raw >> 21U) & 1U) != 0; const auto load = ((raw >> 20U) & 1U) != 0;
    const auto rn = static_cast<int>((raw >> 16U) & 15U); const auto rd = static_cast<int>((raw >> 12U) & 15U);
    const auto sh = static_cast<int>((raw >> 5U) & 3U);
    const auto offset = immediate ? i32(((raw >> 8U) & 15U) << 4U | (raw & 15U)) : read_reg(static_cast<int>(raw & 15U));
    const auto base = read_reg(rn); const auto delta = add ? offset : i32(0U - u32(offset));
    const auto address = pre ? add32(base, delta) : base; const auto new_base = add32(base, delta);
    if (load) {
        std::int32_t value{};
        if (sh == 1) value = bus.read16(address) & 0xffff;
        else if (sh == 2) value = sign_extend(static_cast<unsigned>(bus.read8(address)), 8);
        else value = sign_extend(static_cast<unsigned>(bus.read16(address)), 16);
        if ((!pre || write_back) && rn != rd) write_reg(rn, new_base);
        write_reg(rd, value);
    } else { bus.write16(address, read_reg(rd)); if (!pre || write_back) write_reg(rn, new_base); }
    return 2;
}

int Cpu::arm_block(std::int32_t instruction) {
    const auto raw = u32(instruction); const auto pre = ((raw >> 24U) & 1U) != 0;
    const auto add = ((raw >> 23U) & 1U) != 0; const auto psr_user = ((raw >> 22U) & 1U) != 0;
    const auto write_back = ((raw >> 21U) & 1U) != 0; const auto load = ((raw >> 20U) & 1U) != 0;
    const auto rn = static_cast<int>((raw >> 16U) & 15U); const auto list = raw & 0xffffU;
    const auto count = std::popcount(list); if (count == 0) return 2;
    const auto byte_count = static_cast<std::int32_t>(count) * 4;
    const auto base = read_reg(rn); std::int32_t address{};
    if (add) address = add32(base, pre ? 4 : 0);
    else address = add32(base, -byte_count + (pre ? 0 : 4));
    const auto write_value = add32(base, add ? byte_count : -byte_count);
    for (int reg = 0; reg < 16; ++reg) if ((list & (1U << static_cast<unsigned>(reg))) != 0) {
        if (load) write_reg(reg, bus.read32(address)); else bus.write32(address, read_reg(reg)); address = add32(address, 4);
    }
    if (write_back && !(load && (list & (1U << static_cast<unsigned>(rn))) != 0)) write_reg(rn, write_value);
    if (load && (list & 0x8000U) != 0 && psr_user && state.has_spsr()) state.set_cpsr(state.spsr(), true);
    return static_cast<int>(count) + 2;
}

int Cpu::arm_swi(std::int32_t instruction) {
    execute_swi(static_cast<int>((u32(instruction) >> 16U) & 0xffU), add32(state.regs[15], 4)); return 3;
}

void Cpu::thumb_arithmetic_flags(std::int32_t value) noexcept {
    set_nz(value); state.carry = last_carry; state.overflow = last_overflow;
}

int Cpu::thumb_undefined(int instruction) {
    bus.diagnostics.report(DiagnosticEvent::undefined_instruction,
        "instruction Thumb indéfinie " + std::to_string(instruction)); return 1;
}

int Cpu::execute_thumb(int instruction) {
    const auto instr = static_cast<unsigned>(instruction) & 0xffffU;
    if ((instr & 0xf800U) == 0x1800U) {
        const auto immediate = ((instr >> 10U) & 1U) != 0; const auto subtract = ((instr >> 9U) & 1U) != 0;
        const auto operand = static_cast<int>((instr >> 6U) & 7U); const auto rs = static_cast<int>((instr >> 3U) & 7U);
        const auto rd = static_cast<int>(instr & 7U); const auto b = immediate ? operand : read_reg(operand);
        const auto result = subtract ? add_with_carry(read_reg(rs), ~b, 1) : add_with_carry(read_reg(rs), b, 0);
        write_reg(rd, result); thumb_arithmetic_flags(result); return 1;
    }
    if ((instr & 0xe000U) == 0) {
        const auto type = static_cast<int>((instr >> 11U) & 3U); const auto amount = static_cast<int>((instr >> 6U) & 31U);
        const auto rs = static_cast<int>((instr >> 3U) & 7U); const auto rd = static_cast<int>(instr & 7U);
        const auto result = shift_immediate(read_reg(rs), type, amount); write_reg(rd, result); set_nz(result); state.carry = shifter_carry; return 1;
    }
    if ((instr & 0xe000U) == 0x2000U) {
        const auto opcode = static_cast<int>((instr >> 11U) & 3U); const auto rd = static_cast<int>((instr >> 8U) & 7U);
        const auto value = i32(instr & 0xffU); std::int32_t result{};
        if (opcode == 0) { write_reg(rd, value); set_nz(value); }
        else if (opcode == 1) { result = add_with_carry(read_reg(rd), ~value, 1); thumb_arithmetic_flags(result); }
        else if (opcode == 2) { result = add_with_carry(read_reg(rd), value, 0); write_reg(rd, result); thumb_arithmetic_flags(result); }
        else { result = add_with_carry(read_reg(rd), ~value, 1); write_reg(rd, result); thumb_arithmetic_flags(result); }
        return 1;
    }
    if ((instr & 0xfc00U) == 0x4000U) {
        const auto opcode = static_cast<int>((instr >> 6U) & 15U); const auto rs = static_cast<int>((instr >> 3U) & 7U);
        const auto rd = static_cast<int>(instr & 7U); const auto a = read_reg(rd); const auto b = read_reg(rs);
        std::int32_t result{}; bool write = true; bool arithmetic = false; bool shift = false;
        switch (opcode) {
        case 0: result = a & b; break; case 1: result = a ^ b; break;
        case 2: result = shift_register(a, 0, b); shift = true; break; case 3: result = shift_register(a, 1, b); shift = true; break;
        case 4: result = shift_register(a, 2, b); shift = true; break;
        case 5: result = add_with_carry(a, b, state.carry ? 1 : 0); arithmetic = true; break;
        case 6: result = add_with_carry(a, ~b, state.carry ? 1 : 0); arithmetic = true; break;
        case 7: result = shift_register(a, 3, b); shift = true; break;
        case 8: result = a & b; write = false; break; case 9: result = add_with_carry(0, ~b, 1); arithmetic = true; break;
        case 10: result = add_with_carry(a, ~b, 1); arithmetic = true; write = false; break;
        case 11: result = add_with_carry(a, b, 0); arithmetic = true; write = false; break;
        case 12: result = a | b; break; case 13: result = i32(u32(a) * u32(b)); break;
        case 14: result = a & ~b; break; default: result = ~b; break;
        }
        if (write) write_reg(rd, result);
        set_nz(result);
        if (arithmetic) { state.carry = last_carry; state.overflow = last_overflow; }
        else if (shift) state.carry = shifter_carry;
        return 1;
    }
    if ((instr & 0xfc00U) == 0x4400U) {
        const auto opcode = static_cast<int>((instr >> 8U) & 3U);
        const auto rd = static_cast<int>((instr & 7U) | (((instr >> 7U) & 1U) << 3U));
        const auto rs = static_cast<int>(((instr >> 3U) & 7U) | (((instr >> 6U) & 1U) << 3U));
        if (opcode == 0) write_reg(rd, add32(read_reg(rd), read_reg(rs)));
        else if (opcode == 1) thumb_arithmetic_flags(add_with_carry(read_reg(rd), ~read_reg(rs), 1));
        else if (opcode == 2) write_reg(rd, read_reg(rs)); else branch_exchange(read_reg(rs));
        return opcode == 3 || (opcode != 1 && rd == 15) ? 3 : 1;
    }
    if ((instr & 0xf800U) == 0x4800U) {
        const auto rd = static_cast<int>((instr >> 8U) & 7U);
        write_reg(rd, bus.read32(add32(i32(u32(read_reg(15)) & ~2U), i32((instr & 0xffU) << 2U)))); return 3;
    }
    if ((instr & 0xf200U) == 0x5000U) {
        const auto load = ((instr >> 11U) & 1U) != 0; const auto byte = ((instr >> 10U) & 1U) != 0;
        const auto address = add32(read_reg(static_cast<int>((instr >> 3U) & 7U)), read_reg(static_cast<int>((instr >> 6U) & 7U)));
        const auto rd = static_cast<int>(instr & 7U);
        if (load) write_reg(rd, byte ? bus.read8(address) : load_word_rotated(address));
        else if (byte) bus.write8(address, read_reg(rd));
        else bus.write32(i32(u32(address) & ~3U), read_reg(rd));
        return 2;
    }
    if ((instr & 0xf200U) == 0x5200U) {
        const auto h = ((instr >> 11U) & 1U) != 0; const auto s = ((instr >> 10U) & 1U) != 0;
        const auto address = add32(read_reg(static_cast<int>((instr >> 3U) & 7U)), read_reg(static_cast<int>((instr >> 6U) & 7U)));
        const auto rd = static_cast<int>(instr & 7U);
        if (!s && !h) bus.write16(address, read_reg(rd)); else if (!s) write_reg(rd, bus.read16(address));
        else if (!h) write_reg(rd, sign_extend(static_cast<unsigned>(bus.read8(address)), 8));
        else write_reg(rd, sign_extend(static_cast<unsigned>(bus.read16(address)), 16));
        return 2;
    }
    if ((instr & 0xe000U) == 0x6000U) {
        const auto byte = ((instr >> 12U) & 1U) != 0; const auto load = ((instr >> 11U) & 1U) != 0;
        const auto offset = static_cast<int>((instr >> 6U) & 31U); const auto rb = static_cast<int>((instr >> 3U) & 7U);
        const auto rd = static_cast<int>(instr & 7U); const auto address = add32(read_reg(rb), byte ? offset : offset << 2);
        if (load) write_reg(rd, byte ? bus.read8(address) : load_word_rotated(address));
        else if (byte) bus.write8(address, read_reg(rd));
        else bus.write32(i32(u32(address) & ~3U), read_reg(rd));
        return 2;
    }
    if ((instr & 0xf000U) == 0x8000U) {
        const auto load = ((instr >> 11U) & 1U) != 0; const auto rb = static_cast<int>((instr >> 3U) & 7U);
        const auto rd = static_cast<int>(instr & 7U); const auto address = add32(read_reg(rb), static_cast<int>((instr >> 5U) & 0x3eU));
        if (load) write_reg(rd, bus.read16(address)); else bus.write16(address, read_reg(rd)); return 2;
    }
    if ((instr & 0xf000U) == 0x9000U) {
        const auto load = ((instr >> 11U) & 1U) != 0; const auto rd = static_cast<int>((instr >> 8U) & 7U);
        const auto address = add32(read_reg(13), static_cast<int>((instr & 0xffU) << 2U));
        if (load) write_reg(rd, load_word_rotated(address)); else bus.write32(i32(u32(address) & ~3U), read_reg(rd)); return 2;
    }
    if ((instr & 0xf000U) == 0xa000U) {
        const auto rd = static_cast<int>((instr >> 8U) & 7U); const auto base = ((instr >> 11U) & 1U) != 0
            ? read_reg(13) : i32(u32(read_reg(15)) & ~2U);
        write_reg(rd, add32(base, static_cast<int>((instr & 0xffU) << 2U))); return 1;
    }
    if ((instr & 0xff00U) == 0xb000U) {
        const auto offset = static_cast<std::int32_t>((instr & 0x7fU) << 2U);
        write_reg(13, add32(read_reg(13), ((instr >> 7U) & 1U) != 0 ? -offset : offset)); return 1;
    }
    if ((instr & 0xf600U) == 0xb400U) {
        const auto load = ((instr >> 11U) & 1U) != 0; const auto extra = ((instr >> 8U) & 1U) != 0;
        const auto list = instr & 0xffU; const auto count = static_cast<int>(std::popcount(list)) + (extra ? 1 : 0);
        if (load) {
            auto address = read_reg(13); for (int reg = 0; reg < 8; ++reg) if ((list & (1U << reg)) != 0) {
                write_reg(reg, bus.read32(address)); address = add32(address, 4);
            }
            if (extra) { write_reg(15, bus.read32(address)); address = add32(address, 4); } write_reg(13, address);
        } else {
            auto address = add32(read_reg(13), -count * 4); write_reg(13, address);
            for (int reg = 0; reg < 8; ++reg) if ((list & (1U << reg)) != 0) {
                bus.write32(address, read_reg(reg)); address = add32(address, 4);
            }
            if (extra) bus.write32(address, read_reg(14));
        }
        return count + 1;
    }
    if ((instr & 0xf000U) == 0xc000U) {
        const auto load = ((instr >> 11U) & 1U) != 0; const auto rb = static_cast<int>((instr >> 8U) & 7U);
        const auto list = instr & 0xffU; if (list == 0) return 1; auto address = read_reg(rb);
        for (int reg = 0; reg < 8; ++reg) if ((list & (1U << reg)) != 0) {
            if (load) write_reg(reg, bus.read32(address)); else bus.write32(address, read_reg(reg)); address = add32(address, 4);
        }
        if (!(load && (list & (1U << rb)) != 0)) write_reg(rb, address);
        return static_cast<int>(std::popcount(list)) + 1;
    }
    if ((instr & 0xff00U) == 0xdf00U) { execute_swi(static_cast<int>(instr & 0xffU), add32(state.regs[15], 2)); return 3; }
    if ((instr & 0xf000U) == 0xd000U) {
        const auto condition = static_cast<int>((instr >> 8U) & 15U);
        if (condition >= 14) return thumb_undefined(instruction);
        if (!check_condition(condition)) return 1;
        branch_to(add32(read_reg(15), sign_extend((instr & 0xffU) << 1U, 9))); return 3;
    }
    if ((instr & 0xf800U) == 0xe000U) { branch_to(add32(read_reg(15), sign_extend((instr & 0x7ffU) << 1U, 12))); return 3; }
    if ((instr & 0xf800U) == 0xf000U) { write_reg(14, add32(read_reg(15), sign_extend((instr & 0x7ffU) << 12U, 23))); return 1; }
    if ((instr & 0xf800U) == 0xf800U) {
        const auto target = add32(read_reg(14), static_cast<int>((instr & 0x7ffU) << 1U));
        write_reg(14, i32(u32(add32(state.regs[15], 2)) | 1U)); branch_to(target); return 3;
    }
    return thumb_undefined(instruction);
}

Bus::Bus(Cartridge& value) : cartridge(value) {
    reset_affine_matrices();
}

} // namespace ravenemu::gba
