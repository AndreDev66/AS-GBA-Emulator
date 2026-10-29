#pragma once

#include "memory/bus.hpp"
#include "interrupt/interrupt_controller.hpp"

namespace ravenemu::cgb {

class Cpu {
public:
    Cpu(Bus& bus, InterruptController& interrupts, bool cgb_hardware = false)
        : bus_(bus), interrupts_(interrupts), cgb_hardware_(cgb_hardware) {}

    int step() {
        mcycles_ = 0;
        if (stopped) {
            if (bus_.stop_wake_requested()) stopped = false;
            else return 0;
        }
        if (locked) { idle_mcycle(); return elapsed_cycles(); }
        const bool enable_ime = ei_pending; ei_pending = false;
        if (halted) {
            if ((interrupts_.enable & interrupts_.flags & 0x1f) != 0) halted = false;
            else { halted_mcycle(); return elapsed_cycles(); }
        }
        if (ime) {
            if (interrupts_.highest_pending()) return service_interrupt();
        }
        const int opcode_address = pc;
        const int opcode = fetch_opcode();
        // Une requête qui apparaît pendant le fetch annule l'opcode, mais le
        // cycle qui a permis de la détecter ne remplace aucun des deux cycles
        // d'attente du dispatch. Ce détail rend une attente par NOP identique
        // à HALT et conserve les cinq M-cycles complets de l'interruption.
        // Sur DMG, DI garde son effet immédiat lorsqu'IF monte pendant son
        // fetch : il est donc le seul opcode autorisé à gagner cette course.
        if ((opcode != 0xf3 || cgb_hardware_) && ime && interrupts_.highest_pending()) {
            pc = opcode_address;
            return service_interrupt();
        }
        execute(opcode);
        if (enable_ime && opcode != 0xf3) ime = true;
        return elapsed_cycles();
    }

    [[nodiscard]] int af() const noexcept { return (a << 8) | f; }
    [[nodiscard]] int bc() const noexcept { return (b << 8) | c; }
    [[nodiscard]] int de() const noexcept { return (d << 8) | e; }
    [[nodiscard]] int hl() const noexcept { return (h << 8) | l; }
    void set_af(int value) noexcept { a = byte(value >> 8); f = value & 0xf0; }
    void set_bc(int value) noexcept { b = byte(value >> 8); c = byte(value); }
    void set_de(int value) noexcept { d = byte(value >> 8); e = byte(value); }
    void set_hl(int value) noexcept { h = byte(value >> 8); l = byte(value); }

    void save(BinaryWriter& out) const {
        out.i32(af()); out.i32(bc()); out.i32(de()); out.i32(hl()); out.i32(sp); out.i32(pc);
        out.boolean(ime); out.boolean(halted); out.boolean(ei_pending); out.boolean(halt_bug); out.boolean(locked); out.boolean(stopped);
    }
    void load(BinaryReader& in) {
        set_af(in.i32()); set_bc(in.i32()); set_de(in.i32()); set_hl(in.i32()); sp = in.i32(); pc = in.i32();
        ime = in.boolean(); halted = in.boolean(); ei_pending = in.boolean(); halt_bug = in.boolean(); locked = in.boolean(); stopped = in.boolean();
    }

