package io.github.mirkotappini.opencoderemote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5.1 — offline JVM tests of network detection and countdown.
 *
 * The ViewModel uses these pure transitions: `markOffline("no network")`
 * suspends, `onAvailable` restarts only once, the countdown derives from
 * `nextAttemptAt` e il watchdog SSE scatta a 30 s.
 */
class M5RetryTest {

    // 1. Rete assente ---------------------------------------------------------

    @Test
    fun `markOffline no network suspends attempts and uses the dedicated text`() {
        val now = 1_700_000_000_000L
        val state = RetryTransitions.offline(ReconnectPolicy.NETWORK_ABSENT)

        assertTrue(state.suspended)
        assertEquals(0, state.attempt)
        assertEquals(0L, state.nextAttemptAt)

        val banner = ReconnectPolicy.bannerLabel(state.nextAttemptAt, now, state.suspended)
        assertEquals(ReconnectPolicy.NETWORK_WAITING, banner)
        assertFalse("the suspended banner must not contain numbers", banner.any { it.isDigit() })
        assertEquals(ReconnectPolicy.NETWORK_ABSENT, ReconnectPolicy.statusLabel(0, true))

        // A different reachability error does not suspend backoff.
        val other = RetryTransitions.offline("server unreachable")
        assertFalse(other.suspended)
    }

    // 2. Network return ---------------------------------------------------

    @Test
    fun `onAvailable resets backoff and asks for a single reconnect`() {
        var state = RetryTransitions.offline(ReconnectPolicy.NETWORK_ABSENT)
        var requests = 0

        // Close callbacks: only the first transition asks for restart.
        listOf(1_000L, 1_050L, 1_100L).forEach { now ->
            RetryTransitions.networkAvailable(state, now)?.let { resumed ->
                requests += 1
                state = resumed
            }
        }

        assertEquals(1, requests)
        assertFalse(state.suspended)
        assertEquals("backoff azzerato", 0, state.attempt)
        assertEquals("nessuna attesa residua", 0L, state.nextAttemptAt - 1_000L)

        // With the state already restarted nothing is asked.
        assertNull(RetryTransitions.networkAvailable(state, 2_000L))
        assertNotNull(RetryTransitions.networkAvailable(RetryTransitions.networkLost(), 3_000L))
    }

    // 3. Countdown ------------------------------------------------------------

    @Test
    fun `countdown derives from nextAttemptAt and does not stay at zero`() {
        val now = 10_000L
        val next = now + 8_000L

        val shown = (0..8).map { elapsedSeconds ->
            ReconnectPolicy.secondsUntil(next, now + elapsedSeconds * 1_000L)
        }
        assertEquals(listOf(8, 7, 6, 5, 4, 3, 2, 1, 0), shown)
        assertEquals(
            "connection lost · retrying in 8s",
            ReconnectPolicy.bannerLabel(next, now, suspended = false),
        )

        // At half a second the ceil avoids a premature "0s".
        assertEquals(1, ReconnectPolicy.secondsUntil(next, next - 200L))

        // At attempt time the text no longer says "retrying in 0s".
        val due = ReconnectPolicy.bannerLabel(next, next, suspended = false)
        assertEquals(ReconnectPolicy.NEW_ATTEMPT, due)
        assertFalse(due.contains("0s"))
        assertEquals("reconnecting", ReconnectPolicy.statusLabel(0, suspended = false))
    }

    // 4. Watchdog SSE ---------------------------------------------------------

    @Test
    fun `SSE watchdog at 30 seconds reconnects after 31s of silence`() {
        assertEquals(30_000L, SseWatchdog.TIMEOUT_MS)

        val last = 42_000L
        assertFalse(SseWatchdog.expiredAfter(last, 29_000L))
        assertTrue(SseWatchdog.expiredAfter(last, 31_000L))
        assertTrue(SseWatchdog.expiredAfter(last, 60_000L))
        assertFalse(SseWatchdog.isStale(last, last + 30_000L))
    }
}
