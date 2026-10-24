package io.github.mirkotappini.opencoderemote.net

/**
 * M5.1 — pure SSE reconnection policy.
 *
 * The countdown is always derived from an absolute instant (`nextAttemptAt`),
 * never from a manually decremented counter: this keeps it from getting stuck
 * at "0s" while a hung socket waits for the handshake. When the network is
 * absent the attempts are suspended and the texts show no numbers.
 */
object ReconnectPolicy {

    /** Single reason shared by REST, network callbacks and the banner. */
    const val NETWORK_ABSENT = "no network"

    /** Banner when the network is down: wait, do not retry. */
    const val NETWORK_WAITING = "no network · waiting for connection"

    const val CONNECTION_LOST = "connection lost"

    /** Attempt already due/started: never "retrying in 0s". */
    const val NEW_ATTEMPT = "connection lost · new attempt"

    /** Whole seconds (ceil) left until [nextAttemptAt], never negative. */
    fun secondsUntil(nextAttemptAt: Long, now: Long): Int {
        if (nextAttemptAt <= 0L) return 0
        val left = nextAttemptAt - now
        if (left <= 0L) return 0
        return ((left + 999L) / 1_000L).toInt()
    }

    /** List/chat banner, counting live time to the next attempt. */
    fun bannerLabel(nextAttemptAt: Long, now: Long, suspended: Boolean): String =
        bannerLabel(secondsUntil(nextAttemptAt, now), suspended)

    /**
     * Banner derived from the seconds already computed from `nextAttemptAt`.
     * Suspended → dedicated text without numbers; attempt due → no "0s".
     */
    fun bannerLabel(secondsLeft: Int, suspended: Boolean): String = when {
        suspended -> NETWORK_WAITING
        secondsLeft <= 0 -> NEW_ATTEMPT
        else -> "$CONNECTION_LOST · retrying in ${secondsLeft.coerceAtLeast(0)}s"
    }

    /** Compact label for the chip (list, chat, drawer). */
    fun statusLabel(secondsLeft: Int, suspended: Boolean): String = when {
        suspended -> NETWORK_ABSENT
        secondsLeft <= 0 -> "reconnecting"
        else -> "reconnecting · ${secondsLeft.coerceAtLeast(0)}s"
    }

    /** A missing network is the only reason that suspends attempts. */
    fun isNetworkAbsent(reason: String): Boolean = reason == NETWORK_ABSENT
}

/**
 * Pure state of the retry cycle. Transitions are idempotent: several close
 * network callbacks produce a single reconnection request.
 */
data class RetryState(
    val attempt: Int = 0,
    val nextAttemptAt: Long = 0L,
    val suspended: Boolean = false,
)

object RetryTransitions {

    /** Network lost: backoff reset and no attempts until it returns. */
    fun networkLost(): RetryState =
        RetryState(attempt = 0, nextAttemptAt = 0L, suspended = true)

    /**
     * Network recovered: `null` if there was nothing to resume (duplicate
     * callback). A non-null value requests a single reconnection.
     */
    fun networkAvailable(state: RetryState, now: Long): RetryState? =
        if (!state.suspended) {
            null
        } else {
            RetryState(attempt = 0, nextAttemptAt = now, suspended = false)
        }

    /** Schedules the next attempt at an absolute instant. */
    fun scheduled(attempt: Int, delayMs: Long, now: Long): RetryState =
        RetryState(
            attempt = attempt,
            nextAttemptAt = now + delayMs.coerceAtLeast(0L),
            suspended = false,
        )

    /** Attempt in progress: no active countdown. */
    fun attempting(attempt: Int): RetryState =
        RetryState(attempt = attempt, nextAttemptAt = 0L, suspended = false)

    fun clear(): RetryState = RetryState()

    /**
     * State after a `markOffline(reason)`: only "network down" suspends the
     * attempts, other reachability errors leave the backoff intact.
     */
    fun offline(reason: String, previous: RetryState = RetryState()): RetryState =
        if (ReconnectPolicy.isNetworkAbsent(reason)) networkLost() else previous
}
