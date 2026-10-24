package io.github.mirkotappini.opencoderemote.net

/**
 * Throttle di pubblicazione degli snapshot live (§4.4 di `docs/UI-M6-LIVE.md`).
 *
 * Il reducer accumula i delta senza costo di rendering; questo cancello decide
 * quando lo snapshot arriva alla UI: al massimo uno ogni [intervalMs], tranne
 * gli eventi terminali (`immediate = true`) che passano subito e riaprono la
 * finestra. L'ultimo valore offerto non viene mai perso: resta in [pending]
 * finché qualcuno non lo preleva con [takePending] o lo scarta con
 * [dropPending].
 *
 * Puro e senza orologio interno (`now` è un parametro) per essere testabile
 * sulla JVM.
 */
internal class PublishGate<T>(private val intervalMs: Long) {

    private var lastPublishAt = NEVER
    private var held: T? = null

    /** Snapshot accumulato e non ancora pubblicato. */
    val pending: T? get() = held

    /**
     * Offre uno snapshot.
     *
     * @return `true` = pubblicare subito [value]; `false` = tenerlo in
     *   [pending] e pubblicarlo fra [dueIn] ms.
     */
    fun offer(value: T, immediate: Boolean, now: Long): Boolean {
        if (immediate || dueIn(now) == 0L) {
            held = null
            lastPublishAt = now
            return true
        }
        held = value
        return false
    }

    /** Millisecondi che mancano alla fine della finestra corrente (0 = libero). */
    fun dueIn(now: Long): Long {
        if (lastPublishAt == NEVER) return 0L
        return (lastPublishAt + intervalMs - now).coerceAtLeast(0L)
    }

    /** Prende lo snapshot accumulato (o `null` se non c'è). Non tocca la finestra. */
    fun takePending(): T? {
        val value = held
        held = null
        return value
    }

    /** Scarta lo snapshot accumulato mantenendo la finestra corrente. */
    fun dropPending() {
        held = null
    }

    /** Azzera tutto: pending e finestra. */
    fun reset() {
        held = null
        lastPublishAt = NEVER
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
