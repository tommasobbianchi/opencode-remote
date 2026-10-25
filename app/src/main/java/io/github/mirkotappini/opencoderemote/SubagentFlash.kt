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
 * Bubble for the just-activated subagent.
 *
 * It is an ephemeral notice, not a control: it overlays the chat content (overlay,
 * not layout), does not intercept touches, and disappears on its own when the
 * ViewModel resets `subagentFlash` after a couple of seconds.
 */

/**
 * In 170 ms (fade + brief rise), out 400 ms: slow fade, chosen by the user after
 * trying it at 260 ms.
 */
private const val FLASH_IN_MS = 170
private const val FLASH_OUT_MS = 400

/**
 * Show the bubble until `flash` is null. The key is the `token`: a new activation
 * restarts the animation even when the agent is the same (orchestrator → designer →
 * designer), without flickering the text.
 */
@Composable
fun SubagentFlashOverlay(
    flash: SubagentFlashUi?,
    modifier: Modifier = Modifier,
) {
    // "Remove animations" enabled: appears and disappears instantly, without
    // motion.
    val animated = ValueAnimator.areAnimatorsEnabled()
    val inMs = if (animated) FLASH_IN_MS else 0
    val outMs = if (animated) FLASH_OUT_MS else 0

    AnimatedContent(
        targetState = flash,
        modifier = modifier,
        // Anchored top right: while the container animates its size, the bubble
        // stays fixed on the right edge instead of sliding.
        contentAlignment = Alignment.TopEnd,
        transitionSpec = {
            // Entry: fade + brief rise from the top, so the eye notices it without
            // anything below shifting. Exit: fade only.
            val enter = fadeIn(tween(inMs)) +
                slideInVertically(tween(inMs)) { height -> -height / 3 }
            val exit = fadeOut(tween(outMs))
            val transform = if (targetState != null) {
                enter togetherWith exit
            } else {
                EnterTransition.None togetherWith exit
            }
            transform.using(
                // No clipping: the bubble remains intact while the container
                // resizes (0 → full height and back).
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
        // A veil of the agent's color over the high surface: identifies the
        // subagent without losing the theme's tone.
        color = lerp(scheme.surfaceContainerHigh, tint, 0.10f),
        contentColor = scheme.onSurface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.55f)),
        modifier = Modifier
            .widthIn(max = 240.dp)
            // Purely informational: no click, and TalkBack reads it as a single
            // line ("subagent designer active") when it appears.
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
 * Deterministic tint from the agent name: the same string always yields the same
 * color, so `designer` and `fixer` remain recognizable across turns. Theme tokens
 * only — `warningInk` instead of `warning` because in light mode it is the readable
 * ink of the same family.
 */
private fun subagentTint(agent: String, palette: OpenPalette): Color {
    val tokens = listOf(
        palette.accent,
        palette.interactive,
        palette.success,
        palette.info,
        palette.warningInk,
    )
    // FNV-1a: the actual agent names (orchestrator, designer, fixer, explorer,
    // oracle…) map to distinct hues, which a simple weighted sum did not guarantee.
    // Int arithmetic with overflow: intentional.
    var hash = -2128831035 // 2166136261
    agent.lowercase().forEach { ch ->
        hash = hash xor ch.code
        hash *= 16777619
    }
    val index = ((hash.toLong() and 0xFFFF_FFFFL) % tokens.size).toInt()
    return tokens[index]
}
