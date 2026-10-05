#pragma once

#include "interrupts/interrupt_controller.hpp"

namespace ravenemu::gba {

class Keypad {
public:
    void set_button(Button button, bool pressed) noexcept {
        int bit{};
        switch (button) {
        case Button::a: bit = 0; break;
        case Button::b: bit = 1; break;
        case Button::select: bit = 2; break;
        case Button::start: bit = 3; break;
        case Button::right: bit = 4; break;
        case Button::left: bit = 5; break;
        case Button::up: bit = 6; break;
        case Button::down: bit = 7; break;
        case Button::r: bit = 8; break;
        case Button::l: bit = 9; break;
        }
        if (pressed) pressed_bits |= 1 << bit;
        else pressed_bits &= ~(1 << bit);
    }
    [[nodiscard]] int input() const noexcept { return ~pressed_bits & 0x03ff; }
    int pressed_bits{};
};

} // namespace ravenemu::gba
