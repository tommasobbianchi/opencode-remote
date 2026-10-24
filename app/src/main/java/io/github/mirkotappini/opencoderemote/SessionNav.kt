package io.github.mirkotappini.opencoderemote

import io.github.mirkotappini.opencoderemote.net.SessionUi

/**
 * One level of the navigation stack: the session that was open before
 * entering a child task, with the mode it was opened with.
 *
 * Pure logic (no Android dependency) so the stack can be tested
 * offline. The ViewModel uses it for `openSession`/`back`; the UI only reads
 * `UiState.canGoBackToParent`.
 */
data class SessionFrame(
    val session: SessionUi,
    val readOnly: Boolean = false,
)

/** Outcome of an open: the stack to save and the mode of the requested session. */
data class SessionOpenPlan(
    val stack: List<SessionFrame>,
    val readOnly: Boolean,
)

/** Outcome of a `back()`: frame to reopen (null = list) and the stack left. */
data class SessionPopPlan(
    val frame: SessionFrame?,
    val stack: List<SessionFrame>,
)

/**
 * Parent → child stack rules:
 *  - open from the list (no session open): empty stack, writable;
 *  - open with a session already open: the current one is pushed and the
 *    new session is a child, therefore read-only;
 *  - same id as the open session (retry, refresh, notification): no
 *    duplicate in the stack;
 *  - id already present further down the stack: return to that level without
 *    creating cycles (A → B → A does not leave B pushed above A);
 *  - `back`: reopens the last pushed frame; empty stack = list.
 */
object SessionNav {

    fun planOpen(
        stack: List<SessionFrame>,
        current: SessionFrame?,
        nextSession: SessionUi,
    ): SessionOpenPlan {
        // From the list: the stack starts over, the session is writable.
        if (current == null) return SessionOpenPlan(emptyList(), readOnly = false)

        // Retry/refresh of the same session: stack and mode are unchanged.
        if (current.session.id == nextSession.id) {
            return SessionOpenPlan(stack, readOnly = current.readOnly)
        }

        // Jump to an ancestor: the levels above it are closed.
        val ancestor = stack.indexOfLast { it.session.id == nextSession.id }
        if (ancestor >= 0) {
            return SessionOpenPlan(
                stack = stack.take(ancestor),
                readOnly = stack[ancestor].readOnly,
            )
        }

        // New child: the current session stays in the stack, the child is read-only.
        return SessionOpenPlan(stack + current, readOnly = true)
    }

    fun pop(stack: List<SessionFrame>): SessionPopPlan =
        if (stack.isEmpty()) {
            SessionPopPlan(frame = null, stack = emptyList())
        } else {
            SessionPopPlan(frame = stack.last(), stack = stack.dropLast(1))
        }

    /** `backToList()` and `disconnect()` always reset the stack. */
    fun cleared(): List<SessionFrame> = emptyList()

    fun canGoBackToParent(stack: List<SessionFrame>): Boolean = stack.isNotEmpty()
}
