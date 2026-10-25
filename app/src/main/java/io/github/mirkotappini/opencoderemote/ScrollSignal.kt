package io.github.mirkotappini.opencoderemote

import io.github.mirkotappini.opencoderemote.net.ReasoningPartUi
import io.github.mirkotappini.opencoderemote.net.TextPartUi
import io.github.mirkotappini.opencoderemote.net.ToolPartUi
import io.github.mirkotappini.opencoderemote.net.TurnPartUi

/**
 * Signature of the content that triggers auto-scroll (§4.4).
 *
 * Text and reasoning growth count; growth of a **tool output** does not: reading a
 * long output must not drag the list down. Changing a tool's phase (in progress →
 * ok/error) does count, because it changes the card's height.
 *
 * Pure and separated from the composable so it can be verified on the JVM.
 */
internal fun partsScrollSignal(running: Boolean, parts: List<TurnPartUi>): String = buildString {
    append("t|")
    append(running)
    append('|')
    append(parts.size)
    parts.forEach { part ->
        when (part) {
            is TextPartUi -> {
                append("|t")
                append(part.text.length)
            }

            is ReasoningPartUi -> {
                append("|r")
                append(part.text.length)
            }

            is ToolPartUi -> {
                append("|o")
                append(part.phase.name)
            }
        }
    }
}
