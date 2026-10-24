package io.github.mirkotappini.opencoderemote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-M6 §3/§7 data contract: live turn with interleaved reasoning, tools and
 * text, idempotency, late/duplicate events and the REST → parts mapping.
 */
class LiveTurnTest {

    // --- real fixtures -------------------------------------------------------

    @Test
    fun `shell tool fixture produces a structured tool part then the text`() {
        val state = replayFixture("fixtures/sse_tool.jsonl")

        val assistant = state.messages.filter { it.role == Role.Assistant }
        val toolTurns = assistant.filter { message ->
            message.parts.any { it is ToolPartUi }
        }
        assertEquals("exactly one turn with the tool", 1, toolTurns.size)

        val tool = toolTurns.single().parts.filterIsInstance<ToolPartUi>().single()
        assertEquals("shell", tool.name)
        assertEquals("uname -s", tool.command)
        assertEquals("Darwin", tool.output)
        assertEquals(0, tool.exitCode)
        assertEquals(PartPhase.Ok, tool.phase)
        // `executed` is normalized for the UI: false only for an explicit denial.
        assertTrue("executed=false must not mark a completed tool as denied", tool.executed)
        assertFalse(tool.truncated)
        assertEquals("tool:call_00_ET_i42WHK1I2MY3ACf91rWw4200", tool.key)

        // The next step delivers the final text, after the tool turn.
        val textTurn = assistant.last()
        assertTrue("text arrives after the tool", assistant.indexOf(textTurn) > assistant.indexOf(toolTurns.single()))
        val finalText = textTurn.parts.filterIsInstance<TextPartUi>().single()
        assertEquals("`Darwin`", finalText.text)
        assertEquals(PartPhase.Done, finalText.phase)
        assertNull("at end of turn the live turn is closed", state.stream.liveTurn)
    }

    @Test
    fun `reasoning fixture accumulates deltas and the final server text wins`() {
        val state = replayFixture("fixtures/sse_reasoning.jsonl")

        val assistant = state.messages.single { it.role == Role.Assistant }
        val reasoning = assistant.parts.filterIsInstance<ReasoningPartUi>().single()
        val text = assistant.parts.filterIsInstance<TextPartUi>().single()

        assertTrue(reasoning.text.startsWith("Simple arithmetic. Direct work."))
        assertTrue(reasoning.text.contains("Round to 25.67."))
        assertEquals(PartPhase.Done, reasoning.phase)
        assertEquals(0, reasoning.order)
        assertEquals(1, text.order)
        assertTrue(text.text.contains("**Totale: 25,67 €**"))
        assertEquals(PartPhase.Done, text.phase)
        assertNull(state.stream.liveTurn)
    }

    @Test
    fun `reasoning ended sostituisce i delta accumulati`() {
        var state = OpenChatState("ses_1")
        state = ChatReducer.reduce(state, ServerEvent.StepStarted("ses_1", null, null, "msg_a"))
        state = ChatReducer.reduce(state, ServerEvent.ReasoningStarted("ses_1", "msg_a", 0))
        state = ChatReducer.reduce(state, ServerEvent.ReasoningDelta("ses_1", "msg_a", 0, "delta "))
        state = ChatReducer.reduce(state, ServerEvent.ReasoningDelta("ses_1", "msg_a", 0, "accumulato"))
        state = ChatReducer.reduce(state, ServerEvent.ReasoningEnded("ses_1", "msg_a", 0, "testo finale"))

        val reasoning = state.stream.liveTurn!!.parts.filterIsInstance<ReasoningPartUi>().single()
        assertEquals("testo finale", reasoning.text)
        assertEquals(PartPhase.Done, reasoning.phase)
    }

