package io.github.mirkotappini.opencoderemote.net

/**
 * M5.1 — politica pura della riconnessione SSE.
 *
 * Il countdown deriva sempre da un istante assoluto (`nextAttemptAt`) e non da
 * un contatore decrementato a mano: così non resta bloccato a "0s" mentre il
 * socket appeso aspetta l'handshake. Con la rete assente i tentativi sono
 * sospesi e i testi non mostrano numeri.
 */
object ReconnectPolicy {

    /** Motivo unico usato da REST, callback di rete e banner. */
    const val NETWORK_ABSENT = "rete assente"

    /** Banner quando la rete manca: si attende, non si riprova. */
    const val NETWORK_WAITING = "rete assente · attendo la connessione"

    const val CONNECTION_LOST = "connessione persa"

    /** Tentativo già dovuto/partito: mai "riprovo tra 0s". */
    const val NEW_ATTEMPT = "connessione persa · nuovo tentativo"

    /** Secondi interi (ceil) mancanti a [nextAttemptAt], mai negativi. */
    fun secondsUntil(nextAttemptAt: Long, now: Long): Int {
        if (nextAttemptAt <= 0L) return 0
        val left = nextAttemptAt - now
        if (left <= 0L) return 0
        return ((left + 999L) / 1_000L).toInt()
    }

    /** Banner di lista/chat, a partire dal tempo reale al prossimo tentativo. */
    fun bannerLabel(nextAttemptAt: Long, now: Long, suspended: Boolean): String =
        bannerLabel(secondsUntil(nextAttemptAt, now), suspended)

    /**
     * Banner a partire dai secondi già derivati da `nextAttemptAt`.
     * Sospeso → testo dedicato senza numeri; tentativo dovuto → niente "0s".
     */
    fun bannerLabel(secondsLeft: Int, suspended: Boolean): String = when {
        suspended -> NETWORK_WAITING
        secondsLeft <= 0 -> NEW_ATTEMPT
        else -> "$CONNECTION_LOST · riprovo tra ${secondsLeft.coerceAtLeast(0)}s"
    }

    /** Etichetta compatta del chip (lista, chat, drawer). */
    fun statusLabel(secondsLeft: Int, suspended: Boolean): String = when {
        suspended -> NETWORK_ABSENT
        secondsLeft <= 0 -> "riconnessione"
        else -> "riconnessione · ${secondsLeft.coerceAtLeast(0)}s"
    }

    /** La rete assente è l'unico motivo che sospende i tentativi. */
    fun isNetworkAbsent(reason: String): Boolean = reason == NETWORK_ABSENT
}

/**
 * Stato puro del ciclo di retry. Le transizioni sono idempotenti: più callback
 * di rete ravvicinate producono una sola richiesta di riconnessione.
 */
data class RetryState(
    val attempt: Int = 0,
    val nextAttemptAt: Long = 0L,
    val suspended: Boolean = false,
)

object RetryTransitions {

    /** Rete persa: backoff azzerato e nessun tentativo finché non torna. */
    fun networkLost(): RetryState =
        RetryState(attempt = 0, nextAttemptAt = 0L, suspended = true)

    /**
     * Rete tornata: `null` se non c'era nulla da riprendere (callback
     * duplicata). Un valore non-null chiede una sola riconnessione.
     */
    fun networkAvailable(state: RetryState, now: Long): RetryState? =
        if (!state.suspended) {
            null
        } else {
            RetryState(attempt = 0, nextAttemptAt = now, suspended = false)
        }

    /** Programma il prossimo tentativo a un istante assoluto. */
    fun scheduled(attempt: Int, delayMs: Long, now: Long): RetryState =
        RetryState(
            attempt = attempt,
            nextAttemptAt = now + delayMs.coerceAtLeast(0L),
            suspended = false,
        )

    /** Tentativo in corso: nessun countdown attivo. */
    fun attempting(attempt: Int): RetryState =
        RetryState(attempt = attempt, nextAttemptAt = 0L, suspended = false)

    fun clear(): RetryState = RetryState()

    /**
     * Stato dopo un `markOffline(reason)`: solo "rete assente" sospende i
     * tentativi, gli altri errori di raggiungibilità lasciano il backoff.
     */
    fun offline(reason: String, previous: RetryState = RetryState()): RetryState =
        if (ReconnectPolicy.isNetworkAbsent(reason)) networkLost() else previous
}
