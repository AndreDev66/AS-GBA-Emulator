#pragma once

#include "support/bits.hpp"

namespace ravenemu::gba {

class CpuState {
public:
    static constexpr int mode_user = 0x10;
    static constexpr int mode_fiq = 0x11;
    static constexpr int mode_irq = 0x12;
    static constexpr int mode_supervisor = 0x13;
    static constexpr int mode_abort = 0x17;
    static constexpr int mode_undefined = 0x1b;
    static constexpr int mode_system = 0x1f;

    void reset() noexcept {
        regs.fill(0); banked_r13_.fill(0); banked_r14_.fill(0);
        user_r8_r12_.fill(0); fiq_r8_r12_.fill(0); spsr_.fill(0);
        negative = zero = carry = overflow = false;
        irq_disabled = fiq_disabled = true;
        thumb = halted = false;
        mode = mode_supervisor;
    }
    [[nodiscard]] std::int32_t cpsr() const noexcept {
        auto result = static_cast<std::uint32_t>(mode & 0x1f);
        if (thumb) result |= 1U << 5U;
        if (fiq_disabled) result |= 1U << 6U;
        if (irq_disabled) result |= 1U << 7U;
        if (overflow) result |= 1U << 28U;
        if (carry) result |= 1U << 29U;
        if (zero) result |= 1U << 30U;
        if (negative) result |= 1U << 31U;
        return i32(result);
    }
    void set_cpsr(std::int32_t value, bool control) {
        const auto raw = u32(value);
        negative = (raw >> 31U) != 0; zero = ((raw >> 30U) & 1U) != 0;
        carry = ((raw >> 29U) & 1U) != 0; overflow = ((raw >> 28U) & 1U) != 0;
        if (control) {
            thumb = ((raw >> 5U) & 1U) != 0;
            fiq_disabled = ((raw >> 6U) & 1U) != 0;
            irq_disabled = ((raw >> 7U) & 1U) != 0;
            switch_mode(static_cast<int>(raw & 0x1fU));
        }
    }
    [[nodiscard]] std::int32_t spsr() const noexcept { return spsr_[static_cast<std::size_t>(bank_slot(mode))]; }
    void set_spsr(std::int32_t value) noexcept {
        const auto slot = bank_slot(mode);
        if (slot != 0) spsr_[static_cast<std::size_t>(slot)] = value;
    }
    [[nodiscard]] bool has_spsr() const noexcept { return bank_slot(mode) != 0; }
    void switch_mode(int next) noexcept {
        next &= 0x1f;
        if (next == mode) return;
        store_banks(mode); load_banks(next); mode = next;
    }
    std::array<std::int32_t, 28> export_banks() noexcept {
        store_banks(mode);
        std::array<std::int32_t, 28> result{};
        auto out = result.begin();
        out = std::copy(banked_r13_.begin(), banked_r13_.end(), out);
        out = std::copy(banked_r14_.begin(), banked_r14_.end(), out);
        out = std::copy(spsr_.begin(), spsr_.end(), out);
        out = std::copy(user_r8_r12_.begin(), user_r8_r12_.end(), out);
        std::copy(fiq_r8_r12_.begin(), fiq_r8_r12_.end(), out);
        return result;
    }
    void import_banks(std::span<const std::int32_t> values) {
        if (values.size() != 28) throw SaveStateError("Banques CPU GBA invalides");
        std::copy_n(values.begin(), 6, banked_r13_.begin());
        std::copy_n(values.begin() + 6, 6, banked_r14_.begin());
        std::copy_n(values.begin() + 12, 6, spsr_.begin());
        std::copy_n(values.begin() + 18, 5, user_r8_r12_.begin());
        std::copy_n(values.begin() + 23, 5, fiq_r8_r12_.begin());
    }
    void set_control_raw(std::int32_t value) noexcept {
        const auto raw = u32(value);
        negative = (raw >> 31U) != 0; zero = ((raw >> 30U) & 1U) != 0;
        carry = ((raw >> 29U) & 1U) != 0; overflow = ((raw >> 28U) & 1U) != 0;
        thumb = ((raw >> 5U) & 1U) != 0; fiq_disabled = ((raw >> 6U) & 1U) != 0;
        irq_disabled = ((raw >> 7U) & 1U) != 0; mode = static_cast<int>(raw & 0x1fU);
    }

    std::array<std::int32_t, 16> regs{};
    bool negative{}; bool zero{}; bool carry{}; bool overflow{};
    bool irq_disabled{true}; bool fiq_disabled{true}; bool thumb{}; bool halted{};
    int mode{mode_supervisor};

private:
    static int bank_slot(int value) noexcept {
        switch (value & 0x1f) {
        case mode_fiq: return 1; case mode_irq: return 2; case mode_supervisor: return 3;
        case mode_abort: return 4; case mode_undefined: return 5; default: return 0;
        }
    }
    void store_banks(int from) noexcept {
        const auto slot = static_cast<std::size_t>(bank_slot(from));
        banked_r13_[slot] = regs[13]; banked_r14_[slot] = regs[14];
        auto& values = from == mode_fiq ? fiq_r8_r12_ : user_r8_r12_;
        std::copy_n(regs.begin() + 8, 5, values.begin());
    }
    void load_banks(int to) noexcept {
        const auto slot = static_cast<std::size_t>(bank_slot(to));
        regs[13] = banked_r13_[slot]; regs[14] = banked_r14_[slot];
        const auto& values = to == mode_fiq ? fiq_r8_r12_ : user_r8_r12_;
        std::copy(values.begin(), values.end(), regs.begin() + 8);
    }
    std::array<std::int32_t, 6> banked_r13_{};
    std::array<std::int32_t, 6> banked_r14_{};
    std::array<std::int32_t, 5> user_r8_r12_{};
    std::array<std::int32_t, 5> fiq_r8_r12_{};
    std::array<std::int32_t, 6> spsr_{};
};

} // namespace ravenemu::gba
