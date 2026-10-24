package io.github.mirkotappini.opencoderemote

import io.github.mirkotappini.opencoderemote.net.ReasoningPartUi
import io.github.mirkotappini.opencoderemote.net.TextPartUi
import io.github.mirkotappini.opencoderemote.net.ToolPartUi
import io.github.mirkotappini.opencoderemote.net.TurnPartUi

/**
 * Firma del contenuto che fa scattare l'auto-scroll (§4.4).
 *
 * Crescita di testo e ragionamento contano; la crescita dell'**output di un
 * tool** no: leggere un output lungo non deve strappare la lista verso il
 * basso. Cambiare fase di un tool (in corso → ok/errore) invece conta, perché
 * cambia l'altezza della card.
 *
 * Pura e separata dalla composable per poter essere verificata sulla JVM.
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
