package io.github.mirkotappini.opencoderemote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression on the streaming contract: replays the real turn captured on patchouli 2.1.4
 * (fixture = 38 SSE lines, 15 events) and checks the reduction produces "pong".
 */
class RealTurnContractTest {

    private fun lines(): List<String> =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/sse_run.jsonl")) {
            "fixture mancante"
        }.bufferedReader().use { it.readLines() }

    @Test
    fun `real turn reduced down to pong`() {
        val sessionId = "ses_e953f10bfffeofk10zuK5BMVjf"
        var state = OpenChatState(sessionId = sessionId)
        var parsed = 0
        var skipped = 0

        lines().forEach { raw ->
            if (!raw.startsWith("data: ")) return@forEach // ": heartbeat" e righe vuote
            val json = raw.removePrefix("data: ")
            val incoming = EventParsing.parse(json)
            if (incoming == null) {
                skipped++
                return@forEach
            }
            parsed++
            state = ChatReducer.reduce(state, incoming.event)
        }

        println("REAL parsed=$parsed skipped=$skipped running=${state.running} outcome=${state.outcome}")
        val assistant = state.messages.filter { it.role == Role.Assistant }
        println("REAL live=${state.stream.live?.text} persisted=${assistant.map { it.text }}")

        assertEquals("no turn event may be lost", 15, parsed)
        assertFalse("at end of turn it is no longer running", state.running)
        assertEquals("succeeded", state.outcome)
        assertEquals("the text survives the end of the turn", 1, assistant.size)
        assertEquals("the text is the final one", "pong", assistant.single().text)
        assertTrue("the live has been closed", state.stream.live == null)
    }

    @Test
    fun `reconciliation of an optimistic send against the server truth`() {
        val serverUser = MessageUi(
            id = "msg_server_1",
            role = Role.User,
            text = "Rispondi esattamente: pong",
            createdAt = 1_000_000L,
        )
        val serverAssistant = MessageUi(
            id = "msg_server_2",
            role = Role.Assistant,
            text = "pong",
            createdAt = 1_000_500L,
        )
        val pendingSameId = serverUser.copy(pending = true)
        val pendingDuplicateByText = MessageUi(
            id = "msg_local_ottimistico",
            role = Role.User,
            text = "Rispondi esattamente: pong",
            createdAt = 1_000_300L,
            pending = true,
        )
        val pendingDavveroNuovo = MessageUi(
            id = "msg_local_nuovo",
            role = Role.User,
            text = "secondo messaggio",
            createdAt = 1_002_000L,
            pending = true,
        )

        val merged = reconcileMessages(
            server = listOf(serverUser, serverAssistant),
            local = listOf(serverUser.copy(pending = false), pendingSameId, pendingDuplicateByText, pendingDavveroNuovo),
        )

        println("RECONCILE size=${merged.size} ids=${merged.map { it.id }}")
        assertEquals("no duplicate and the new pending remains", 3, merged.size)
        assertNotNull(merged.firstOrNull { it.id == "msg_local_nuovo" })
        assertTrue(merged.none { it.id == "msg_local_ottimistico" && it.pending })
    }

    @Test
    fun `unknown event does not break the reduction`() {
        val raw = """{"id":"evt_x","created":1,"type":"session.tool.started","data":{"sessionID":"ses_1","tool":"bash"}}"""
        val incoming = EventParsing.parse(raw)
        println("UNKNOWN -> ${incoming?.event?.javaClass?.simpleName}")
        assertNotNull(incoming)
        assertTrue(incoming!!.event is ServerEvent.Unknown)
        val after = ChatReducer.reduce(OpenChatState("ses_1"), incoming.event)
        assertEquals(0, after.messages.size)
    }
}