    @Test
    fun `closing reasoning does not block text with the same ordinal`() {
        var state = OpenChatState("ses_1")
        state = ChatReducer.reduce(state, ServerEvent.StepStarted("ses_1", null, null, "msg_a"))
        state = ChatReducer.reduce(state, ServerEvent.ReasoningStarted("ses_1", "msg_a", 0))
        state = ChatReducer.reduce(state, ServerEvent.ReasoningDelta("ses_1", "msg_a", 0, "penso"))
        state = ChatReducer.reduce(state, ServerEvent.ReasoningEnded("ses_1", "msg_a", 0, "penso"))
        state = ChatReducer.reduce(state, ServerEvent.TextStarted("ses_1", "msg_a", 0))
        state = ChatReducer.reduce(state, ServerEvent.TextDelta("ses_1", "msg_a", 0, "risposta"))

        val text = state.stream.liveTurn!!.parts.filterIsInstance<TextPartUi>().single()
        assertEquals("risposta", text.text)
        assertEquals(PartPhase.Streaming, text.phase)
    }

    // --- order / interleaving ----------------------------------------------

    @Test
    fun `text tool text mantiene l ordine di arrivo`() {
        var state = OpenChatState("ses_1")
        state = ChatReducer.reduce(state, ServerEvent.StepStarted("ses_1", null, null, "msg_a"))
        state = text(state, "msg_a", 0, "prima")
        state = tool(state, "msg_a", "call_1", "bash", "echo ciao", "ciao")
        state = text(state, "msg_a", 1, "dopo")

        val parts = state.stream.liveTurn!!.parts
        assertEquals(listOf("text:0", "tool:call_1", "text:1"), parts.map { it.key })
        assertEquals(listOf(0, 1, 2), parts.map { it.order })
        assertEquals(listOf("prima", "dopo"), parts.filterIsInstance<TextPartUi>().map { it.text })
    }

    @Test
    fun `a part updated late stays in place by order`() {
        var state = OpenChatState("ses_1")
        state = ChatReducer.reduce(state, ServerEvent.StepStarted("ses_1", null, null, "msg_a"))
        state = ChatReducer.reduce(state, ServerEvent.TextStarted("ses_1", "msg_a", 0))
        state = ChatReducer.reduce(state, ServerEvent.TextDelta("ses_1", "msg_a", 0, "prima"))
        // The next part (tool) is created after the text one, but the late delta
        // of the text updates the existing part without moving it to the end.
        state = ChatReducer.reduce(state, ServerEvent.ToolInputStarted("ses_1", "msg_a", "call_1", "bash"))
        state = ChatReducer.reduce(state, ServerEvent.TextDelta("ses_1", "msg_a", 0, " aggiornato"))

        val parts = state.stream.liveTurn!!.parts
        assertEquals(listOf("text:0", "tool:call_1"), parts.map { it.key })
        assertEquals(listOf(0, 1), parts.map { it.order })
        assertEquals("prima aggiornato", parts.filterIsInstance<TextPartUi>().single().text)
    }

    // --- idempotency / duplicates -------------------------------------------

    @Test
    fun `repeated started, ended and success are idempotent`() {
        var state = OpenChatState("ses_1")
        state = ChatReducer.reduce(state, ServerEvent.StepStarted("ses_1", null, null, "msg_a"))
        state = text(state, "msg_a", 0, "risposta")
        state = tool(state, "msg_a", "call_1", "bash", "echo ok", "ok")

        // Identical re-delivery (same events twice).
        state = ChatReducer.reduce(state, ServerEvent.TextStarted("ses_1", "msg_a", 0))
        state = ChatReducer.reduce(state, ServerEvent.TextEnded("ses_1", "msg_a", 0, "risposta"))
        state = ChatReducer.reduce(state, ServerEvent.ToolInputStarted("ses_1", "msg_a", "call_1", "bash"))
        state = ChatReducer.reduce(
            state,
            ServerEvent.ToolSuccess("ses_1", "msg_a", "call_1", listOf("altro"), true, 0, false),
        )

        val parts = state.stream.liveTurn!!.parts
        assertEquals("no duplicated part", 2, parts.size)
        assertEquals("risposta", parts.filterIsInstance<TextPartUi>().single().text)
        assertEquals(
            "the first terminal outcome wins",
            "ok",
            parts.filterIsInstance<ToolPartUi>().single().output,
        )
    }

