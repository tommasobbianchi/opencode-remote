package io.github.mirkotappini.opencoderemote.net

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test JVM di M2: eventi, accumulo streaming, dedupe, backoff e isolamento
 * between sessions. All offline, inline payloads taken from docs/API-M2.md §2.
 */
class M2Test {

    // 1. Delta accumulation over multiple ordinals and close on text.ended ---------------

    @Test
    fun `delta su piu ordinal accumulati e chiusura su text ended`() {
        var state = OpenChatState(sessionId = "ses_1")

        state = ChatReducer.reduce(
            state,
            ServerEvent.TextStarted("ses_1", "msg_a", ordinal = 0),
        )
        state = ChatReducer.reduce(
            state,
            ServerEvent.TextDelta("ses_1", "msg_a", ordinal = 0, delta = "po"),
        )
        state = ChatReducer.reduce(
            state,
            ServerEvent.TextDelta("ses_1", "msg_a", ordinal = 0, delta = "ng"),
        )
        state = ChatReducer.reduce(
            state,
            ServerEvent.TextStarted("ses_1", "msg_a", ordinal = 1),
        )
        state = ChatReducer.reduce(
            state,
            ServerEvent.TextDelta("ses_1", "msg_a", ordinal = 1, delta = "secondo"),
        )

        val live = state.stream.live
        assertEquals("msg_a", live?.messageId)
        assertEquals(listOf(0, 1), live?.parts?.map { it.ordinal })
        assertEquals("pong\nsecondo", live?.text)

        state = ChatReducer.reduce(
            state,
            ServerEvent.TextEnded("ses_1", "msg_a", ordinal = 0, text = "PONG"),
        )

        val part0 = state.stream.live?.parts?.first { it.ordinal == 0 }
        assertEquals("PONG", part0?.text)
        assertTrue(part0?.ended == true)
        assertTrue(state.stream.closedParts.contains("msg_a" to 0))

        // A late delta on the closed part does not dirty the final text.
        state = ChatReducer.reduce(
            state,
            ServerEvent.TextDelta("ses_1", "msg_a", ordinal = 0, delta = "-late"),
        )
        assertEquals("PONG\nsecondo", state.stream.live?.text)
    }

    // 2. Type sconosciuto: mai eccezioni --------------------------------------

    @Test
    fun `unknown type becomes Unknown without exceptions`() {
        // Real payload from the M2 sequence (§2), with an unmodeled type.
        val raw = """
            {"id":"evt_1792792392494","created":1792792392494,
             "type":"session.instructions.updated",
             "location":{"directory":"/Users/patchouli"},
             "data":{"sessionID":"ses_...","delta":"abc123"},
             "durable":{"aggregateID":"ses_...","seq":7,"version":1}}
        """.trimIndent()

        val parsed = EventParsing.parse(raw)
        val event = parsed?.event
        assertTrue(event is ServerEvent.Unknown)
        assertEquals("session.instructions.updated", (event as ServerEvent.Unknown).type)
        assertEquals("ses_...", event.sessionId)

        // Even a non-JSON payload must not crash the parser.
        assertNull(EventParsing.parse("{non-json"))
        assertNull(EventParsing.parseEvent(""))
    }

    @Test
    fun `known event with delta is normalized`() {
        val raw = """
            {"id":"evt_1","created":1792792392493,"type":"session.text.delta",
             "location":{"directory":"/Users/patchouli"},
             "data":{"sessionID":"ses_1","assistantMessageID":"msg_a","ordinal":0,
                     "delta":"pong"},
             "durable":{"aggregateID":"ses_1","seq":6,"version":1}}
        """.trimIndent()

        val event = EventParsing.parseEvent(raw)
        assertEquals(ServerEvent.TextDelta("ses_1", "msg_a", 0, "pong"), event)

        // Without a sessionID the session event is dropped.
        val withoutSession = """{"type":"session.text.delta","data":{"delta":"x"}}"""
        assertNull(EventParsing.parseEvent(withoutSession))
    }

    // 3. Dedupe after resync and optimistic replacement --------------------

    @Test
    fun `resync does not duplicate and replaces the optimistic message`() {
        val server = listOf(
            MessageUi(id = "msg_u", role = Role.User, text = "ciao", createdAt = 1_000L),
            MessageUi(id = "msg_a", role = Role.Assistant, text = "risposta", createdAt = 2_000L),
        )
        val local = listOf(
            // same text, provisional id: must be discarded in favor of the server
            MessageUi(
                id = "local-1",
                role = Role.User,
                text = "ciao",
                createdAt = 1_500L,
                pending = true,
            ),
            // same id but old content: the server version wins
            MessageUi(id = "msg_a", role = Role.Assistant, text = "vecchia", createdAt = 2_000L),
            // pending not yet on the server: stays queued
            MessageUi(
                id = "local-2",
                role = Role.User,
                text = "nuovo",
                createdAt = 3_000L,
                pending = true,
            ),
        )

        val merged = reconcileMessages(server, local)

        assertEquals(3, merged.size)
        assertEquals(1, merged.count { it.text == "ciao" })
        assertEquals("risposta", merged.first { it.id == "msg_a" }.text)
        assertFalse(merged.any { it.id == "local-1" })
        assertTrue(merged.any { it.id == "local-2" })

        // Doppio resync con la stessa lista: nessuna crescita.
        assertEquals(merged.size, reconcileMessages(server, merged).size)
    }

    // 4. Backoff 1,2,4,…,30 con jitter nei limiti -----------------------------

    @Test
    fun `backoff esponenziale con jitter nei limiti`() {
        val nominal = (1..7).map { Backoff.baseDelayMs(it) }
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            nominal,
        )

        val random = Random(42)
        (1..30).forEach { attempt ->
            val base = Backoff.baseDelayMs(attempt)
            val delay = Backoff.delayMs(attempt, random)
            assertTrue("delay $delay < base $base", delay >= base)
            assertTrue("delay $delay above the cap", delay <= Backoff.MAX_MS)
            val maxJitter = if (base >= Backoff.MAX_MS) 0L else base / 5L
            assertTrue("jitter eccessivo a $attempt", delay <= base + maxJitter)
        }
    }

    // 5. Another session's event does not touch the open chat -----------------

    @Test
    fun `another session's event does not touch the open chat`() {
        val before = OpenChatState(
            sessionId = "ses_1",
            messages = listOf(
                MessageUi(id = "msg_u", role = Role.User, text = "ciao", createdAt = 1_000L),
            ),
            stream = ChatStream(
                liveTurn = AssistantTurnUi(
                    id = "msg_a",
                    parts = listOf(TextPartUi("text:0", 0, PartPhase.Done, "par")),
                    running = true,
                ),
            ).let { it.copy(closedParts = setOf("msg_a" to 0)) },
            running = true,
        )

        val afterDelta = ChatReducer.reduce(
            before,
            ServerEvent.TextDelta("ses_2", "msg_other", 0, "intruso"),
        )
        val afterFinish = ChatReducer.reduce(
            before,
            ServerEvent.ExecutionFinished("ses_2", "failed"),
        )
        val afterStart = ChatReducer.reduce(
            before,
            ServerEvent.ExecutionStarted("ses_2"),
        )

        assertEquals(before, afterDelta)
        assertEquals(before, afterFinish)
        assertEquals(before, afterStart)
        assertEquals("par", afterDelta.stream.live?.text)
        assertTrue(afterDelta.running)
    }
}
