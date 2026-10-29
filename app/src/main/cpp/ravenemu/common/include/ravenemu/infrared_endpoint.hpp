#pragma once

#include <array>

namespace ravenemu {

class InfraredPortEndpoint {
public:
    virtual ~InfraredPortEndpoint() = default;
    [[nodiscard]] virtual bool infrared_led_on() const noexcept = 0;
    virtual void set_infrared_light(bool detected) noexcept = 0;
};

/** Transport logique IR indépendant d'Android, du réseau et de Bluetooth. */
class InfraredEndpoint {
public:
    virtual ~InfraredEndpoint() = default;
    [[nodiscard]] virtual bool attach(InfraredPortEndpoint& port) noexcept = 0;
    virtual void detach(InfraredPortEndpoint& port) noexcept = 0;
    virtual void output_changed(InfraredPortEndpoint& source) noexcept = 0;
};

/** Liaison IR déterministe entre au plus deux CGB dans le même processus. */
class LocalInfraredEndpoint final : public InfraredEndpoint {
public:
    [[nodiscard]] bool attach(InfraredPortEndpoint& port) noexcept override {
        for (auto* existing : ports_) if (existing == &port) return true;
        for (auto& slot : ports_) {
            if (slot == nullptr) {
                slot = &port;
                refresh();
                return true;
            }
        }
        return false;
    }

    void detach(InfraredPortEndpoint& port) noexcept override {
        for (auto& slot : ports_) if (slot == &port) slot = nullptr;
        port.set_infrared_light(false);
        refresh();
    }

    void output_changed(InfraredPortEndpoint&) noexcept override { refresh(); }

private:
    void refresh() noexcept {
        for (auto* target : ports_) {
            if (target == nullptr) continue;
            bool detected{};
            for (auto* source : ports_) {
                if (source != nullptr && source != target && source->infrared_led_on()) {
                    detected = true;
                }
            }
            target->set_infrared_light(detected);
        }
    }

    std::array<InfraredPortEndpoint*, 2> ports_{};
};

} // namespace ravenemu
