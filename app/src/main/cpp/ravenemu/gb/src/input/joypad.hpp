#pragma once

#include "interrupt/interrupt_controller.hpp"

namespace ravenemu::cgb {

class Joypad {
public:
    explicit Joypad(InterruptController& interrupts) : interrupts_(interrupts) {}

    void set_button(Button button, bool pressed) noexcept {
        bool action{};
        int bit{};
        switch (button) {
        case Button::a: action = true; bit = 1; break;
        case Button::b: action = true; bit = 2; break;
        case Button::select: action = true; bit = 4; break;
        case Button::start: action = true; bit = 8; break;
        case Button::right: bit = 1; break;
        case Button::left: bit = 2; break;
        case Button::up: bit = 4; break;
        case Button::down: bit = 8; break;
        case Button::l: case Button::r: return;
        }
        auto& state = action ? action_state_ : direction_state_;
        const bool was_pressed = (state & bit) != 0;
        state = pressed ? state | bit : state & ~bit;
        if (pressed && !was_pressed) stop_wake_pending_ = true;
        const bool selected = (action && (select_ & 0x20) == 0) || (!action && (select_ & 0x10) == 0);
        if (pressed && !was_pressed && selected) interrupts_.request(Interrupt::joypad);
    }

    [[nodiscard]] int read() const noexcept {
        int lines = 0x0f;
        if ((select_ & 0x10) == 0) lines &= ~direction_state_;
        if ((select_ & 0x20) == 0) lines &= ~action_state_;
        return 0xc0 | select_ | (lines & 0x0f);
    }
    void write(int value) noexcept { select_ = value & 0x30; }

    [[nodiscard]] bool take_stop_wake() noexcept {
        const bool pending = stop_wake_pending_;
        stop_wake_pending_ = false;
        return pending;
    }

    void save(BinaryWriter& out) const {
        out.i32(4);
        out.i32(select_);
        out.i32(action_state_);
        out.i32(direction_state_);
        out.i32(stop_wake_pending_ ? 1 : 0);
    }
    void load(BinaryReader& in) {
        if (in.i32() != 4) throw SaveStateError("Etat instantane corrompu (joypad)");
        const auto nibble = [&in] {
            const int value = in.i32();
            if (value < 0 || value > 0x0f) {
                throw SaveStateError("Etat instantane corrompu (joypad)");
            }
            return value;
        };
        const int select = in.i32();
        if (select < 0 || select > 0x30 || (select & ~0x30) != 0) {
            throw SaveStateError("Etat instantane corrompu (joypad)");
        }
        select_ = select;
        action_state_ = nibble();
        direction_state_ = nibble();
        const int wake = in.i32();
        if (wake < 0 || wake > 1) throw SaveStateError("Etat instantane corrompu (joypad)");
        stop_wake_pending_ = wake != 0;
    }

private:
    InterruptController& interrupts_;
    int select_{0x30};
    int action_state_{};
    int direction_state_{};
    bool stop_wake_pending_{};
};

} // namespace ravenemu::cgb
