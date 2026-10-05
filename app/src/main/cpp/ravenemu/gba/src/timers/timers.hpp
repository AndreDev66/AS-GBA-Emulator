#pragma once

#include "memory/bus.hpp"

namespace ravenemu::gba {

class Timers {
public:
    explicit Timers(InterruptController& interrupts) : interrupts_(interrupts) {}
    void reload_write(int timer, int value) {
        flush();
        reload_[static_cast<std::size_t>(timer)] = value & 0xffff;
    }
    void control_write(int timer, int value) {
        flush();
        const auto index = static_cast<std::size_t>(timer);
        const auto was_enabled = (control_[index] & 0x80) != 0;
        control_[index] = value & 0xffff;
        if (!was_enabled && (value & 0x80) != 0) {
            counter_[index] = reload_[index];
            prescaler_counter_[index] = 0;
        }
        cycles_until_overflow_ = compute_budget();
    }
    int counter(int timer) {
        const auto index = static_cast<std::size_t>(timer);
        if (pending_cycles_ < cycles_until_overflow_) {
            const auto settings = control_[index];
            if ((settings & 0x80) == 0 || (settings & 0x04) != 0) return counter_[index];
            const auto prescale = prescale_[static_cast<std::size_t>(settings & 3)];
            return counter_[index] + (prescaler_counter_[index] + pending_cycles_) / prescale;
        }
        flush();
        return counter_[index];
    }
    [[nodiscard]] int control(int timer) const noexcept { return control_[static_cast<std::size_t>(timer)]; }
    [[nodiscard]] int cycles_until_next_overflow() const noexcept {
        if (cycles_until_overflow_ == no_overflow) return no_overflow;
        return std::max(1, cycles_until_overflow_ - pending_cycles_);
    }
    void tick(int cycles) {
        if (cycles_until_overflow_ == no_overflow) return;
        pending_cycles_ += cycles;
        if (pending_cycles_ >= cycles_until_overflow_) flush();
    }
    std::array<std::int32_t, 16> export_state() {
        flush();
        std::array<std::int32_t, 16> result{};
        std::copy(counter_.begin(), counter_.end(), result.begin());
        std::copy(reload_.begin(), reload_.end(), result.begin() + 4);
        std::copy(control_.begin(), control_.end(), result.begin() + 8);
        std::copy(prescaler_counter_.begin(), prescaler_counter_.end(), result.begin() + 12);
        return result;
    }
    void import_state(std::span<const std::int32_t> state) {
        if (state.size() != 16) throw SaveStateError("État timers GBA invalide");
        std::copy_n(state.begin(), 4, counter_.begin());
        std::copy_n(state.begin() + 4, 4, reload_.begin());
        std::copy_n(state.begin() + 8, 4, control_.begin());
        std::copy_n(state.begin() + 12, 4, prescaler_counter_.begin());
        pending_cycles_ = 0;
        cycles_until_overflow_ = compute_budget();
    }
    void reset() noexcept {
        counter_.fill(0); reload_.fill(0); control_.fill(0); prescaler_counter_.fill(0);
        pending_cycles_ = 0; cycles_until_overflow_ = no_overflow;
    }
    std::function<void(int)> on_overflow;

private:
    static constexpr int no_overflow = std::numeric_limits<int>::max();
    static constexpr std::array prescale_{1, 64, 256, 1024};
    void flush() {
        const auto cycles = std::exchange(pending_cycles_, 0);
        if (cycles > 0) apply_cycles(cycles);
        cycles_until_overflow_ = compute_budget();
    }
    [[nodiscard]] int compute_budget() const noexcept {
        auto budget = no_overflow;
        for (std::size_t i = 0; i < 4; ++i) {
            const auto settings = control_[i];
            if ((settings & 0x80) == 0 || (settings & 0x04) != 0) continue;
            const auto divisor = prescale_[static_cast<std::size_t>(settings & 3)];
            const auto cycles = (0x10000 - counter_[i]) * divisor - prescaler_counter_[i];
            budget = std::min(budget, cycles);
        }
        return budget;
    }
    void apply_cycles(int cycles) {
        for (std::size_t i = 0; i < 4; ++i) {
            const auto settings = control_[i];
            if ((settings & 0x80) == 0 || (settings & 0x04) != 0) continue;
            const auto divisor = prescale_[static_cast<std::size_t>(settings & 3)];
            const auto total = prescaler_counter_[i] + cycles;
            prescaler_counter_[i] = total % divisor;
            if (total >= divisor) advance(static_cast<int>(i), total / divisor);
        }
    }
    void advance(int timer, int ticks) {
        const auto index = static_cast<std::size_t>(timer);
        const auto value = counter_[index] + ticks;
        if (value <= 0xffff) {
            counter_[index] = value;
            return;
        }
        const auto period = 0x10000 - reload_[index];
        const auto excess = value - 0x10000;
        const auto overflows = 1 + excess / period;
        counter_[index] = reload_[index] + excess % period;
        for (int i = 0; i < overflows; ++i) overflow(timer);
    }
    void overflow(int timer) {
        const auto index = static_cast<std::size_t>(timer);
        if ((control_[index] & 0x40) != 0) interrupts_.request(InterruptController::timer0 + timer);
        if (on_overflow) on_overflow(timer);
        const auto next = timer + 1;
        if (next < 4) {
            const auto settings = control_[static_cast<std::size_t>(next)];
            if ((settings & 0x84) == 0x84) advance(next, 1);
        }
    }

    InterruptController& interrupts_;
    std::array<int, 4> counter_{};
    std::array<int, 4> reload_{};
    std::array<int, 4> control_{};
    std::array<int, 4> prescaler_counter_{};
    int pending_cycles_{};
    int cycles_until_overflow_{no_overflow};
};

} // namespace ravenemu::gba
