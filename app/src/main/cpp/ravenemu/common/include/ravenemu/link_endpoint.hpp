#pragma once

#include <array>

namespace ravenemu {

/** Extrémité matérielle série exposée à un transport indépendant de l'hôte. */
class LinkPort {
public:
    virtual ~LinkPort() = default;
    [[nodiscard]] virtual bool outgoing_bit_high() const noexcept = 0;
    virtual void clock_external_bit(bool incoming_high) noexcept = 0;
};

/**
 * Transport d'un link cable ou d'un périphérique série (Printer, réseau…).
 * La durée reste entièrement décidée par le SerialPort émulé.
 */
class LinkEndpoint {
public:
    virtual ~LinkEndpoint() = default;
    [[nodiscard]] virtual bool attach(LinkPort& port) noexcept = 0;
    virtual void detach(LinkPort& port) noexcept = 0;
    [[nodiscard]] virtual bool exchange_bit(LinkPort& source, bool outgoing_high) noexcept = 0;
};

/** Câble local déterministe reliant au plus deux machines dans un processus. */
class LocalLinkEndpoint final : public LinkEndpoint {
public:
    [[nodiscard]] bool attach(LinkPort& port) noexcept override {
        for (auto* existing : ports_) if (existing == &port) return true;
        for (auto& slot : ports_) {
            if (slot == nullptr) { slot = &port; return true; }
        }
        return false;
    }

    void detach(LinkPort& port) noexcept override {
        for (auto& slot : ports_) if (slot == &port) slot = nullptr;
    }

    [[nodiscard]] bool exchange_bit(LinkPort& source, bool outgoing_high) noexcept override {
        for (auto* port : ports_) {
            if (port == nullptr || port == &source) continue;
            const bool incoming = port->outgoing_bit_high();
            port->clock_external_bit(outgoing_high);
            return incoming;
        }
        return true; // ligne SIN au repos
    }

private:
    std::array<LinkPort*, 2> ports_{};
};

} // namespace ravenemu
