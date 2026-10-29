#pragma once

#include "support/bits.hpp"

namespace ravenemu::gba {

class Rtc {
public:
    using Clock = std::function<std::int64_t()>;
    explicit Rtc(Clock clock) : clock_(std::move(clock)) {}

    int pin_state(int data, int direction) const noexcept {
        auto result = data & direction;
        if ((direction & 2) == 0) result |= sio_out_ << 1;
        return result & 0x0f;
    }
    void write(int data, int direction) {
        const auto new_sck = pin(data, direction, 0, sck_);
        const auto new_sio = pin(data, direction, 1, sio_);
        const auto new_cs = pin(data, direction, 2, chip_select_);
        if (new_cs == 0) phase_ = idle;
        else if (chip_select_ == 0) {
            phase_ = command_phase;
            shift_ = 0;
            bit_index_ = 0;
        } else if (sck_ == 0 && new_sck == 1) on_rise(new_sio);
        else if (sck_ == 1 && new_sck == 0) on_fall();
        sck_ = new_sck;
        sio_ = new_sio;
        chip_select_ = new_cs;
    }
    std::array<std::int32_t, 20> export_state() const noexcept {
        std::array<std::int32_t, 20> result{
            sck_, sio_, chip_select_, sio_out_, phase_, shift_, bit_index_, command_,
            byte_index_, byte_count_, status_, static_cast<std::int32_t>(static_cast<std::uint64_t>(offset_seconds_) >> 32U),
            static_cast<std::int32_t>(offset_seconds_),
        };
        std::copy(transfer_.begin(), transfer_.end(), result.begin() + 13);
        return result;
    }
    void import_state(std::span<const std::int32_t> values) {
        if (values.size() != 20) throw SaveStateError("État RTC GBA invalide");
        sck_ = values[0]; sio_ = values[1]; chip_select_ = values[2]; sio_out_ = values[3];
        phase_ = values[4]; shift_ = values[5]; bit_index_ = values[6]; command_ = values[7];
        byte_index_ = values[8]; byte_count_ = values[9]; status_ = values[10];
        offset_seconds_ = static_cast<std::int64_t>(
            (static_cast<std::uint64_t>(u32(values[11])) << 32U) | u32(values[12]));
        std::copy_n(values.begin() + 13, 7, transfer_.begin());
    }

private:
    static constexpr int idle = 0;
    static constexpr int command_phase = 1;
    static constexpr int reading = 2;
    static constexpr int writing = 3;
    static int pin(int data, int direction, int bit, int previous) noexcept {
        return (direction & (1 << bit)) != 0 ? (data >> bit) & 1 : previous;
    }
    static int reverse_bits(int value) noexcept {
        auto input = static_cast<unsigned>(value) & 0xffU;
        unsigned result{};
        for (unsigned bit = 0; bit < 8; ++bit) result |= ((input >> bit) & 1U) << (7U - bit);
        return static_cast<int>(result);
    }
    static int bcd(int value) noexcept { return ((value / 10) << 4) | (value % 10); }
    static int from_bcd(int value) noexcept { return ((value >> 4) & 15) * 10 + (value & 15); }
    std::tm game_time() const {
        const auto seconds = static_cast<std::time_t>(clock_() + offset_seconds_);
        std::tm result{};
#if defined(_WIN32)
        localtime_s(&result, &seconds);
#else
        localtime_r(&seconds, &result);
#endif
        return result;
    }
    void load_time(const std::tm& value, int at) noexcept {
        transfer_[static_cast<std::size_t>(at)] = bcd(value.tm_hour);
        transfer_[static_cast<std::size_t>(at + 1)] = bcd(value.tm_min);
        transfer_[static_cast<std::size_t>(at + 2)] = bcd(value.tm_sec);
    }
    void decode_command() {
        auto byte = shift_ & 0xff;
        if ((byte & 0xf0) != 0x60) byte = reverse_bits(byte);
        command_ = (byte >> 1) & 7;
        const auto is_reading = (byte & 1) != 0;
        shift_ = 0; bit_index_ = 0; byte_index_ = 0; transfer_.fill(0);
        if (command_ == 0) {
            status_ = 0x40;
            offset_seconds_ = 0;
            phase_ = idle;
        } else if (command_ == 1) {
            byte_count_ = 1;
            if (is_reading) transfer_[0] = status_;
            phase_ = is_reading ? reading : writing;
        } else if (command_ == 2 || command_ == 3) {
            const auto now = game_time();
            byte_count_ = command_ == 2 ? 7 : 3;
            if (is_reading && command_ == 2) {
                transfer_[0] = bcd((now.tm_year + 1900) % 100);
                transfer_[1] = bcd(now.tm_mon + 1);
                transfer_[2] = bcd(now.tm_mday);
                transfer_[3] = now.tm_wday;
                load_time(now, 4);
            } else if (is_reading) {
                load_time(now, 0);
            }
            phase_ = is_reading ? reading : writing;
        } else {
            phase_ = idle;
        }
    }
    void on_rise(int bit) {
        if (phase_ == command_phase) {
            shift_ = (shift_ << 1) | bit;
            if (++bit_index_ == 8) decode_command();
        } else if (phase_ == writing) {
            shift_ |= bit << bit_index_;
            if (++bit_index_ == 8) {
                transfer_[static_cast<std::size_t>(byte_index_)] = shift_ & 0xff;
                shift_ = 0; bit_index_ = 0;
                if (++byte_index_ == byte_count_) {
                    apply_written();
                    phase_ = idle;
                }
            }
        }
    }
    void on_fall() noexcept {
        if (phase_ != reading) return;
        sio_out_ = (transfer_[static_cast<std::size_t>(byte_index_)] >> bit_index_) & 1;
        if (++bit_index_ == 8) {
            bit_index_ = 0;
            if (++byte_index_ >= byte_count_) phase_ = idle;
        }
    }
    void apply_written() {
        if (command_ == 1) {
            status_ = 0x40 | (transfer_[0] & 0x0e);
            return;
        }
        auto target = game_time();
        const auto at = command_ == 2 ? 4 : 0;
        if (command_ == 2) {
            target.tm_year = 100 + from_bcd(transfer_[0]);
            target.tm_mon = from_bcd(transfer_[1]) - 1;
            target.tm_mday = from_bcd(transfer_[2]);
        }
        target.tm_hour = from_bcd(transfer_[static_cast<std::size_t>(at)] & 0x7f);
        target.tm_min = from_bcd(transfer_[static_cast<std::size_t>(at + 1)]);
        target.tm_sec = from_bcd(transfer_[static_cast<std::size_t>(at + 2)]);
        // `target` part de l'heure hôte courante et porte donc son ancien
        // indicateur heure d'été. Après une écriture de date, cet indicateur
        // peut ne plus correspondre à la nouvelle saison : mktime décalerait
        // alors silencieusement l'heure demandée d'une heure. Laisser la
        // bibliothèque déterminer le régime de la nouvelle date.
        target.tm_isdst = -1;
        const auto epoch = std::mktime(&target);
        if (epoch != static_cast<std::time_t>(-1)) offset_seconds_ = epoch - clock_();
    }

    Clock clock_;
    int sck_{};
    int sio_{};
    int chip_select_{};
    int sio_out_{};
    int phase_{};
    int shift_{};
    int bit_index_{};
    int command_{};
    int byte_index_{};
    int byte_count_{};
    std::array<int, 7> transfer_{};
    int status_{0x40};
    std::int64_t offset_seconds_{};
};

} // namespace ravenemu::gba
