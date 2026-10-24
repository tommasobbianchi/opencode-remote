package io.github.mirkotappini.opencoderemote

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Bubble del subagente appena attivato.
 *
 * È un avviso effimero, non un controllo: resta sopra il contenuto della chat
 * (overlay, non layout), non intercetta tocchi e sparisce da solo quando il
 * ViewModel azzera `subagentFlash` dopo un paio di secondi.
 */

/** Entrata 170 ms (fade + breve salita), uscita 120 ms (solo fade). */
private const val FLASH_IN_MS = 170
private const val FLASH_OUT_MS = 120

/**
 * Mostra il bubble finché `flash` non è nullo. La chiave è il `token`: una
 * nuova attivazione riavvia l'animazione anche quando l'agente è lo stesso
 * (orchestrator → designer → designer), senza far lampeggiare il testo.
 */
@Composable
fun SubagentFlashOverlay(
    flash: SubagentFlashUi?,
    modifier: Modifier = Modifier,
) {
    // "Rimuovi animazioni" attivo: compare e sparisce di colpo, senza movimento.
    val animated = ValueAnimator.areAnimatorsEnabled()
    val inMs = if (animated) FLASH_IN_MS else 0
    val outMs = if (animated) FLASH_OUT_MS else 0

    AnimatedContent(
        targetState = flash,
        modifier = modifier,
        // Ancorato in alto a destra: mentre il contenitore anima la misura, il
        // bubble resta fermo sul bordo destro invece di scivolare.
        contentAlignment = Alignment.TopEnd,
        transitionSpec = {
            // Arrivo: fade + salita breve dall'alto, così l'occhio lo nota
            // senza che nulla sotto si sposti. Uscita: solo fade.
            val enter = fadeIn(tween(inMs)) +
                slideInVertically(tween(inMs)) { height -> -height / 3 }
            val exit = fadeOut(tween(outMs))
            val transform = if (targetState != null) {
                enter togetherWith exit
            } else {
                EnterTransition.None togetherWith exit
            }
            transform.using(
                // Niente clip: il bubble resta intero mentre il contenitore
                // cambia misura (0 → altezza piena e ritorno).
                SizeTransform(clip = false) { _, _ -> tween(inMs) },
            )
        },
        contentKey = { it?.token ?: -1L },
        label = "subagent-flash",
    ) { current ->
        if (current != null) SubagentFlashBubble(current)
    }
}

@Composable
private fun SubagentFlashBubble(flash: SubagentFlashUi) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val agent = flash.agent.ifBlank { "subagent" }
    val title = flash.title?.takeIf { it.isNotBlank() }
    val tint = subagentTint(agent, oc)
    Surface(
        // Un velo del colore dell'agente sopra la superficie alta: identifica il
        // subagente senza perdere il tono del tema.
        color = lerp(scheme.surfaceContainerHigh, tint, 0.10f),
        contentColor = scheme.onSurface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.55f)),
        modifier = Modifier
            .widthIn(max = 240.dp)
            // Puramente informativo: nessun click, e TalkBack lo legge come una
            // riga sola ("subagent designer active") quando compare.
            .clearAndSetSemantics {
                contentDescription = "subagent $agent active"
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 34.dp)
                .padding(start = 11.dp, end = 13.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(tint, size = 6.dp)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = agent,
                    style = monoStyle(
                        fontSize = 12.5.sp,
                        lineHeight = 15.sp,
                        weight = FontWeight.Medium,
                    ),
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            lineHeight = 13.sp,
                        ),
                        color = oc.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Tinta deterministica dal nome dell'agente: la stessa stringa dà sempre lo
 * stesso colore, così `designer` e `fixer` restano riconoscibili fra un turno
 * e l'altro. Solo token del tema — `warningInk` invece di `warning` perché in
 * chiaro è l'inchiostro leggibile della stessa famiglia.
 */
private fun subagentTint(agent: String, palette: OpenPalette): Color {
    val tokens = listOf(
        palette.accent,
        palette.interactive,
        palette.success,
        palette.info,
        palette.warningInk,
    )
    // FNV-1a: i nomi veri degli agenti (orchestrator, designer, fixer,
    // explorer, oracle…) finiscono su tinte diverse, cosa che una somma
    // pesata semplice non garantiva. Aritmetica su Int con overflow: è voluto.
    var hash = -2128831035 // 2166136261
    agent.lowercase().forEach { ch ->
        hash = hash xor ch.code
        hash *= 16777619
    }
    val index = ((hash.toLong() and 0xFFFF_FFFFL) % tokens.size).toInt()
    return tokens[index]
}
