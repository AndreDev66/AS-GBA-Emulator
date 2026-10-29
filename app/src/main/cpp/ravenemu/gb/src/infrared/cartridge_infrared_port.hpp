#pragma once

#include <ravenemu/infrared_endpoint.hpp>

namespace ravenemu::cgb {

/**
 * Transceiver logique porté par une cartouche (HuC1/HuC3).
 *
 * Le composant ne connaît ni Android ni le transport utilisé. Il garantit
 * aussi qu'un changement d'endpoint est transactionnel : si le nouveau
 * backend refuse la connexion, l'ancien est restauré lorsque c'est possible.
 */
class CartridgeInfraredPort final : public InfraredPortEndpoint {
public:
    ~CartridgeInfraredPort() override { disconnect(); }
    CartridgeInfraredPort() = default;
    CartridgeInfraredPort(const CartridgeInfraredPort&) = delete;
    CartridgeInfraredPort& operator=(const CartridgeInfraredPort&) = delete;

    [[nodiscard]] bool connect(InfraredEndpoint* endpoint) noexcept {
        if (endpoint_ == endpoint) return true;

        auto* previous = endpoint_;
        if (previous != nullptr) previous->detach(*this);
        endpoint_ = nullptr;

        if (endpoint != nullptr && !endpoint->attach(*this)) {
            if (previous != nullptr && previous->attach(*this)) {
                endpoint_ = previous;
                previous->output_changed(*this);
            } else {
                light_detected_ = false;
            }
            return false;
        }

        endpoint_ = endpoint;
        if (endpoint_ != nullptr) endpoint_->output_changed(*this);
        else light_detected_ = false;
        return true;
    }

    void disconnect() noexcept {
        auto* previous = endpoint_;
        endpoint_ = nullptr;
        if (previous != nullptr) previous->detach(*this);
        light_detected_ = false;
    }

    void set_led(bool enabled) noexcept {
        if (led_on_ == enabled) return;
        led_on_ = enabled;
        notify_output();
    }

    void restore(bool led_on, bool light_detected) noexcept {
        led_on_ = led_on;
        light_detected_ = light_detected;
        notify_output();
    }

    [[nodiscard]] bool led_on() const noexcept { return led_on_; }
    [[nodiscard]] bool light_detected() const noexcept { return light_detected_; }
    [[nodiscard]] bool infrared_led_on() const noexcept override { return led_on_; }
    void set_infrared_light(bool detected) noexcept override { light_detected_ = detected; }

private:
    void notify_output() noexcept {
        if (endpoint_ != nullptr) endpoint_->output_changed(*this);
    }

    InfraredEndpoint* endpoint_{};
    bool led_on_{};
    bool light_detected_{};
};

} // namespace ravenemu::cgb
