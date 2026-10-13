#pragma once
#include <algorithm>
#include <cstdint>
#include <limits>

namespace lockpin::phone {
// One user-visible connection request. Radio notifications never extend its
// deadline; only a new selection/manual resend creates another window.
class ConnectionWindow {
public:
    static constexpr unsigned MaxAttempts = 4;
    static constexpr std::uint32_t AttemptTimeoutMs = 8000;
    static constexpr std::uint32_t WindowMs = 60000;
    static constexpr std::uint32_t LegacyFallbackMs = 30000;
    static constexpr std::uint32_t Forever = (std::numeric_limits<std::uint32_t>::max)();
    static constexpr std::uint32_t CountdownSeconds(std::uint32_t remainingMs) noexcept {
        return remainingMs / 1000 + (remainingMs % 1000 != 0 ? 1 : 0);
    }

    void Start(std::uint64_t nowMs) noexcept {
        if (started_) return;
        started_ = true;
        deadlineMs_ = nowMs + WindowMs;
        nextAttemptMs_ = nowMs;
    }
    bool Started() const noexcept { return started_; }
    bool Expired(std::uint64_t nowMs) const noexcept {
        return started_ && nowMs >= deadlineMs_;
    }
    bool CanAttempt(std::uint64_t nowMs) const noexcept {
        return started_ && !stopped_ && attempts_ < MaxAttempts &&
            nowMs >= nextAttemptMs_ && !Expired(nowMs);
    }
    std::uint32_t BeginAttempt(std::uint64_t nowMs) noexcept {
        if (!CanAttempt(nowMs)) return 0;
        ++attempts_;
        nextAttemptMs_ = deadlineMs_;
        return (std::min)(RemainingMs(nowMs), AttemptTimeoutMs);
    }
    void Failed(std::uint64_t nowMs) noexcept {
        constexpr std::uint32_t delays[] = {5000, 10000, 20000};
        if (attempts_ > 0 && attempts_ < MaxAttempts)
            nextAttemptMs_ = nowMs + delays[attempts_ - 1];
    }
    unsigned Attempts() const noexcept { return attempts_; }
    bool HasPendingAttempt() const noexcept { return !stopped_ && attempts_ < MaxAttempts; }
    void StopAttempts() noexcept { stopped_ = true; }
    std::uint32_t RemainingMs(std::uint64_t nowMs) const noexcept {
        if (!started_) return Forever;
        return Expired(nowMs) ? 0 : static_cast<std::uint32_t>(deadlineMs_ - nowMs);
    }
    std::uint32_t WaitMs(std::uint64_t nowMs) const noexcept {
        const auto remaining = RemainingMs(nowMs);
        if (!started_ || stopped_ || attempts_ >= MaxAttempts || !remaining) return remaining;
        if (nowMs >= nextAttemptMs_) return 0;
        return static_cast<std::uint32_t>((std::min)(
            nextAttemptMs_ - nowMs, static_cast<std::uint64_t>(remaining)));
    }

private:
    bool started_ = false;
    bool stopped_ = false;
    unsigned attempts_ = 0;
    std::uint64_t deadlineMs_ = 0;
    std::uint64_t nextAttemptMs_ = 0;
};
}
