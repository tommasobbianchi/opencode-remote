package io.github.mirkotappini.opencoderemote

import io.github.mirkotappini.opencoderemote.net.ReconnectPolicy
import io.github.mirkotappini.opencoderemote.net.SessionUi
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Età relativa compatta da timestamp epoch in millisecondi. */
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

/** Costo in USD con 4 decimali, come da DESIGN/API-M1. */
fun costText(cost: Double?): String =
    if (cost == null) "—" else String.format(Locale.US, "$%.4f", cost)

/** Nome leggibile del server, ricavato dall'host: mac-mini.tail… → mac-mini. */
fun serverName(url: String): String {
    val host = runCatching { URI(url).host }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: url.substringAfter("://", url)
            .substringBefore('/')
            .substringBefore(':')
    return host.substringBefore('.').ifBlank { "server" }
}

/** Riga secondaria della lista: agente · modello · costo. */
fun sessionMeta(session: SessionUi): String {
    val parts = buildList {
        session.agent?.takeIf { it.isNotBlank() }?.let(::add)
        session.modelId?.takeIf { it.isNotBlank() }?.let(::add)
        session.costUsd?.let { add(costText(it)) }
    }
    return parts.joinToString(" · ").ifBlank { "—" }
}

/**
 * Meta compatta per le righe del drawer: una sessione mai usata non ha ancora
 * `agent`/`model` (compaiono dopo il primo turno o un cambio esplicito), quindi
 * la riga non resta mai vuota: mostra "predefinito".
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

/** Riferimento `provider/modello`, oppure null quando il server non lo espone. */
fun modelRefLabel(providerId: String?, modelId: String?): String? = when {
    !providerId.isNullOrBlank() && !modelId.isNullOrBlank() -> "$providerId/$modelId"
    !modelId.isNullOrBlank() -> modelId
    else -> null
}

/** Famiglia cromatica dello stato SSE, tradotta in colori dal tema. */
enum class StreamTone { Live, Warning, Muted }

/** Etichetta unica dello stato SSE: lista, chat e drawer usano le stesse parole. */
data class StreamStatusUi(
    val label: String,
    val tone: StreamTone,
    val pulsing: Boolean,
)

/**
 * M5 F1 / M5.1: il semaforo deriva solo da [StreamState]. Mai "SSE live" quando
 * lo stream è in riconnessione o fermo: i dati in lista possono essere cache.
 * Con la rete assente il chip dice "rete assente", senza countdown.
 */
fun streamStatusUi(
    state: StreamState,
    secondsLeft: Int,
    suspended: Boolean = false,
): StreamStatusUi = when (state) {
    StreamState.Live -> StreamStatusUi("SSE live", StreamTone.Live, pulsing = true)
    is StreamState.Connecting -> StreamStatusUi("riconnessione", StreamTone.Warning, pulsing = true)
    is StreamState.Reconnecting -> StreamStatusUi(
        label = ReconnectPolicy.statusLabel(secondsLeft, suspended || state.suspended),
        tone = StreamTone.Warning,
        pulsing = true,
    )
    StreamState.Idle -> StreamStatusUi("SSE ferma", StreamTone.Muted, pulsing = false)
}

/** Etichetta italiana e breve per l'esito di una sessione. */
fun outcomeLabel(outcome: String?): String? = when (outcome?.lowercase(Locale.ROOT)) {
    "succeeded", "success", "done" -> "ok"
    "failed", "failure", "error" -> "errore"
    "interrupted", "aborted" -> "interrotta"
    "running", "active" -> "in corso"
    null, "" -> null
    else -> outcome
}

/**
 * Orario del messaggio: solo ora se è oggi, altrimenti giorno + ora.
 * Formato coerente in tutta la chat, in italiano.
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

/** Stato leggibile di un tool, ricavato dal JSON già troncato del modello UI. */
enum class ToolStatus(val label: String) {
    Ok("ok"),
    Running("in corso"),
    Failed("errore"),
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

/** Una riga del log SSE: "HH:mm:ss.SSS  [kind] payload". */
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