    @Test
    fun `input delta with partial JSON does not crash and shows no JSON`() {
        var state = OpenChatState("ses_1")
        state = ChatReducer.reduce(state, ServerEvent.StepStarted("ses_1", null, null, "msg_a"))
        state = ChatReducer.reduce(state, ServerEvent.ToolInputStarted("ses_1", "msg_a", "call_1", "bash"))
        state = ChatReducer.reduce(
            state,
            ServerEvent.ToolInputDelta("ses_1", "msg_a", "call_1", "{\"command\": \"git sta"),
        )

        val partial = state.stream.liveTurn!!.parts.filterIsInstance<ToolPartUi>().single()
        assertNull("command not extractable from partial JSON", partial.command)
        assertEquals(PartPhase.Streaming, partial.phase)

        state = ChatReducer.reduce(
            state,
            ServerEvent.ToolInputDelta("ses_1", "msg_a", "call_1", "tus\"}"),
        )
        val complete = state.stream.liveTurn!!.parts.filterIsInstance<ToolPartUi>().single()
        assertEquals("git status", complete.command)
    }

    @Test
    fun `executed false does not make a called tool denied`() {
        var state = OpenChatState("ses_1")
        state = ChatReducer.reduce(state, ServerEvent.StepStarted("ses_1", null, null, "msg_a"))
        state = ChatReducer.reduce(state, ServerEvent.ToolInputStarted("ses_1", "msg_a", "call_1", "bash"))
        state = ChatReducer.reduce(
            state,
            ServerEvent.ToolInputEnded("ses_1", "msg_a", "call_1", "{\"command\":\"uname -s\"}"),
        )
        state = ChatReducer.reduce(
            state,
            ServerEvent.ToolCalled("ses_1", "msg_a", "call_1", null, executed = false),
        )

        val tool = state.stream.liveTurn!!.parts.filterIsInstance<ToolPartUi>().single()
        assertEquals("executed does not decide the phase", PartPhase.Running, tool.phase)
        assertTrue("executed is normalized for the UI", tool.executed)
        assertEquals("uname -s", tool.command)
    }

    @Test
    fun `tool failed con messaggio generico resta failed`() {
        var state = OpenChatState("ses_1")
        state = ChatReducer.reduce(state, ServerEvent.StepStarted("ses_1", null, null, "msg_a"))
        state = ChatReducer.reduce(state, ServerEvent.ToolInputStarted("ses_1", "msg_a", "call_1", "bash"))
        state = ChatReducer.reduce(
            state,
            ServerEvent.ToolFailed("ses_1", "msg_a", "call_1", listOf("boom"), false, 1, false),
        )

        val tool = state.stream.liveTurn!!.parts.filterIsInstance<ToolPartUi>().single()
        assertEquals(PartPhase.Failed, tool.phase)
        assertEquals("boom", tool.output)
        assertEquals(1, tool.exitCode)
        assertTrue("a generic failure still executed the tool", tool.executed)
    }

    @Test
    fun `tool failed con permission denied diventa denied`() {
        var state = OpenChatState("ses_1")
        state = ChatReducer.reduce(state, ServerEvent.StepStarted("ses_1", null, null, "msg_a"))
        state = ChatReducer.reduce(state, ServerEvent.ToolInputStarted("ses_1", "msg_a", "call_1", "bash"))
        state = ChatReducer.reduce(
            state,
            ServerEvent.ToolFailed(
                "ses_1", "msg_a", "call_1", listOf("Permission denied"), false, null, false,
            ),
        )

        val tool = state.stream.liveTurn!!.parts.filterIsInstance<ToolPartUi>().single()
        assertEquals(PartPhase.Denied, tool.phase)
        assertFalse(tool.executed)
    }

