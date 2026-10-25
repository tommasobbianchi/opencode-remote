package io.github.mirkotappini.opencoderemote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4.4: the UI receives at most one snapshot per window, phase/end-of-turn events
 * pass through immediately, and the last accumulated value is not lost.
 */
class PublishGateTest {

    private val interval = 100L

    @Test
    fun `il primo snapshot passa subito`() {
        val gate = PublishGate<String>(interval)
        assertTrue(gate.offer("a", immediate = false, now = 1_000L))
        assertNull(gate.pending)
    }

    @Test
    fun `un delta dentro la finestra resta in attesa`() {
        val gate = PublishGate<String>(interval)
        gate.offer("a", immediate = false, now = 1_000L)

        assertFalse(gate.offer("b", immediate = false, now = 1_040L))
        assertEquals("b", gate.pending)
        assertEquals(60L, gate.dueIn(1_040L))
    }

    @Test
    fun `a finestra scaduta si pubblica`() {
        val gate = PublishGate<String>(interval)
        gate.offer("a", immediate = false, now = 1_000L)

        assertTrue(gate.offer("b", immediate = false, now = 1_100L))
        assertNull(gate.pending)
        assertEquals(interval, gate.dueIn(1_100L))
    }

    @Test
    fun `l evento terminale passa subito e riapre la finestra`() {
        val gate = PublishGate<String>(interval)
        gate.offer("a", immediate = false, now = 1_000L)
        gate.offer("delta", immediate = false, now = 1_030L)

        assertTrue(gate.offer("ended", immediate = true, now = 1_040L))
        assertNull("lo snapshot terminale sostituisce il pending", gate.pending)
        assertFalse(
            "dopo il terminale la finestra ricomincia",
            gate.offer("delta2", immediate = false, now = 1_050L),
        )
    }

    @Test
    fun `il pending contiene sempre l ultimo offerto`() {
        val gate = PublishGate<Int>(interval)
        gate.offer(0, immediate = false, now = 0L)
        gate.offer(1, immediate = false, now = 10L)
        gate.offer(2, immediate = false, now = 20L)

        assertEquals(2, gate.takePending())
        assertNull(gate.takePending())
    }

    @Test
    fun `scartare il pending non riapre la finestra`() {
        val gate = PublishGate<String>(interval)
        gate.offer("a", immediate = false, now = 1_000L)
        gate.offer("b", immediate = false, now = 1_010L)

        gate.dropPending()
        assertNull(gate.pending)
        assertFalse(gate.offer("c", immediate = false, now = 1_020L))
    }

    @Test
    fun `reset azzera pending e finestra`() {
        val gate = PublishGate<String>(interval)
        gate.offer("a", immediate = false, now = 1_000L)
        gate.offer("b", immediate = false, now = 1_010L)

        gate.reset()
        assertNull(gate.pending)
        assertTrue(gate.offer("c", immediate = false, now = 1_020L))
    }

    @Test
    fun `un secondo di delta a 60 Hz produce circa dieci pubblicazioni`() {
        val gate = PublishGate<Int>(interval)
        var published = 0
        // 1 second of delta every 16 ms (≈60 Hz): 63 frames.
        for (frame in 0..62) {
            val now = 1_000L + frame * 16L
            if (gate.offer(frame, immediate = false, now = now)) published += 1
        }
        // One every ~7 frames (100 ms / 16 ms), not one per frame.
        assertTrue("attese ~10 pubblicazioni, trovate $published", published in 9..11)
    }

    // --- classificazione eventi ---------------------------------------------

    @Test
    fun `solo i delta puri sono throttled`() {
        val session = "ses_1"
        val throttled = listOf(
            ServerEvent.TextDelta(session, "msg", 0, "x"),
            ServerEvent.ReasoningDelta(session, "msg", 0, "x"),
            ServerEvent.ToolInputDelta(session, "msg", "call", "x"),
            ServerEvent.ToolProgress(session, "msg", "call", null, false),
        )
        throttled.forEach { assertFalse(it.wireName(), it.publishedImmediately()) }
    }

    @Test
    fun `cambi di fase e fine turno passano subito`() {
        val session = "ses_1"
        val immediate = listOf(
            ServerEvent.TextStarted(session, "msg", 0),
            ServerEvent.TextEnded(session, "msg", 0, "testo"),
            ServerEvent.ReasoningStarted(session, "msg", 0),
            ServerEvent.ReasoningEnded(session, "msg", 0, "pensiero"),
            ServerEvent.ToolInputStarted(session, "msg", "call", "shell"),
            ServerEvent.ToolInputEnded(session, "msg", "call", "{}"),
            ServerEvent.StepStarted(session, agent = null, modelId = null, messageId = "msg"),
            ServerEvent.StepEnded(session, "msg", "stop", null, null),
            ServerEvent.StepFailed(session, "msg", "boom"),
            ServerEvent.ExecutionStarted(session),
            ServerEvent.ExecutionFinished(session, "completed"),
        )
        immediate.forEach { assertTrue(it.wireName(), it.publishedImmediately()) }
    }

    private fun ServerEvent.wireName(): String = javaClass.simpleName
}
