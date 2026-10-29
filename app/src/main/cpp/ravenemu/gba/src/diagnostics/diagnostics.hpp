#pragma once

#include "support/bits.hpp"

namespace ravenemu::gba {

class Diagnostics {
public:
    void begin_frame() noexcept {
        instructions_last_frame = instructions_this_frame;
        instructions_this_frame = 0;
        ppu_nanos_last_frame = std::exchange(ppu_nanos, 0);
        dma_nanos_last_frame = std::exchange(dma_nanos, 0);
        apu_nanos_last_frame = std::exchange(apu_nanos, 0);
    }

    void instruction() noexcept { ++instructions_this_frame; }
    void swi(int number) noexcept {
        last_swi = number;
        if (number >= 0 && number < static_cast<int>(swi_counts.size())) {
            ++swi_counts[static_cast<std::size_t>(number)];
        }
    }
    void interrupt(int mask) noexcept { last_interrupt_mask = mask; }
    void bg2_matrix_write() noexcept { ++bg2_matrix_writes; }
    void bg2_reference_write() noexcept { ++bg2_reference_writes; }
    void wait_step(int cycles, int mask) {
        const auto before = wait_cycles;
        wait_cycles += cycles;
        if (before < stuck_wait_cycles && wait_cycles >= stuck_wait_cycles) {
            report(DiagnosticEvent::missing_interrupt,
                   "attente prolongée de l'interruption " + std::to_string(mask));
        }
    }
    void wait_resolved() noexcept { wait_cycles = 0; }
    void unsupported_access(std::int32_t address) {
        if (counts[index(DiagnosticEvent::unsupported_access)] == 0) {
            first_unsupported_address = address;
        }
        report(DiagnosticEvent::unsupported_access,
               "accès mémoire GBA hors plan à " + std::to_string(u32(address)));
    }
    void report(DiagnosticEvent event, std::string detail) {
        const auto slot = index(event);
        ++counts[slot];
        if (reported[slot] >= max_reports_per_event) return;
        ++reported[slot];
        messages.push_back({event, std::move(detail)});
    }
    std::vector<DiagnosticMessage> drain() {
        auto result = std::move(messages);
        messages.clear();
        return result;
    }
    [[nodiscard]] int count(DiagnosticEvent event) const noexcept {
        return counts[index(event)];
    }

    bool measuring_time{};
    int instructions_this_frame{};
    int instructions_last_frame{};
    int last_swi{-1};
    int last_interrupt_mask{};
    std::int32_t first_unsupported_address{};
    int bg2_matrix_writes{};
    int bg2_reference_writes{};
    std::array<int, 0x30> swi_counts{};
    std::int64_t ppu_nanos_last_frame{};
    std::int64_t dma_nanos_last_frame{};
    std::int64_t apu_nanos_last_frame{};
    std::int64_t ppu_nanos{};
    std::int64_t dma_nanos{};
    std::int64_t apu_nanos{};

private:
    static constexpr std::size_t index(DiagnosticEvent event) noexcept {
        return static_cast<std::size_t>(event);
    }
    static constexpr int max_reports_per_event = 8;
    static constexpr int stuck_wait_cycles = 2 * 280'896;
    std::array<int, 5> counts{};
    std::array<int, 5> reported{};
    int wait_cycles{};
    std::vector<DiagnosticMessage> messages;
};

} // namespace ravenemu::gba
