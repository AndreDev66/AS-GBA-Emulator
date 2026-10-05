#pragma once

#include "memory/bus.hpp"

namespace ravenemu::gba {

class DmaController {
public:
    static constexpr int state_words = 50;

    DmaController(Bus& bus, InterruptController& interrupts) : bus_(bus), interrupts_(interrupts) {}

    void control_write(int channel, int control) {
        const auto bit = 1 << channel;
        if ((control & 0x8000) == 0) {
            pending_requests_ &= ~bit;
            if (active_channel_ != channel) suspended_[static_cast<std::size_t>(channel)] = {};
            return;
        }
        const auto index = static_cast<std::size_t>(channel);
        source_[index] = i32(u32(read_io_word(source_offsets_[index])) & 0x0fff'ffffU);
        destination_[index] = i32(u32(read_io_word(destination_offsets_[index])) & 0x0fff'ffffU);
        if (timing(control) == 0) request_transfer(channel);
    }

    void trigger_vblank() { trigger_timing(1); }
    void trigger_hblank() { trigger_timing(2); }

    void trigger_sound_fifo(int fifo_channel) {
        const auto fifo_address = fifo_channel == 0 ? i32(0x040000a0U) : i32(0x040000a4U);
        for (int channel = 1; channel <= 2; ++channel) {
            const auto index = static_cast<std::size_t>(channel);
            const auto control = read_io_half(control_offsets_[index]);
            if ((control & 0x8000) == 0 || timing(control) != 3) continue;
            if (i32(u32(read_io_word(destination_offsets_[index])) & 0x0fff'ffffU) != fifo_address) continue;
            request_transfer(channel);
        }
    }

    [[nodiscard]] bool active() const noexcept { return active_channel_ >= 0; }
    [[nodiscard]] int cycles_until_event() const noexcept { return active() ? phase_cycles_ : 0; }

    void tick(int cycles) {
        if (!active() || cycles <= 0) return;
        phase_cycles_ -= std::min(cycles, phase_cycles_);
        if (phase_cycles_ == 0) {
            complete_phase();
            if (active()) preempt_if_needed();
        }
    }

    std::array<std::int32_t, state_words> export_state() const noexcept {
        std::array<std::int32_t, state_words> result{};
        std::copy(source_.begin(), source_.end(), result.begin());
        std::copy(destination_.begin(), destination_.end(), result.begin() + 4);
        result[8] = active_channel_;
        result[9] = current_destination_;
        result[10] = remaining_words_;
        result[11] = active_control_;
        result[12] = phase_;
        result[13] = phase_cycles_;
        result[14] = latched_value_;
        result[15] = sequential_ ? 1 : 0;
        result[16] = fifo_transfer_ ? 1 : 0;
        result[17] = pending_requests_;
        for (int channel = 0; channel < 4; ++channel) {
            const auto& transfer = suspended_[static_cast<std::size_t>(channel)];
            const auto offset = suspended_offset + channel * suspended_transfer_words;
            result[static_cast<std::size_t>(offset)] = transfer.current_destination;
            result[static_cast<std::size_t>(offset + 1)] = transfer.remaining_words;
            result[static_cast<std::size_t>(offset + 2)] = transfer.control;
            result[static_cast<std::size_t>(offset + 3)] = transfer.phase;
            result[static_cast<std::size_t>(offset + 4)] = transfer.phase_cycles;
            result[static_cast<std::size_t>(offset + 5)] = transfer.latched_value;
            result[static_cast<std::size_t>(offset + 6)] = transfer.sequential ? 1 : 0;
            result[static_cast<std::size_t>(offset + 7)] = transfer.fifo ? 1 : 0;
        }
        return result;
    }

    void import_state(std::span<const std::int32_t> values) {
        if (values.size() != state_words) throw SaveStateError("État DMA GBA invalide");
        const auto active_channel = values[8];
        const auto phase = values[12];
        const auto pending_requests = values[17];
        const auto active_channel_valid = active_channel >= 0 && active_channel <= 3;
        const auto invalid_active = active_channel_valid &&
            (phase < phase_startup || phase > phase_write || values[10] <= 0 ||
             values[10] > 0x10000 || values[13] <= 0 ||
             (values[11] & 0x8000) == 0 || ((timing(values[11]) == 3) != (values[16] != 0)) ||
             (pending_requests & (1 << active_channel)) != 0);
        if (active_channel < -1 || active_channel > 3 || pending_requests < 0 ||
            pending_requests > 0x0f || values[15] < 0 || values[15] > 1 ||
            values[16] < 0 || values[16] > 1 || invalid_active) {
            throw SaveStateError("État DMA GBA invalide");
        }

        std::array<SuspendedTransfer, 4> suspended{};
        auto suspended_mask = 0;
        for (int channel = 0; channel < 4; ++channel) {
            const auto offset = suspended_offset + channel * suspended_transfer_words;
            const auto stored_phase = values[static_cast<std::size_t>(offset + 3)];
            if (stored_phase == phase_idle) {
                for (int field = 0; field < suspended_transfer_words; ++field) {
                    if (values[static_cast<std::size_t>(offset + field)] != 0) {
                        throw SaveStateError("État DMA GBA invalide");
                    }
                }
                continue;
            }
            const auto control = values[static_cast<std::size_t>(offset + 2)];
            const auto sequential = values[static_cast<std::size_t>(offset + 6)];
            const auto fifo = values[static_cast<std::size_t>(offset + 7)];
            if (channel == active_channel || (active_channel_valid && channel < active_channel) ||
                stored_phase < phase_startup || stored_phase > phase_write ||
                values[static_cast<std::size_t>(offset + 1)] <= 0 ||
                values[static_cast<std::size_t>(offset + 1)] > 0x10000 ||
                values[static_cast<std::size_t>(offset + 4)] <= 0 ||
                (control & 0x8000) == 0 || sequential < 0 || sequential > 1 || fifo < 0 || fifo > 1 ||
                ((timing(control) == 3) != (fifo != 0)) ||
                (pending_requests & (1 << channel)) != 0) {
                throw SaveStateError("État DMA GBA invalide");
            }
            auto& transfer = suspended[static_cast<std::size_t>(channel)];
            transfer.current_destination = values[static_cast<std::size_t>(offset)];
            transfer.remaining_words = values[static_cast<std::size_t>(offset + 1)];
            transfer.control = control;
            transfer.phase = stored_phase;
            transfer.phase_cycles = values[static_cast<std::size_t>(offset + 4)];
            transfer.latched_value = values[static_cast<std::size_t>(offset + 5)];
            transfer.sequential = sequential != 0;
            transfer.fifo = fifo != 0;
            suspended_mask |= 1 << channel;
        }
        const auto invalid_idle = active_channel == -1 &&
            (phase != phase_idle || values[10] != 0 || values[13] != 0 ||
             pending_requests != 0 || suspended_mask != 0);
        if (invalid_idle) throw SaveStateError("État DMA GBA invalide");

        std::copy_n(values.begin(), 4, source_.begin());
        std::copy_n(values.begin() + 4, 4, destination_.begin());
        active_channel_ = active_channel;
        current_destination_ = values[9];
        remaining_words_ = values[10];
        active_control_ = values[11];
        phase_ = phase;
        phase_cycles_ = values[13];
        latched_value_ = values[14];
        sequential_ = values[15] != 0;
        fifo_transfer_ = values[16] != 0;
        pending_requests_ = pending_requests;
        suspended_ = suspended;
        if (active()) bus_.break_access_sequence();
    }

    void reset() noexcept {
        source_.fill(0);
        destination_.fill(0);
        active_channel_ = -1;
        current_destination_ = 0;
        remaining_words_ = 0;
        active_control_ = 0;
        phase_ = phase_idle;
        phase_cycles_ = 0;
        latched_value_ = 0;
        sequential_ = false;
        fifo_transfer_ = false;
        pending_requests_ = 0;
        suspended_.fill({});
        last_channel = -1;
    }

    int last_channel{-1};

private:
    static constexpr int phase_idle = 0;
    static constexpr int phase_startup = 1;
    static constexpr int phase_read = 2;
    static constexpr int phase_write = 3;
    static constexpr int suspended_offset = 18;
    static constexpr int suspended_transfer_words = 8;
    static constexpr std::array source_offsets_{0x0b0, 0x0bc, 0x0c8, 0x0d4};
    static constexpr std::array destination_offsets_{0x0b4, 0x0c0, 0x0cc, 0x0d8};
    static constexpr std::array count_offsets_{0x0b8, 0x0c4, 0x0d0, 0x0dc};
    static constexpr std::array control_offsets_{0x0ba, 0x0c6, 0x0d2, 0x0de};

    struct SuspendedTransfer {
        std::int32_t current_destination{};
        int remaining_words{};
        int control{};
        int phase{};
        int phase_cycles{};
        std::int32_t latched_value{};
        bool sequential{};
        bool fifo{};

        [[nodiscard]] bool active() const noexcept { return phase != phase_idle; }
    };

    int read_io_half(int offset) const noexcept {
        return bus_.io[static_cast<std::size_t>(offset)] |
            bus_.io[static_cast<std::size_t>(offset + 1)] << 8;
    }

    std::int32_t read_io_word(int offset) const noexcept {
        return i32(static_cast<std::uint32_t>(read_io_half(offset)) |
                   (static_cast<std::uint32_t>(read_io_half(offset + 2)) << 16U));
    }

    static int timing(int control) noexcept { return (control >> 12) & 3; }

    static int delta(int control, int size) noexcept {
        if (control == 0 || control == 3) return size;
        if (control == 1) return -size;
        return 0;
    }

    int word_count(int channel, int raw) const noexcept {
        const auto mask = channel == 3 ? 0xffff : 0x3fff;
        const auto value = raw & mask;
        return value == 0 ? mask + 1 : value;
    }

    int access_cycles(std::int32_t address, int size, bool sequential) const noexcept {
        return 1 + bus_.timing.wait_states(address, size, sequential);
    }

    [[nodiscard]] int transfer_size() const noexcept {
        return fifo_transfer_ || (active_control_ & 0x0400) != 0 ? 4 : 2;
    }

    void trigger_timing(int requested) {
        for (int channel = 0; channel < 4; ++channel) {
            const auto control = read_io_half(control_offsets_[static_cast<std::size_t>(channel)]);
            if ((control & 0x8000) != 0 && timing(control) == requested) request_transfer(channel);
        }
    }

    void request_transfer(int channel) {
        const auto bit = 1 << channel;
        if (active_channel_ == channel || suspended_[static_cast<std::size_t>(channel)].active() ||
            (pending_requests_ & bit) != 0) {
            return;
        }
        pending_requests_ |= bit;
        start_next_transfer();
    }

    void start_next_transfer() {
        if (active()) return;
        while (true) {
            auto channel = -1;
            for (int candidate = 0; candidate < 4; ++candidate) {
                if (suspended_[static_cast<std::size_t>(candidate)].active() ||
                    (pending_requests_ & (1 << candidate)) != 0) {
                    channel = candidate;
                    break;
                }
            }
            if (channel < 0) return;

            auto& suspended = suspended_[static_cast<std::size_t>(channel)];
            if (suspended.active()) {
                restore_transfer(channel, suspended);
                suspended = {};
                return;
            }

            pending_requests_ &= ~(1 << channel);
            const auto control = read_io_half(control_offsets_[static_cast<std::size_t>(channel)]);
            if ((control & 0x8000) != 0) {
                start_transfer(channel, control);
                return;
            }
        }
    }

    void preempt_if_needed() {
        for (int channel = 0; channel < active_channel_; ++channel) {
            const auto bit = 1 << channel;
            if ((pending_requests_ & bit) == 0) continue;
            pending_requests_ &= ~bit;
            const auto control = read_io_half(control_offsets_[static_cast<std::size_t>(channel)]);
            if ((control & 0x8000) == 0) continue;
            suspend_active_transfer();
            start_transfer(channel, control);
            return;
        }
    }

    void suspend_active_transfer() noexcept {
        auto& transfer = suspended_[static_cast<std::size_t>(active_channel_)];
        transfer.current_destination = current_destination_;
        transfer.remaining_words = remaining_words_;
        transfer.control = active_control_;
        transfer.phase = phase_;
        transfer.phase_cycles = phase_cycles_;
        transfer.latched_value = latched_value_;
        transfer.sequential = sequential_;
        transfer.fifo = fifo_transfer_;
    }

    void restore_transfer(int channel, const SuspendedTransfer& transfer) noexcept {
        active_channel_ = channel;
        current_destination_ = transfer.current_destination;
        remaining_words_ = transfer.remaining_words;
        active_control_ = transfer.control;
        phase_ = transfer.phase;
        phase_cycles_ = transfer.phase_cycles;
        latched_value_ = transfer.latched_value;
        sequential_ = transfer.sequential;
        fifo_transfer_ = transfer.fifo;
    }

    void start_transfer(int channel, int control) {
        const auto index = static_cast<std::size_t>(channel);
        active_channel_ = channel;
        active_control_ = control;
        fifo_transfer_ = timing(control) == 3;
        remaining_words_ = fifo_transfer_ ? 4 : word_count(channel, read_io_half(count_offsets_[index]));
        current_destination_ = destination_[index];
        sequential_ = false;
        latched_value_ = 0;
        phase_ = phase_startup;
        phase_cycles_ = 2;
        if (auto* memory = bus_.eeprom(); memory &&
            ((u32(source_[index]) >> 24U) == 0x0dU || (u32(current_destination_) >> 24U) == 0x0dU)) {
            memory->hint_transfer_length(remaining_words_);
        }
        bus_.break_access_sequence();
    }

    void complete_phase() {
        if (phase_ == phase_startup) {
            phase_ = phase_read;
            phase_cycles_ = access_cycles(
                source_[static_cast<std::size_t>(active_channel_)],
                transfer_size(),
                sequential_
            );
        } else if (phase_ == phase_read) {
            complete_read();
        } else {
            complete_write();
        }
    }

    void complete_read() {
        const auto index = static_cast<std::size_t>(active_channel_);
        const auto size = transfer_size();
        const auto started = bus_.diagnostics.measuring_time
            ? std::chrono::steady_clock::now()
            : std::chrono::steady_clock::time_point{};
        latched_value_ = size == 4 ? bus_.read32(source_[index]) : bus_.read16(source_[index]);
        finish_access_timing(started);
        source_[index] = add32(source_[index], delta((active_control_ >> 7) & 3, size));
        phase_ = phase_write;
        phase_cycles_ = access_cycles(current_destination_, size, sequential_);
    }

    void complete_write() {
        const auto size = transfer_size();
        const auto started = bus_.diagnostics.measuring_time
            ? std::chrono::steady_clock::now()
            : std::chrono::steady_clock::time_point{};
        if (size == 4) bus_.write32(current_destination_, latched_value_);
        else bus_.write16(current_destination_, latched_value_);
        finish_access_timing(started);
        const auto destination_control = fifo_transfer_ ? 2 : (active_control_ >> 5) & 3;
        current_destination_ = add32(current_destination_, delta(destination_control, size));
        --remaining_words_;
        if (remaining_words_ > 0) {
            sequential_ = true;
            phase_ = phase_read;
            phase_cycles_ = access_cycles(
                source_[static_cast<std::size_t>(active_channel_)],
                size,
                sequential_
            );
            return;
        }
        finish_transfer();
    }

    void finish_access_timing(std::chrono::steady_clock::time_point started) {
        bus_.take_wait_cycles();
        if (bus_.diagnostics.measuring_time) {
            bus_.diagnostics.dma_nanos += std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - started
            ).count();
        }
    }

    void finish_transfer() {
        const auto channel = active_channel_;
        const auto index = static_cast<std::size_t>(channel);
        const auto destination_control = (active_control_ >> 5) & 3;
        if (!fifo_transfer_ && destination_control != 3) destination_[index] = current_destination_;
        if ((active_control_ & 0x4000) != 0) {
            interrupts_.request(InterruptController::dma0 + channel);
        }
        if (!fifo_transfer_ && ((active_control_ & 0x0200) == 0 || timing(active_control_) == 0)) {
            const auto cleared = active_control_ & ~0x8000;
            bus_.io[static_cast<std::size_t>(control_offsets_[index] + 1)] =
                static_cast<std::uint8_t>(cleared >> 8);
        }
        last_channel = channel;
        active_channel_ = -1;
        current_destination_ = 0;
        remaining_words_ = 0;
        active_control_ = 0;
        phase_ = phase_idle;
        phase_cycles_ = 0;
        latched_value_ = 0;
        sequential_ = false;
        fifo_transfer_ = false;
        bus_.break_access_sequence();
        start_next_transfer();
    }

    Bus& bus_;
    InterruptController& interrupts_;
    std::array<std::int32_t, 4> source_{};
    std::array<std::int32_t, 4> destination_{};
    int active_channel_{-1};
    std::int32_t current_destination_{};
    int remaining_words_{};
    int active_control_{};
    int phase_{phase_idle};
    int phase_cycles_{};
    std::int32_t latched_value_{};
    bool sequential_{};
    bool fifo_transfer_{};
    int pending_requests_{};
    std::array<SuspendedTransfer, 4> suspended_{};
};

inline void dma_control_write(DmaController* dma, int channel, int value) {
    if (dma) dma->control_write(channel, value);
}

} // namespace ravenemu::gba
