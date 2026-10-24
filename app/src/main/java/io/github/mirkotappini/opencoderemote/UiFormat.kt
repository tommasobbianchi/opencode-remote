package io.github.mirkotappini.opencoderemote

import io.github.mirkotappini.opencoderemote.net.ReconnectPolicy
import io.github.mirkotappini.opencoderemote.net.SessionUi
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Compact relative age from an epoch timestamp in milliseconds. */
fun relativeAge(epochMs: Long?, now: Long = System.currentTimeMillis()): String {
    if (epochMs == null || epochMs <= 0L) return "—"
    val diffMs = (now - epochMs).coerceAtLeast(0L)
    val minutes = diffMs / 60_000L
    return when {
        minutes < 1L -> "ora"
        minutes < 60L -> "${minutes}m"
        minutes < 1_440L -> "${minutes / 60L}h"
        minutes < 10_080L -> "${minutes / 1_440L}g"
        else -> "${minutes / 10_080L}sett"
    }
}

/** Cost in USD with 4 decimals, per DESIGN/API-M1. */
fun costText(cost: Double?): String =
    if (cost == null) "—" else String.format(Locale.US, "$%.4f", cost)

/** Human-readable server name, derived from the host: mac-mini.tail… → mac-mini. */
fun serverName(url: String): String {
    val host = runCatching { URI(url).host }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: url.substringAfter("://", url)
            .substringBefore('/')
            .substringBefore(':')
    return host.substringBefore('.').ifBlank { "server" }
}

/** Secondary line of the list: agent · model · cost. */
fun sessionMeta(session: SessionUi): String {
    val parts = buildList {
        session.agent?.takeIf { it.isNotBlank() }?.let(::add)
        session.modelId?.takeIf { it.isNotBlank() }?.let(::add)
        session.costUsd?.let { add(costText(it)) }
    }
    return parts.joinToString(" · ").ifBlank { "—" }
}

/**
 * Compact meta for drawer rows: a session never used does not have
 * `agent`/`model` yet (they appear after the first turn or an explicit change),
 * so the row is never left empty: it shows "predefinito".
 */
fun drawerSessionMeta(session: SessionUi): String {
    val agent = session.agent?.takeIf { it.isNotBlank() }
    val model = session.modelId?.takeIf { it.isNotBlank() }
    return when {
        agent != null && model != null -> "$agent · $model"
        agent != null -> agent
        model != null -> "predefinito · $model"
        else -> "predefinito"
    }
}

/** `provider/model` reference, or null when the server does not expose it. */
fun modelRefLabel(providerId: String?, modelId: String?): String? = when {
    !providerId.isNullOrBlank() && !modelId.isNullOrBlank() -> "$providerId/$modelId"
    !modelId.isNullOrBlank() -> modelId
    else -> null
}

/** Color family for the SSE state, mapped to colors by the theme. */
enum class StreamTone { Live, Warning, Muted }

/** Single label for the SSE state: list, chat and drawer share the same wording. */
data class StreamStatusUi(
    val label: String,
    val tone: StreamTone,
    val pulsing: Boolean,
)

/**
 * M5 F1 / M5.1: the traffic light derives only from [StreamState]. Never
 * "SSE live" while the stream is reconnecting or stalled: the list data may
 * be cached. With the network down the chip says "rete assente", no countdown.
 */
fun streamStatusUi(
    state: StreamState,
    secondsLeft: Int,
    suspended: Boolean = false,
): StreamStatusUi = when (state) {
    StreamState.Live -> StreamStatusUi("SSE live", StreamTone.Live, pulsing = true)
    is StreamState.Connecting -> StreamStatusUi("reconnecting", StreamTone.Warning, pulsing = true)
    is StreamState.Reconnecting -> StreamStatusUi(
        label = ReconnectPolicy.statusLabel(secondsLeft, suspended || state.suspended),
        tone = StreamTone.Warning,
        pulsing = true,
    )
    StreamState.Idle -> StreamStatusUi("SSE ferma", StreamTone.Muted, pulsing = false)
}

/** Short Italian label for a session's outcome. */
fun outcomeLabel(outcome: String?): String? = when (outcome?.lowercase(Locale.ROOT)) {
    "succeeded", "success", "done" -> "ok"
    "failed", "failure", "error" -> "error"
    "interrupted", "aborted" -> "interrotta"
    "running", "active" -> "in corso"
    null, "" -> null
    else -> outcome
}

/**
 * Time of the message: time only if it is today, otherwise day + time.
 * Consistent formatting across the whole chat, in Italian.
 */
fun messageTime(epochMs: Long?, now: Long = System.currentTimeMillis()): String {
    if (epochMs == null || epochMs <= 0L) return ""
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(epochMs).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN).format(at)
    return if (at.toLocalDate() == today) {
        clock
    } else {
        "${DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN).format(at)} · $clock"
    }
}

/** Human-readable tool state, derived from the truncated JSON in the UI model. */
enum class ToolStatus(val label: String) {
    Ok("ok"),
    Running("in corso"),
    Failed("error"),
    Denied("negato"),
}

private val statusRegex = Regex("\"status\"\\s*:\\s*\"([^\"]+)\"")

fun toolStatusOf(state: String?): ToolStatus? {
    if (state == null) return null
    val raw = statusRegex.find(state)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: return null
    return when (raw) {
        "completed", "complete", "success", "succeeded", "done", "ok" -> ToolStatus.Ok
        "running", "pending", "in_progress", "in-progress", "started" -> ToolStatus.Running
        "error", "failed", "failure" -> ToolStatus.Failed
        "denied", "rejected", "cancelled", "canceled" -> ToolStatus.Denied
        else -> null
    }
}

/** One line of the SSE log: "HH:mm:ss.SSS  [kind] payload". */
data class EventLineParts(
    val time: String,
    val kind: String,
    val payload: String,
)

fun splitEventLine(line: String): EventLineParts {
    val marker = line.indexOf("  [")
    if (marker < 0) return EventLineParts("", "", line)
    val time = line.substring(0, marker).trim()
    val rest = line.substring(marker + 3)
    val close = rest.indexOf(']')
    if (close < 0) return EventLineParts(time, "", rest)
    return EventLineParts(
        time = time,
        kind = rest.substring(0, close).trim(),
        payload = rest.substring(close + 1).trimStart(),
    )
}