    @Test
    fun `success after a denied fixes it to Ok`() {
        var state = OpenChatState("ses_1")
        state = ChatReducer.reduce(state, ServerEvent.StepStarted("ses_1", null, null, "msg_a"))
        state = ChatReducer.reduce(state, ServerEvent.ToolInputStarted("ses_1", "msg_a", "call_1", "shell"))
        // Explicit denial via metadata.status (like an explicit failure).
        state = ChatReducer.reduce(
            state,
            ServerEvent.ToolFailed(
                "ses_1", "msg_a", "call_1", emptyList(), false, null, false, status = "denied",
            ),
        )
        val denied = state.stream.liveTurn!!.parts.filterIsInstance<ToolPartUi>().single()
        assertEquals(PartPhase.Denied, denied.phase)
        assertFalse(denied.executed)

        state = ChatReducer.reduce(
            state,
            ServerEvent.ToolSuccess(
                "ses_1", "msg_a", "call_1", listOf("Darwin"), false, 0, false,
            ),
        )

        val tool = state.stream.liveTurn!!.parts.filterIsInstance<ToolPartUi>().single()
        assertEquals("a success corrects a previous Denied", PartPhase.Ok, tool.phase)
        assertEquals("Darwin", tool.output)
        assertEquals(0, tool.exitCode)
        assertTrue(tool.executed)
    }

    @Test
    fun `step failed interrompe le part attive`() {
        var state = OpenChatState("ses_1")
        state = ChatReducer.reduce(state, ServerEvent.StepStarted("ses_1", null, null, "msg_a"))
        state = ChatReducer.reduce(state, ServerEvent.TextStarted("ses_1", "msg_a", 0))
        state = ChatReducer.reduce(state, ServerEvent.TextDelta("ses_1", "msg_a", 0, "parziale"))
        state = ChatReducer.reduce(state, ServerEvent.ToolInputStarted("ses_1", "msg_a", "call_1", "bash"))
        state = ChatReducer.reduce(
            state,
            ServerEvent.ToolCalled("ses_1", "msg_a", "call_1", null, executed = true),
        )
        state = ChatReducer.reduce(state, ServerEvent.StepFailed("ses_1", "msg_a", "boom"))

        val turn = state.stream.liveTurn!!
        assertFalse(turn.running)
        assertEquals(PartPhase.Interrupted, turn.parts.filterIsInstance<TextPartUi>().single().phase)
        assertEquals(PartPhase.Interrupted, turn.parts.filterIsInstance<ToolPartUi>().single().phase)
    }

    @Test
    fun `success without called goes straight to Ok`() {
        var state = OpenChatState("ses_1")
        state = ChatReducer.reduce(state, ServerEvent.StepStarted("ses_1", null, null, "msg_a"))
        state = ChatReducer.reduce(state, ServerEvent.ToolInputStarted("ses_1", "msg_a", "call_1", "read"))
        state = ChatReducer.reduce(
            state,
            ServerEvent.ToolSuccess("ses_1", "msg_a", "call_1", listOf("contenuto"), true, 0, false),
        )

        val tool = state.stream.liveTurn!!.parts.filterIsInstance<ToolPartUi>().single()
        assertEquals(PartPhase.Ok, tool.phase)
        assertEquals("contenuto", tool.output)
    }

    // --- multi-step / handoff ------------------------------------------------

    @Test
    fun `subsequent steps materialize the previous turn`() {
        var state = OpenChatState("ses_1")
        state = ChatReducer.reduce(state, ServerEvent.StepStarted("ses_1", null, null, "msg_1"))
        state = text(state, "msg_1", 0, "primo")
        state = ChatReducer.reduce(state, ServerEvent.StepStarted("ses_1", null, null, "msg_2"))
        state = text(state, "msg_2", 0, "secondo")
        state = ChatReducer.reduce(state, ServerEvent.ExecutionFinished("ses_1", "succeeded"))

        val assistant = state.messages.filter { it.role == Role.Assistant }
        assertEquals(listOf("msg_1", "msg_2"), assistant.map { it.id })
        assertEquals(listOf("primo", "secondo"), assistant.map { it.text })
        assertNull(state.stream.liveTurn)
    }

    // --- REST mapping -------------------------------------------------------

