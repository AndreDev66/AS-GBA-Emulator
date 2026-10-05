#pragma once

#include "cpu/cpu_state.hpp"
#include "memory/bus.hpp"

namespace ravenemu::gba {

class Cpu {
public:
    explicit Cpu(Bus& bus) : bus(bus) {}
    void reset(std::int32_t entry) {
        state.reset();
        state.switch_mode(CpuState::mode_supervisor); state.regs[13] = i32(0x03007fe0U);
        state.switch_mode(CpuState::mode_irq); state.regs[13] = i32(0x03007fa0U);
        state.switch_mode(CpuState::mode_system); state.regs[13] = i32(0x03007f00U);
        state.thumb = false; state.irq_disabled = false; state.regs[15] = entry;
    }
    [[nodiscard]] std::int32_t read_reg(int index) const noexcept {
        if (index != 15) return state.regs[static_cast<std::size_t>(index)];
        return add32(state.regs[15], state.thumb ? 4 : 8);
    }
    void write_reg(int index, std::int32_t value) noexcept {
        if (index == 15) branch_to(value); else state.regs[static_cast<std::size_t>(index)] = value;
    }
    void branch_to(std::int32_t address) noexcept {
        state.regs[15] = i32(u32(address) & (state.thumb ? ~1U : ~3U)); branched_ = true;
    }
    void branch_exchange(std::int32_t address) noexcept {
        state.thumb = (u32(address) & 1U) != 0;
        state.regs[15] = i32(u32(address) & (state.thumb ? ~1U : ~3U)); branched_ = true;
    }
    void execute_swi(int number, std::int32_t return_address) {
        if (swi_handler) swi_handler(number);
        else raise_exception(CpuState::mode_supervisor, 0x08, return_address);
    }
    void raise_exception(int mode, std::int32_t vector, std::int32_t return_address) {
        const auto saved = state.cpsr();
        state.switch_mode(mode); state.set_spsr(saved); state.regs[14] = return_address;
        state.irq_disabled = true; state.thumb = false; state.regs[15] = vector; branched_ = true;
    }
    int step() {
        branched_ = false; bus.take_wait_cycles();
        int cost{};
        if (state.thumb) {
            const auto instruction = bus.fetch16(state.regs[15]) & 0xffff;
            cost = execute_thumb(instruction);
            if (!branched_) state.regs[15] = add32(state.regs[15], 2);
        } else {
            const auto instruction = bus.fetch32(state.regs[15]);
            cost = check_condition(static_cast<int>(u32(instruction) >> 28U)) ? execute_arm(instruction) : 1;
            if (!branched_) state.regs[15] = add32(state.regs[15], 4);
        }
        if (branched_) bus.break_access_sequence();
        return cost + bus.take_wait_cycles();
    }
    [[nodiscard]] bool check_condition(int condition) const noexcept {
        switch (condition & 15) {
        case 0: return state.zero; case 1: return !state.zero; case 2: return state.carry;
        case 3: return !state.carry; case 4: return state.negative; case 5: return !state.negative;
        case 6: return state.overflow; case 7: return !state.overflow;
        case 8: return state.carry && !state.zero; case 9: return !state.carry || state.zero;
        case 10: return state.negative == state.overflow; case 11: return state.negative != state.overflow;
        case 12: return !state.zero && state.negative == state.overflow;
        case 13: return state.zero || state.negative != state.overflow; case 14: return true;
        default: return false;
        }
    }
    std::int32_t shift_immediate(std::int32_t value, int type, int amount) noexcept {
        const auto raw = u32(value);
        switch (type & 3) {
        case 0:
            if (amount == 0) { shifter_carry = state.carry; return value; }
            shifter_carry = ((raw >> (32U - static_cast<unsigned>(amount))) & 1U) != 0;
            return i32(raw << static_cast<unsigned>(amount));
        case 1: {
            const auto n = amount == 0 ? 32U : static_cast<unsigned>(amount);
            shifter_carry = ((raw >> (n - 1U)) & 1U) != 0;
            return n == 32U ? 0 : i32(raw >> n);
        }
        case 2: {
            const auto n = amount == 0 ? 32U : static_cast<unsigned>(amount);
            if (n >= 32U) { shifter_carry = value < 0; return value < 0 ? -1 : 0; }
            shifter_carry = ((raw >> (n - 1U)) & 1U) != 0;
            return value >> n;
        }
        default:
            if (amount == 0) {
                shifter_carry = (raw & 1U) != 0;
                return i32((raw >> 1U) | (state.carry ? 0x80000000U : 0U));
            }
            shifter_carry = ((raw >> (static_cast<unsigned>(amount) - 1U)) & 1U) != 0;
            return i32(std::rotr(raw, amount));
        }
    }
    std::int32_t shift_register(std::int32_t value, int type, int amount) noexcept {
        const auto n = static_cast<unsigned>(amount) & 0xffU;
        const auto raw = u32(value);
        if (n == 0) { shifter_carry = state.carry; return value; }
        switch (type & 3) {
        case 0:
            if (n < 32U) { shifter_carry = ((raw >> (32U - n)) & 1U) != 0; return i32(raw << n); }
            shifter_carry = n == 32U && (raw & 1U) != 0; return 0;
        case 1:
            if (n < 32U) { shifter_carry = ((raw >> (n - 1U)) & 1U) != 0; return i32(raw >> n); }
            shifter_carry = n == 32U && value < 0; return 0;
        case 2:
            if (n >= 32U) { shifter_carry = value < 0; return value < 0 ? -1 : 0; }
            shifter_carry = ((raw >> (n - 1U)) & 1U) != 0; return value >> n;
        default: {
            const auto rotate = n & 31U;
            if (rotate == 0) { shifter_carry = value < 0; return value; }
            shifter_carry = ((raw >> (rotate - 1U)) & 1U) != 0;
            return i32(std::rotr(raw, static_cast<int>(rotate)));
        }
        }
    }
    std::int32_t add_with_carry(std::int32_t a, std::int32_t b, int carry_in) noexcept {
        const auto sum = static_cast<std::uint64_t>(u32(a)) + u32(b) + static_cast<unsigned>(carry_in);
        const auto result = i32(static_cast<std::uint32_t>(sum));
        last_carry = sum > 0xffff'ffffULL;
        last_overflow = ((a ^ result) & (b ^ result)) < 0;
        return result;
    }
    void set_nz(std::int32_t value) noexcept { state.negative = value < 0; state.zero = value == 0; }
    std::int32_t load_word_rotated(std::int32_t address) {
        const auto word = bus.read32(i32(u32(address) & ~3U));
        return i32(std::rotr(u32(word), static_cast<int>((u32(address) & 3U) * 8U)));
    }

    Bus& bus;
    CpuState state;
    std::function<void(int)> swi_handler;
    bool shifter_carry{};
    bool last_carry{};
    bool last_overflow{};

private:
    int execute_arm(std::int32_t instruction);
    int arm_undefined(std::int32_t instruction);
    int arm_branch(std::int32_t instruction);
    int arm_data_or_psr(std::int32_t instruction);
    int arm_data(std::int32_t instruction, bool immediate, int opcode, bool set_flags);
    std::int32_t arm_operand2(std::int32_t instruction, bool immediate, int pc_extra);
    int arm_psr(std::int32_t instruction);
    int arm_single_transfer(std::int32_t instruction);
    int arm_multiply(std::int32_t instruction);
    int arm_multiply_long(std::int32_t instruction);
    int arm_swap(std::int32_t instruction);
    int arm_halfword(std::int32_t instruction);
    int arm_block(std::int32_t instruction);
    int arm_swi(std::int32_t instruction);
    int execute_thumb(int instruction);
    int thumb_undefined(int instruction);
    void thumb_arithmetic_flags(std::int32_t value) noexcept;
    bool branched_{};
};

} // namespace ravenemu::gba
