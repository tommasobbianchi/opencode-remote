package io.github.mirkotappini.opencoderemote

import io.github.mirkotappini.opencoderemote.net.PartPhase
import io.github.mirkotappini.opencoderemote.net.ReasoningPartUi
import io.github.mirkotappini.opencoderemote.net.TextPartUi
import io.github.mirkotappini.opencoderemote.net.ToolPartUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * §4.4: la firma di auto-scroll deve cambiare quando arriva testo nuovo
 * (la lista deve seguire) e **non** quando cresce l'output di un tool
 * (leggere un output lungo non deve trascinare la vista in basso).
 */
class ScrollSignalTest {

    @Test
    fun `il testo che cresce cambia la firma`() {
        val before = partsScrollSignal(running = true, parts = listOf(text("ciao")))
        val after = partsScrollSignal(running = true, parts = listOf(text("ciao mondo")))
        assertNotEquals(before, after)
    }

    @Test
    fun `l output di un tool che cresce non cambia la firma`() {
        val before = partsScrollSignal(
            running = true,
            parts = listOf(tool(name = "shell", output = "riga 1")),
        )
        val after = partsScrollSignal(
            running = true,
            parts = listOf(tool(name = "shell", output = "riga 1\nriga 2\nriga 3")),
        )
        assertEquals(before, after)
    }

    @Test
    fun `un cambio di esito del tool cambia la firma`() {
        val running = partsScrollSignal(
            running = true,
            parts = listOf(tool(name = "shell", output = "", phase = PartPhase.Running)),
        )
        val done = partsScrollSignal(
            running = true,
            parts = listOf(tool(name = "shell", output = "", phase = PartPhase.Ok)),
        )
        assertNotEquals(running, done)
    }

    @Test
    fun `una nuova part cambia la firma`() {
        val one = partsScrollSignal(running = true, parts = listOf(text("ciao")))
        val two = partsScrollSignal(running = true, parts = listOf(text("ciao"), text("altro")))
        assertNotEquals(one, two)
    }

    @Test
    fun `il ragionamento che cresce cambia la firma`() {
        val before = partsScrollSignal(running = true, parts = listOf(reasoning("penso")))
        val after = partsScrollSignal(running = true, parts = listOf(reasoning("penso ancora")))
        assertNotEquals(before, after)
    }

    @Test
    fun `fine turno senza cambi di contenuto cambia la firma solo per running`() {
        val parts = listOf(text("risposta"))
        assertNotEquals(
            partsScrollSignal(running = true, parts = parts),
            partsScrollSignal(running = false, parts = parts),
        )
    }

    private fun text(value: String) =
        TextPartUi(key = "text:0", order = 0, phase = PartPhase.Streaming, text = value)

    private fun reasoning(value: String) =
        ReasoningPartUi(key = "reasoning:0", order = 0, phase = PartPhase.Streaming, text = value)

    private fun tool(
        name: String,
        output: String,
        phase: PartPhase = PartPhase.Running,
    ) = ToolPartUi(
        key = "tool:call_1",
        order = 1,
        phase = phase,
        callId = "call_1",
        name = name,
        output = output,
    )
}
