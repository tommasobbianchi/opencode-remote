package io.github.mirkotappini.opencoderemote.net

import kotlin.random.Random

/**
 * Backoff esponenziale della riconnessione SSE: 1s, 2s, 4s, … max 30s,
 * con jitter additivo fino al 20% del valore corrente.
 */
object Backoff {

    const val BASE_MS = 1_000L
    const val MAX_MS = 30_000L

    /** Valore nominale per il tentativo `attempt` (1-based), senza jitter. */
    fun baseDelayMs(attempt: Int): Long {
        if (attempt <= 1) return BASE_MS
        var delay = BASE_MS
        repeat(attempt - 1) {
            delay = (delay * 2).coerceAtMost(MAX_MS)
        }
        return delay
    }

    /** Ritardo effettivo: nominale + jitter, mai oltre [MAX_MS]. */
    fun delayMs(attempt: Int, random: Random = Random.Default): Long {
        val base = baseDelayMs(attempt)
        if (base >= MAX_MS) return MAX_MS
        val jitter = random.nextLong(0L, base / 5L + 1L)
        return (base + jitter).coerceAtMost(MAX_MS)
    }
}