    int a{0x01}; int f{0xb0}; int b{}; int c{0x13}; int d{}; int e{0xd8}; int h{0x01}; int l{0x4d};
    int sp{0xfffe}; int pc{0x0100};
    bool ime{}; bool halted{}; bool locked{}; bool ei_pending{}; bool halt_bug{}; bool stopped{};

private:
    static constexpr int flag_z = 0x80;
    static constexpr int flag_n = 0x40;
    static constexpr int flag_h = 0x20;
    static constexpr int flag_c = 0x10;
    [[nodiscard]] bool z() const noexcept { return (f & flag_z) != 0; }
    [[nodiscard]] bool n() const noexcept { return (f & flag_n) != 0; }
    [[nodiscard]] bool half() const noexcept { return (f & flag_h) != 0; }
    [[nodiscard]] bool carry() const noexcept { return (f & flag_c) != 0; }
    void flags(bool zero, bool subtract, bool half_carry, bool carry_value) noexcept {
        f = (zero ? flag_z : 0) | (subtract ? flag_n : 0) | (half_carry ? flag_h : 0) | (carry_value ? flag_c : 0);
    }
    [[nodiscard]] int elapsed_cycles() const noexcept { return mcycles_ * 4; }
    void idle_mcycle() { bus_.tick_mcycle(); ++mcycles_; }
    void halted_mcycle() { bus_.tick_halted_mcycle(); ++mcycles_; }
    [[nodiscard]] int read_mcycle(int address) {
        const int value = bus_.read_mcycle(address);
        ++mcycles_;
        return value;
    }
    void write_mcycle(int address, int value) {
        bus_.write_mcycle(address, value);
        ++mcycles_;
    }
    int service_interrupt() {
        ime = false;
        ei_pending = false;
        // EI ; HALT with an already pending interrupt triggers the HALT bug
        // before EI takes effect.  The interrupt wins, but the suppressed PC
        // increment still applies to the return address: RETI must return to
        // the HALT opcode itself, not to the byte after it.
        if (halt_bug) {
            pc = word(pc - 1);
            halt_bug = false;
        }
        // Deux M-cycles d'attente, puis l'octet haut de PC est empilé. Le
        // contrôleur échantillonne seulement alors IE&IF et sa priorité. Si
        // SP=$0000, cette écriture vise IE et peut donc annuler ou rediriger
        // le dispatch ; l'écriture de l'octet bas est déjà trop tardive.
        for (int cycle = 0; cycle < 2; ++cycle) idle_mcycle();
        sp = word(sp - 1);
        write_mcycle(sp, pc >> 8);
        const auto interrupt = interrupts_.highest_pending();
        sp = word(sp - 1);
        write_mcycle(sp, pc);
        idle_mcycle();
        if (interrupt) {
            interrupts_.acknowledge(*interrupt);
            pc = interrupt_vector(*interrupt);
        } else {
            pc = 0x0000;
        }
        return elapsed_cycles();
    }
    int fetch_opcode() {
        const int value = read_mcycle(pc); if (halt_bug) halt_bug = false; else pc = word(pc + 1); return value;
    }
    int fetch_byte() { const int value = read_mcycle(pc); pc = word(pc + 1); return value; }
    int fetch_stop_padding() { const int value = bus_.read(pc); pc = word(pc + 1); return value; }
    int fetch_word() { const int low = fetch_byte(); return low | (fetch_byte() << 8); }
    void push(int value, bool leading_idle = true) {
        if (leading_idle) idle_mcycle();
        sp = word(sp - 1); write_mcycle(sp, value >> 8);
        sp = word(sp - 1); write_mcycle(sp, value);
    }
    int pop() {
        const int low = read_mcycle(sp); sp = word(sp + 1);
        const int high = read_mcycle(sp); sp = word(sp + 1);
        return (high << 8) | low;
    }
    int read_r8(int index) {
        switch (index) { case 0: return b; case 1: return c; case 2: return d; case 3: return e;
        case 4: return h; case 5: return l; case 6: return read_mcycle(hl()); default: return a; }
    }
    void write_r8(int index, int value) {
        value = byte(value);
        switch (index) { case 0: b = value; break; case 1: c = value; break; case 2: d = value; break;
        case 3: e = value; break; case 4: h = value; break; case 5: l = value; break;
        case 6: write_mcycle(hl(), value); break; default: a = value; break; }
    }
    void add8(int value, int carry_in) noexcept {
        const int result = a + value + carry_in;
        flags(byte(result) == 0, false, (a & 0x0f) + (value & 0x0f) + carry_in > 0x0f, result > 0xff);
        a = byte(result);
    }
    void sub8(int value, int carry_in, bool store) noexcept {
        const int result = a - value - carry_in;
        flags(byte(result) == 0, true, (a & 0x0f) - (value & 0x0f) - carry_in < 0, result < 0);
        if (store) a = byte(result);
    }
    void and8(int value) noexcept { a &= value; flags(a == 0, false, true, false); }
    void xor8(int value) noexcept { a = byte(a ^ value); flags(a == 0, false, false, false); }
    void or8(int value) noexcept { a = byte(a | value); flags(a == 0, false, false, false); }
    int inc8(int value) noexcept {
        const int result = byte(value + 1); f = (f & flag_c) | (result == 0 ? flag_z : 0) | ((value & 0x0f) == 0x0f ? flag_h : 0); return result;
    }
    int dec8(int value) noexcept {
        const int result = byte(value - 1); f = (f & flag_c) | flag_n | (result == 0 ? flag_z : 0) | ((value & 0x0f) == 0 ? flag_h : 0); return result;
    }
    void add_hl(int value) noexcept {
        const int current = hl(); const int result = current + value;
        f = (f & flag_z) | ((current & 0x0fff) + (value & 0x0fff) > 0x0fff ? flag_h : 0) | (result > 0xffff ? flag_c : 0);
        set_hl(word(result));
    }
    int sp_plus_immediate() {
        const int offset = static_cast<std::int8_t>(fetch_byte());
        flags(false, false, (sp & 0x0f) + (offset & 0x0f) > 0x0f, (sp & 0xff) + (offset & 0xff) > 0xff);
        return word(sp + offset);
    }
    void daa() noexcept {
        int result = a;
        if (!n()) {
            if (carry() || result > 0x99) { result += 0x60; f |= flag_c; }
            if (half() || (result & 0x0f) > 9) result += 6;
        } else { if (carry()) result -= 0x60; if (half()) result -= 6; }
        result = byte(result); f = (f & (flag_n | flag_c)) | (result == 0 ? flag_z : 0); a = result;
    }
    int rlc(int value) noexcept { const int c = (value >> 7) & 1; const int result = byte((value << 1) | c); flags(result == 0, false, false, c != 0); return result; }
    int rrc(int value) noexcept { const int c = value & 1; const int result = byte((value >> 1) | (c << 7)); flags(result == 0, false, false, c != 0); return result; }
    int rl(int value) noexcept { const int in = carry() ? 1 : 0; const int c = (value >> 7) & 1; const int result = byte((value << 1) | in); flags(result == 0, false, false, c != 0); return result; }
    int rr(int value) noexcept { const int in = carry() ? 0x80 : 0; const int c = value & 1; const int result = (value >> 1) | in; flags(result == 0, false, false, c != 0); return result; }
    int sla(int value) noexcept { const int c = (value >> 7) & 1; const int result = byte(value << 1); flags(result == 0, false, false, c != 0); return result; }
    int sra(int value) noexcept { const int c = value & 1; const int result = (value >> 1) | (value & 0x80); flags(result == 0, false, false, c != 0); return result; }
    int swap(int value) noexcept { const int result = byte((value << 4) | (value >> 4)); flags(result == 0, false, false, false); return result; }
    int srl(int value) noexcept { const int c = value & 1; const int result = value >> 1; flags(result == 0, false, false, c != 0); return result; }
    int jump_relative(bool condition) {
        const int offset = static_cast<std::int8_t>(fetch_byte());
        if (!condition) return 8;
        idle_mcycle();
        pc = word(pc + offset);
        return 12;
    }
    int jump_absolute(bool condition) {
        const int target = fetch_word();
        if (!condition) return 12;
        idle_mcycle();
        pc = target;
        return 16;
    }
    int call(bool condition) {
        const int target = fetch_word();
        if (!condition) return 12;
        push(pc);
        pc = target;
        return 24;
    }
    int return_if(bool condition) {
        idle_mcycle();
        if (!condition) return 8;
        pc = pop();
        idle_mcycle();
        return 20;
    }
    int rst(int vector) { push(pc); pc = vector; return 16; }

