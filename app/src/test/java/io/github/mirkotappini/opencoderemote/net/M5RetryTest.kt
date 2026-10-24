package io.github.mirkotappini.opencoderemote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5.1 — test JVM offline del rilevamento rete e del countdown.
 *
 * Il ViewModel usa queste transizioni pure: `markOffline("rete assente")`
 * sospende, `onAvailable` riparte una sola volta, il countdown deriva da
 * `nextAttemptAt` e il watchdog SSE scatta a 30 s.
 */
class M5RetryTest {

    // 1. Rete assente ---------------------------------------------------------

    @Test
    fun `markOffline rete assente sospende i tentativi e usa il testo dedicato`() {
        val now = 1_700_000_000_000L
        val state = RetryTransitions.offline(ReconnectPolicy.NETWORK_ABSENT)

        assertTrue(state.suspended)
        assertEquals(0, state.attempt)
        assertEquals(0L, state.nextAttemptAt)

        val banner = ReconnectPolicy.bannerLabel(state.nextAttemptAt, now, state.suspended)
        assertEquals(ReconnectPolicy.NETWORK_WAITING, banner)
        assertFalse("il banner sospeso non deve contenere numeri", banner.any { it.isDigit() })
        assertEquals(ReconnectPolicy.NETWORK_ABSENT, ReconnectPolicy.statusLabel(0, true))

        // Un errore di raggiungibilità diverso non sospende il backoff.
        val other = RetryTransitions.offline("server non raggiungibile")
        assertFalse(other.suspended)
    }

    // 2. Ritorno della rete ---------------------------------------------------

    @Test
    fun `onAvailable azzera il backoff e chiede una sola riconnessione`() {
        var state = RetryTransitions.offline(ReconnectPolicy.NETWORK_ABSENT)
        var requests = 0

        // Callback ravvicinate: solo la prima transizione chiede il restart.
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

        // Con lo stato già ripartito non si chiede nulla.
        assertNull(RetryTransitions.networkAvailable(state, 2_000L))
        assertNotNull(RetryTransitions.networkAvailable(RetryTransitions.networkLost(), 3_000L))
    }

    // 3. Countdown ------------------------------------------------------------

    @Test
    fun `countdown deriva da nextAttemptAt e non resta a zero`() {
        val now = 10_000L
        val next = now + 8_000L

        val shown = (0..8).map { elapsedSeconds ->
            ReconnectPolicy.secondsUntil(next, now + elapsedSeconds * 1_000L)
        }
        assertEquals(listOf(8, 7, 6, 5, 4, 3, 2, 1, 0), shown)
        assertEquals(
            "connessione persa · riprovo tra 8s",
            ReconnectPolicy.bannerLabel(next, now, suspended = false),
        )

        // A metà secondo il ceil evita lo "0s" prematuro.
        assertEquals(1, ReconnectPolicy.secondsUntil(next, next - 200L))

        // Al momento del tentativo il testo non dice più "riprovo tra 0s".
        val due = ReconnectPolicy.bannerLabel(next, next, suspended = false)
        assertEquals(ReconnectPolicy.NEW_ATTEMPT, due)
        assertFalse(due.contains("0s"))
        assertEquals("riconnessione", ReconnectPolicy.statusLabel(0, suspended = false))
    }

    // 4. Watchdog SSE ---------------------------------------------------------

    @Test
    fun `watchdog SSE a 30 secondi riconnette dopo 31s di silenzio`() {
        assertEquals(30_000L, SseWatchdog.TIMEOUT_MS)

        val last = 42_000L
        assertFalse(SseWatchdog.expiredAfter(last, 29_000L))
        assertTrue(SseWatchdog.expiredAfter(last, 31_000L))
        assertTrue(SseWatchdog.expiredAfter(last, 60_000L))
        assertFalse(SseWatchdog.isStale(last, last + 30_000L))
    }
}
