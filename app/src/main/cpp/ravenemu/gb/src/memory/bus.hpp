#pragma once

#include "support/bits.hpp"

namespace ravenemu::cgb {

class Bus {
public:
    virtual ~Bus() = default;
    [[nodiscard]] virtual int read(int address) = 0;
    virtual void write(int address, int value) = 0;

    /**
     * Avance un M-cycle CPU avant l'accès ou l'opération interne suivante.
     *
     * Le CPU appelle cette primitive entre chacune de ses micro-opérations au
     * lieu de rendre un bloc de cycles une fois l'instruction terminée. Une
     * implémentation de bus de test peut la laisser vide, tandis que le bus de
     * la machine fait progresser DIV/TIMA, SIO, PPU, APU et DMA.
     */
    virtual void tick_mcycle() {}

    /**
     * Effectue un accès placé sur un M-cycle complet.
     *
     * Le comportement par défaut conserve l'ordre tick/access utilisé par
     * les bus de test. Le bus matériel peut surcharger ces primitives lorsque
     * l'arbitrage dépend à la fois de l'état au début et à la fin du cycle
     * (notamment le premier et le dernier cycle d'un OAM DMA).
     */
    [[nodiscard]] virtual int read_mcycle(int address) {
        tick_mcycle();
        return read(address);
    }
    virtual void write_mcycle(int address, int value) {
        tick_mcycle();
        write(address, value);
    }

    /** M-cycle passé en HALT. Le HDMA CGB peut le traiter différemment. */
    virtual void tick_halted_mcycle() { tick_mcycle(); }

    /** Retourne true lorsque STOP déclenche un changement de vitesse CGB. */
    virtual bool on_stop() { return false; }
    /** Consomme une demande de réveil du mode STOP normal. */
    virtual bool stop_wake_requested() { return false; }
    /** Le CPU doit rester arrêté pendant un transfert DMA VRAM. */
    [[nodiscard]] virtual bool cpu_blocked() const noexcept { return false; }

    void write_word(int address, int value) {
        write(address, value); write(word(address + 1), value >> 8);
    }
};

} // namespace ravenemu::cgb