    int execute(int opcode) {
        if (opcode == 0x76) {
            if (!ime && (interrupts_.enable & interrupts_.flags & 0x1f) != 0) halt_bug = true; else halted = true;
            return 4;
        }
        if (opcode >= 0x40 && opcode <= 0x7f) {
            const int source = opcode & 7; const int destination = (opcode >> 3) & 7;
            write_r8(destination, read_r8(source)); return source == 6 || destination == 6 ? 8 : 4;
        }
        if (opcode >= 0x80 && opcode <= 0xbf) {
            const int source = opcode & 7; const int value = read_r8(source); const int carry_in = carry() ? 1 : 0;
            switch ((opcode >> 3) & 7) { case 0: add8(value, 0); break; case 1: add8(value, carry_in); break;
            case 2: sub8(value, 0, true); break; case 3: sub8(value, carry_in, true); break;
            case 4: and8(value); break; case 5: xor8(value); break; case 6: or8(value); break; default: sub8(value, 0, false); break; }
            return source == 6 ? 8 : 4;
        }
        switch (opcode) {
        case 0x00: return 4; case 0x01: set_bc(fetch_word()); return 12; case 0x02: write_mcycle(bc(), a); return 8;
        case 0x03: idle_mcycle(); set_bc(word(bc() + 1)); return 8; case 0x04: b = inc8(b); return 4; case 0x05: b = dec8(b); return 4;
        case 0x06: b = fetch_byte(); return 8; case 0x07: a = rlc(a); f &= ~flag_z; return 4;
        case 0x08: { const int address = fetch_word(); write_mcycle(address, sp); write_mcycle(word(address + 1), sp >> 8); return 20; }
        case 0x09: idle_mcycle(); add_hl(bc()); return 8;
        case 0x0a: a = read_mcycle(bc()); return 8; case 0x0b: idle_mcycle(); set_bc(word(bc() - 1)); return 8;
        case 0x0c: c = inc8(c); return 4; case 0x0d: c = dec8(c); return 4; case 0x0e: c = fetch_byte(); return 8;
        case 0x0f: a = rrc(a); f &= ~flag_z; return 4;
        case 0x10: static_cast<void>(fetch_stop_padding()); if (!bus_.on_stop()) stopped = true; return 4; case 0x11: set_de(fetch_word()); return 12;
        case 0x12: write_mcycle(de(), a); return 8; case 0x13: idle_mcycle(); set_de(word(de() + 1)); return 8;
        case 0x14: d = inc8(d); return 4; case 0x15: d = dec8(d); return 4; case 0x16: d = fetch_byte(); return 8;
        case 0x17: a = rl(a); f &= ~flag_z; return 4; case 0x18: return jump_relative(true); case 0x19: idle_mcycle(); add_hl(de()); return 8;
        case 0x1a: a = read_mcycle(de()); return 8; case 0x1b: idle_mcycle(); set_de(word(de() - 1)); return 8;
        case 0x1c: e = inc8(e); return 4; case 0x1d: e = dec8(e); return 4; case 0x1e: e = fetch_byte(); return 8;
        case 0x1f: a = rr(a); f &= ~flag_z; return 4;
        case 0x20: return jump_relative(!z()); case 0x21: set_hl(fetch_word()); return 12;
        case 0x22: write_mcycle(hl(), a); set_hl(word(hl() + 1)); return 8; case 0x23: idle_mcycle(); set_hl(word(hl() + 1)); return 8;
        case 0x24: h = inc8(h); return 4; case 0x25: h = dec8(h); return 4; case 0x26: h = fetch_byte(); return 8;
        case 0x27: daa(); return 4; case 0x28: return jump_relative(z()); case 0x29: idle_mcycle(); add_hl(hl()); return 8;
        case 0x2a: a = read_mcycle(hl()); set_hl(word(hl() + 1)); return 8; case 0x2b: idle_mcycle(); set_hl(word(hl() - 1)); return 8;
        case 0x2c: l = inc8(l); return 4; case 0x2d: l = dec8(l); return 4; case 0x2e: l = fetch_byte(); return 8;
        case 0x2f: a = byte(~a); f |= flag_n | flag_h; return 4;
        case 0x30: return jump_relative(!carry()); case 0x31: sp = fetch_word(); return 12;
        case 0x32: write_mcycle(hl(), a); set_hl(word(hl() - 1)); return 8; case 0x33: idle_mcycle(); sp = word(sp + 1); return 8;
        case 0x34: { const int value = inc8(read_mcycle(hl())); write_mcycle(hl(), value); return 12; }
        case 0x35: { const int value = dec8(read_mcycle(hl())); write_mcycle(hl(), value); return 12; }
        case 0x36: { const int value = fetch_byte(); write_mcycle(hl(), value); return 12; } case 0x37: f = (f & flag_z) | flag_c; return 4;
        case 0x38: return jump_relative(carry()); case 0x39: idle_mcycle(); add_hl(sp); return 8;
        case 0x3a: a = read_mcycle(hl()); set_hl(word(hl() - 1)); return 8; case 0x3b: idle_mcycle(); sp = word(sp - 1); return 8;
        case 0x3c: a = inc8(a); return 4; case 0x3d: a = dec8(a); return 4; case 0x3e: a = fetch_byte(); return 8;
        case 0x3f: f = (f & flag_z) | ((f & flag_c) ^ flag_c); return 4;
        case 0xc0: return return_if(!z()); case 0xc1: set_bc(pop()); return 12; case 0xc2: return jump_absolute(!z());
        case 0xc3: return jump_absolute(true); case 0xc4: return call(!z()); case 0xc5: push(bc()); return 16;
        case 0xc6: add8(fetch_byte(), 0); return 8; case 0xc7: return rst(0x00); case 0xc8: return return_if(z());
        case 0xc9: pc = pop(); idle_mcycle(); return 16; case 0xca: return jump_absolute(z()); case 0xcb: return execute_cb();
        case 0xcc: return call(z()); case 0xcd: return call(true); case 0xce: add8(fetch_byte(), carry() ? 1 : 0); return 8;
        case 0xcf: return rst(0x08); case 0xd0: return return_if(!carry()); case 0xd1: set_de(pop()); return 12;
        case 0xd2: return jump_absolute(!carry()); case 0xd4: return call(!carry()); case 0xd5: push(de()); return 16;
        case 0xd6: sub8(fetch_byte(), 0, true); return 8; case 0xd7: return rst(0x10); case 0xd8: return return_if(carry());
        case 0xd9: pc = pop(); idle_mcycle(); ime = true; return 16; case 0xda: return jump_absolute(carry()); case 0xdc: return call(carry());
        case 0xde: sub8(fetch_byte(), carry() ? 1 : 0, true); return 8; case 0xdf: return rst(0x18);
        case 0xe0: { const int offset = fetch_byte(); write_mcycle(0xff00 + offset, a); return 12; } case 0xe1: set_hl(pop()); return 12;
        case 0xe2: write_mcycle(0xff00 + c, a); return 8; case 0xe5: push(hl()); return 16; case 0xe6: and8(fetch_byte()); return 8;
        case 0xe7: return rst(0x20); case 0xe8: { const int result = sp_plus_immediate(); idle_mcycle(); idle_mcycle(); sp = result; return 16; } case 0xe9: pc = hl(); return 4;
        case 0xea: { const int address = fetch_word(); write_mcycle(address, a); return 16; } case 0xee: xor8(fetch_byte()); return 8; case 0xef: return rst(0x28);
        case 0xf0: { const int offset = fetch_byte(); a = read_mcycle(0xff00 + offset); return 12; } case 0xf1: set_af(pop()); return 12;
        case 0xf2: a = read_mcycle(0xff00 + c); return 8; case 0xf3: ime = false; ei_pending = false; return 4;
        case 0xf5: push(af()); return 16; case 0xf6: or8(fetch_byte()); return 8; case 0xf7: return rst(0x30);
        case 0xf8: { const int result = sp_plus_immediate(); idle_mcycle(); set_hl(result); return 12; } case 0xf9: idle_mcycle(); sp = hl(); return 8;
        case 0xfa: { const int address = fetch_word(); a = read_mcycle(address); return 16; } case 0xfb: ei_pending = true; return 4;
        case 0xfe: sub8(fetch_byte(), 0, false); return 8; case 0xff: return rst(0x38);
        default: locked = true; return 4;
        }
    }
    int execute_cb() {
        const int opcode = fetch_byte(); const int reg = opcode & 7; const int selector = (opcode >> 3) & 7;
        const bool memory = reg == 6; const int value = read_r8(reg);
        switch (opcode >> 6) {
        case 0: {
            int result{}; switch (selector) { case 0: result = rlc(value); break; case 1: result = rrc(value); break;
            case 2: result = rl(value); break; case 3: result = rr(value); break; case 4: result = sla(value); break;
            case 5: result = sra(value); break; case 6: result = swap(value); break; default: result = srl(value); break; }
            write_r8(reg, result); return memory ? 16 : 8;
        }
        case 1: f = (f & flag_c) | flag_h | (((value >> selector) & 1) == 0 ? flag_z : 0); return memory ? 12 : 8;
        case 2: write_r8(reg, value & ~(1 << selector)); return memory ? 16 : 8;
        default: write_r8(reg, value | (1 << selector)); return memory ? 16 : 8;
        }
    }

    Bus& bus_;
    InterruptController& interrupts_;
    bool cgb_hardware_{};
    int mcycles_{};
};

} // namespace ravenemu::cgb
