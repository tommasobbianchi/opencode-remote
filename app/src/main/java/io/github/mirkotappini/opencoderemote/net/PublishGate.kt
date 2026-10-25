package io.github.mirkotappini.opencoderemote.net

/**
 * Publishing throttle for live snapshots (§4.4 of `docs/UI-M6-LIVE.md`).
 *
 * The reducer accumulates deltas with no rendering cost; this gate decides when the
 * snapshot reaches the UI: at most one every [intervalMs], except terminal events
 * (`immediate = true`) which pass through immediately and reset the window. The
 * last offered value is never lost: it stays in [pending] until someone retrieves
 * it with [takePending] or discards it with [dropPending].
 *
 * Pure and clock-free (`now` is a parameter) to be testable on the JVM.
 */
internal class PublishGate<T>(private val intervalMs: Long) {

    private var lastPublishAt = NEVER
    private var held: T? = null

    /** Accumulated snapshot not yet published. */
    val pending: T? get() = held

    /**
     * Provides a snapshot.
     *
     * @return `true` = publish [value] immediately; `false` = keep it in [pending]
     * and publish it after [dueIn] ms.
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

    /** Milliseconds remaining until the end of the current window (0 = free). */
    fun dueIn(now: Long): Long {
        if (lastPublishAt == NEVER) return 0L
        return (lastPublishAt + intervalMs - now).coerceAtLeast(0L)
    }

    /**
     * Takes the accumulated snapshot (or `null` if none). Does not touch the
     * window.
     */
    fun takePending(): T? {
        val value = held
        held = null
        return value
    }

    /** Discard the accumulated snapshot, keeping the current window. */
    fun dropPending() {
        held = null
    }

    /** Reset everything: pending and window. */
    fun reset() {
        held = null
        lastPublishAt = NEVER
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
