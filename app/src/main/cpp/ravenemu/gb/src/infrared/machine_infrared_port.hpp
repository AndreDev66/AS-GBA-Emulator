#pragma once

#include <ravenemu/infrared_endpoint.hpp>

#include <array>

namespace ravenemu::cgb {

/**
 * Présente une seule extrémité IR par machine à l'hôte, même lorsque le CGB
 * et la cartouche possèdent chacun leur propre transceiver.
 *
 * Les ports internes reçoivent la même lumière distante et leurs LED sont
 * combinées. Le backend externe ne voit donc jamais les deux composants d'une
 * même console comme deux appareils susceptibles de s'éclairer mutuellement.
 */
class MachineInfraredPort final : public InfraredPortEndpoint, public InfraredEndpoint {
public:
    ~MachineInfraredPort() override { disconnect(); }
    MachineInfraredPort() = default;
    MachineInfraredPort(const MachineInfraredPort&) = delete;
    MachineInfraredPort& operator=(const MachineInfraredPort&) = delete;

    [[nodiscard]] bool attach(InfraredPortEndpoint& port) noexcept override {
        if (&port == this) return false;
        for (auto* existing : ports_) if (existing == &port) return true;
        for (auto& slot : ports_) {
            if (slot == nullptr) {
                slot = &port;
                port.set_infrared_light(light_detected_);
                notify_output();
                return true;
            }
        }
        return false;
    }

    void detach(InfraredPortEndpoint& port) noexcept override {
        bool detached{};
        for (auto& slot : ports_) {
            if (slot == &port) {
                slot = nullptr;
                detached = true;
            }
        }
        if (!detached) return;
        port.set_infrared_light(false);
        notify_output();
    }

    void output_changed(InfraredPortEndpoint& source) noexcept override {
        for (auto* port : ports_) {
            if (port == &source) {
                notify_output();
                return;
            }
        }
    }

    [[nodiscard]] bool infrared_led_on() const noexcept override {
        for (const auto* port : ports_) {
            if (port != nullptr && port->infrared_led_on()) return true;
        }
        return false;
    }

    void set_infrared_light(bool detected) noexcept override {
        light_detected_ = detected;
        for (auto* port : ports_) {
            if (port != nullptr) port->set_infrared_light(detected);
        }
    }

    [[nodiscard]] bool connect(InfraredEndpoint* endpoint) noexcept {
        if (external_ == endpoint) return true;
        if (endpoint == this) return false;

        auto* previous = external_;
        if (previous != nullptr) previous->detach(*this);
        external_ = nullptr;

        if (endpoint != nullptr && !endpoint->attach(*this)) {
            if (previous != nullptr && previous->attach(*this)) {
                external_ = previous;
                previous->output_changed(*this);
            } else {
                set_infrared_light(false);
            }
            return false;
        }

        external_ = endpoint;
        if (external_ != nullptr) external_->output_changed(*this);
        else set_infrared_light(false);
        return true;
    }

    void disconnect() noexcept {
        auto* previous = external_;
        external_ = nullptr;
        if (previous != nullptr) previous->detach(*this);
        set_infrared_light(false);
    }

private:
    void notify_output() noexcept {
        if (external_ != nullptr) external_->output_changed(*this);
    }

    std::array<InfraredPortEndpoint*, 2> ports_{};
    InfraredEndpoint* external_{};
    bool light_detected_{};
};

} // namespace ravenemu::cgb
