#pragma once

#include "interrupt/interrupt_controller.hpp"
#include <ravenemu/link_endpoint.hpp>

namespace ravenemu::cgb {

class SerialPort final : public LinkPort {
public:
    SerialPort(InterruptController& interrupts, bool cgb_mode)
        : interrupts_(interrupts), cgb_mode_(cgb_mode) {}
    ~SerialPort() override { disconnect(); }
    SerialPort(const SerialPort&) = delete;
    SerialPort& operator=(const SerialPort&) = delete;

    /**
     * Avance l'horloge série interne en cycles CPU. Les périodes restent 512
     * cycles (8 KHz) et 16 cycles (256 KHz CGB) : en double vitesse le nombre
     * de cycles CPU par seconde double lui aussi, ce qui double naturellement
     * la fréquence série réelle.
     */
    void tick(int cycles) noexcept {
        for (int cycle = 0; cycle < cycles; ++cycle) {
            serial_divider_ = (serial_divider_ + 1) & 0x1ff;
            if (internal_transfer_active() &&
                (serial_divider_ & (bit_period() - 1)) == 0) {
                const bool outgoing = outgoing_bit_high();
                const int incoming = endpoint_ != nullptr
                    ? (endpoint_->exchange_bit(*this, outgoing) ? 1 : 0)
                    : 1;
                shift_bit(incoming);
            }
        }
    }

    void initialize_hle_post_boot(int reset_aligned_phase) noexcept {
        data_ = 0;
        control_ = 0;
        bits_remaining_ = 0;
        serial_divider_ = reset_aligned_phase & 0x1ff;
    }

    /** Front d'horloge fourni par un périphérique externe/link cable. */
    void clock_external_bit(bool incoming_high) noexcept override {
        if (!external_transfer_active()) return;
        shift_bit(incoming_high ? 1 : 0);
    }

    [[nodiscard]] bool outgoing_bit_high() const noexcept override { return (data_ & 0x80) != 0; }

    void set_cgb_mode(bool enabled) noexcept {
        cgb_mode_ = enabled;
        if (!enabled) control_ &= 0x81;
    }

    [[nodiscard]] bool connect(LinkEndpoint* endpoint) noexcept {
        if (endpoint_ == endpoint) return true;
        if (endpoint != nullptr && !endpoint->attach(*this)) return false;
        if (endpoint_ != nullptr) endpoint_->detach(*this);
        endpoint_ = endpoint;
        return true;
    }

    void disconnect() noexcept {
        if (endpoint_ != nullptr) endpoint_->detach(*this);
        endpoint_ = nullptr;
    }

    [[nodiscard]] int read_data() const noexcept { return data_; }
    void write_data(int value) noexcept { data_ = byte(value); }

    [[nodiscard]] int read_control() const noexcept {
        const int writable = cgb_mode_ ? (control_ & 0x83) : (control_ & 0x81);
        return writable | (cgb_mode_ ? 0x7c : 0x7e);
    }

    void write_control(int value) noexcept {
        const int mask = cgb_mode_ ? 0x83 : 0x81;
        control_ = value & mask;
        if ((control_ & 0x80) == 0) {
            bits_remaining_ = 0;
            return;
        }
        bits_remaining_ = 8;
    }

    [[nodiscard]] bool transfer_active() const noexcept { return (control_ & 0x80) != 0; }
    [[nodiscard]] bool fast_clock() const noexcept { return cgb_mode_ && (control_ & 0x02) != 0; }
    [[nodiscard]] bool internal_clock() const noexcept { return (control_ & 0x01) != 0; }
    [[nodiscard]] int bits_remaining() const noexcept { return bits_remaining_; }

    void save(BinaryWriter& out) const {
        out.i32(4);
        out.i32(data_);
        out.i32(control_);
        out.i32(bits_remaining_);
        out.i32(serial_divider_);
    }

    void load(BinaryReader& in) {
        if (in.i32() != 4) throw SaveStateError("Etat instantane corrompu (serie)");
        const auto bounded = [&in](int minimum, int maximum) {
            const int value = in.i32();
            if (value < minimum || value > maximum) {
                throw SaveStateError("Etat instantane corrompu (serie)");
            }
            return value;
        };
        data_ = bounded(0, 0xff);
        control_ = bounded(0, 0x83);
        const int allowed = cgb_mode_ ? 0x83 : 0x81;
        if ((control_ & ~allowed) != 0) throw SaveStateError("Etat instantane corrompu (serie)");
        bits_remaining_ = bounded(0, 8);
        serial_divider_ = bounded(0, 0x1ff);
        if (((control_ & 0x80) == 0) != (bits_remaining_ == 0)) {
            throw SaveStateError("Etat instantane corrompu (serie)");
        }
    }

private:
    [[nodiscard]] bool internal_transfer_active() const noexcept {
        return transfer_active() && internal_clock() && bits_remaining_ > 0;
    }
    [[nodiscard]] bool external_transfer_active() const noexcept {
        return transfer_active() && !internal_clock() && bits_remaining_ > 0;
    }
    [[nodiscard]] int bit_period() const noexcept { return fast_clock() ? 16 : 512; }

    void shift_bit(int incoming) noexcept {
        data_ = byte((data_ << 1) | (incoming & 1));
        if (bits_remaining_ > 0) --bits_remaining_;
        if (bits_remaining_ == 0) {
            control_ &= ~0x80;
            interrupts_.request(Interrupt::serial);
        }
    }

    InterruptController& interrupts_;
    bool cgb_mode_{};
    int data_{};
    int control_{};
    int bits_remaining_{};
    int serial_divider_{};
    LinkEndpoint* endpoint_{};
};

} // namespace ravenemu::cgb