    @Test
    fun `mapping REST mantiene l ordine di content e i campi tool strutturati`() {
        val body = """
            {"data":[{"id":"msg_r","type":"assistant","time":{"created":10},
              "content":[
                {"type":"reasoning","text":"penso","time":{"start":100,"end":250}},
                {"type":"text","text":"prima "},
                {"type":"tool","callID":"call_1","name":"shell",
                 "state":{"status":"completed","input":{"command":"uname -s"},
                          "content":[{"type":"text","text":"Darwin\n"}],
                          "metadata":{"exit":0,"truncated":false},
                          "time":{"start":1000,"end":1200}}},
                {"type":"text","text":"dopo"}
              ]}]}
        """.trimIndent()

        val message = ApiParsing.parseMessages(body).messages.single()
        val parts = message.parts

        assertEquals(4, parts.size)
        assertEquals(listOf(0, 1, 2, 3), parts.map { it.order })
        assertTrue(parts[0] is ReasoningPartUi)
        assertEquals(100L, (parts[0] as ReasoningPartUi).startedAt)
        assertEquals(250L, (parts[0] as ReasoningPartUi).endedAt)
        assertEquals("text:1", parts[1].key)
        assertEquals("prima ", (parts[1] as TextPartUi).text)

        val tool = parts[2] as ToolPartUi
        assertEquals("tool:call_1", tool.key)
        assertEquals("shell", tool.name)
        assertEquals("uname -s", tool.command)
        assertEquals("Darwin", tool.output)
        assertEquals(0, tool.exitCode)
        assertFalse(tool.truncated)
        assertEquals(PartPhase.Ok, tool.phase)
        assertEquals(1000L, tool.startedAt)
        assertEquals(1200L, tool.endedAt)

        assertEquals("text:3", parts[3].key)
        // Derived fields remain populated for search/copy/legacy UI.
        assertEquals("prima \ndopo", message.text)
        assertEquals("penso", message.reasoning)
        assertEquals("shell", message.tools.single().name)
    }

    // --- helper -------------------------------------------------------------

    private fun text(
        state: OpenChatState,
        messageId: String,
        ordinal: Int,
        value: String,
    ): OpenChatState {
        var next = ChatReducer.reduce(state, ServerEvent.TextStarted("ses_1", messageId, ordinal))
        next = ChatReducer.reduce(next, ServerEvent.TextDelta("ses_1", messageId, ordinal, value))
        return ChatReducer.reduce(next, ServerEvent.TextEnded("ses_1", messageId, ordinal, value))
    }

    private fun tool(
        state: OpenChatState,
        messageId: String,
        callId: String,
        name: String,
        command: String,
        output: String,
    ): OpenChatState {
        var next = ChatReducer.reduce(
            state,
            ServerEvent.ToolInputStarted("ses_1", messageId, callId, name),
        )
        next = ChatReducer.reduce(
            next,
            ServerEvent.ToolInputEnded("ses_1", messageId, callId, "{\"command\":\"$command\"}"),
        )
        next = ChatReducer.reduce(
            next,
            ServerEvent.ToolCalled("ses_1", messageId, callId, null, executed = true),
        )
        return ChatReducer.reduce(
            next,
            ServerEvent.ToolSuccess("ses_1", messageId, callId, listOf(output), true, 0, false),
        )
    }

    private fun replayFixture(resource: String): OpenChatState {
        val sessionId = readLines(resource)
            .asSequence()
            .filter { it.startsWith("data: ") }
            .mapNotNull { EventParsing.parse(it.removePrefix("data: "))?.event }
            .firstNotNullOfOrNull { event ->
                event.sessionId?.takeIf { it.startsWith("ses_") }
            }
            ?: error("fixture without sessionID")

        var state = OpenChatState(sessionId)
        readLines(resource).forEach { raw ->
            if (!raw.startsWith("data: ")) return@forEach
            val incoming = EventParsing.parse(raw.removePrefix("data: ")) ?: return@forEach
            state = ChatReducer.reduce(state, incoming.event)
        }
        return state
    }

    private fun readLines(resource: String): List<String> =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
            "missing fixture: $resource"
        }.bufferedReader().use { it.readLines() }
}
