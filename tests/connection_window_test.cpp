#include "connection_window.h"
#include <iostream>
#include <stdexcept>

using lockpin::phone::ConnectionWindow;
void Require(bool ok, const char* message) {
    if (!ok) throw std::runtime_error(message);
}

int main() {
    try {
        // No radio: no connection attempt, countdown, or periodic wake. Its
        // arrival starts one window even if duplicate notifications follow.
        ConnectionWindow boot;
        Require(!boot.CanAttempt(50000) && boot.WaitMs(50000) == ConnectionWindow::Forever,
            "Unavailable radio must wait for an event");
        boot.Start(50000);
        Require(boot.BeginAttempt(50000) == 8000, "Radio arrival starts a connection");
        boot.Failed(58000);
        boot.Start(59000);
        Require(boot.RemainingMs(59000) == 51000, "Duplicate arrivals must not extend the window");
        Require(boot.WaitMs(58000) == 5000 && !boot.CanAttempt(62999), "First retry backs off five seconds");
        Require(boot.WaitMs(62001) == 999, "Countdown must use the absolute deadline");
        Require(ConnectionWindow::CountdownSeconds(boot.WaitMs(58000)) == 5 &&
            ConnectionWindow::CountdownSeconds(boot.WaitMs(59000)) == 4 &&
            ConnectionWindow::CountdownSeconds(boot.WaitMs(62001)) == 1 &&
            ConnectionWindow::CountdownSeconds(boot.WaitMs(63000)) == 0,
            "UI countdown must round up, reach zero, and not drift between refreshes");
        Require(boot.BeginAttempt(63000) == 8000, "Delayed phone gets another connection");
        boot.StopAttempts();
        Require(!boot.CanAttempt(90000), "Connected transport must not resend authentication requests");

        // Long failures: backoff grows, and the final connection timeout is
        // capped by the original sixty-second deadline rather than extending it.
        ConnectionWindow slow;
        slow.Start(1000);
        Require(slow.BeginAttempt(1000) == 8000, "First attempt");
        slow.Failed(9000);
        Require(slow.BeginAttempt(14000) == 8000, "First delayed attempt");
        slow.Failed(22000);
        Require(slow.WaitMs(22000) == 10000 && !slow.CanAttempt(31999), "Ten-second backoff");
        Require(slow.BeginAttempt(32000) == 8000, "Second delayed attempt");
        slow.Failed(40000);
        Require(slow.WaitMs(40000) == 20000 && !slow.CanAttempt(59999), "Twenty-second backoff");
        Require(slow.BeginAttempt(60000) == 1000, "Last attempt must fit the remaining window");
        slow.Failed(61000);
        Require(slow.Expired(61000) && !slow.CanAttempt(61000) && slow.WaitMs(61000) == 0,
            "Expired request cannot reconnect");

        // Immediate errors also exhaust a finite budget; no busy loop or fifth
        // attempt is allowed even if most of the window remains.
        ConnectionWindow absent;
        absent.Start(0);
        std::uint64_t now = 0;
        for (unsigned attempt = 1; attempt <= ConnectionWindow::MaxAttempts; ++attempt) {
            Require(absent.BeginAttempt(now) > 0 && absent.Attempts() == attempt, "Bounded attempt sequence");
            absent.Failed(now);
            if (attempt < ConnectionWindow::MaxAttempts) {
                const auto delay = absent.WaitMs(now);
                Require(delay > 0 && !absent.CanAttempt(now), "No immediate retry after failure");
                now += delay;
            }
        }
        Require(!absent.HasPendingAttempt() && absent.BeginAttempt(now + 1) == 0, "No fifth attempt");
        absent = ConnectionWindow{}; // Explicit resend creates a fresh request.
        absent.Start(now);
        Require(absent.BeginAttempt(now) == 8000 && absent.Attempts() == 1, "Manual resend resets the budget");

        ConnectionWindow legacy;
        legacy.Start(0);
        legacy.StopAttempts();
        Require(!legacy.CanAttempt(1000) && legacy.WaitMs(1000) == 59000,
            "Legacy BLE wait must not invent a direct route");
        Require(ConnectionWindow::LegacyFallbackMs < ConnectionWindow::WindowMs,
            "Legacy advertising has its own shorter deadline");
        std::cout << "PASS: radio event start, duplicate events, countdown deadline, growing backoff, overall timeout, attempt limit, manual resend, legacy wait\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
