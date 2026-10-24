package io.github.mirkotappini.opencoderemote.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Fase di una part di un turno assistente.
 *
 * `Done` = text/reasoning conclusi; `Ok`/`Failed`/`Denied` = esiti di un tool.
 */
enum class PartPhase { Streaming, Running, Done, Ok, Failed, Denied, Interrupted }

/** Una part del turno live o persistito. `order` è l'unico ordinamento visivo. */
sealed interface TurnPartUi {
    /** Stabile e indipendente da testo/stato: "reasoning:0", "text:1", "tool:call_abc". */
    val key: String

    /** Cursore monotono per messaggio; al resync è l'indice in `content[]`. */
    val order: Int

    val phase: PartPhase
}

data class ReasoningPartUi(
    override val key: String,
    override val order: Int,
    override val phase: PartPhase,
    val text: String,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
) : TurnPartUi

data class TextPartUi(
    override val key: String,
    override val order: Int,
    override val phase: PartPhase,
    val text: String,
) : TurnPartUi

data class ToolPartUi(
    override val key: String,
    override val order: Int,
    override val phase: PartPhase,
    val callId: String,
    val name: String,
    val command: String? = null,
    val output: String? = null,
    val exitCode: Int? = null,
    val truncated: Boolean = false,
    /**
     * Flag UI-facing: false solo per un diniego esplicito. In 2.1.4 il campo
     * `executed` del server è false anche per tool completati, quindi non deve
     * mai determinare la fase né essere interpretato come "permesso di eseguire".
     */
    val executed: Boolean = true,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
) : TurnPartUi

data class AssistantTurnUi(
    /** assistantMessageID: stessa chiave di lista live → resync. */
    val id: String,
    /** Sempre ordinate per [TurnPartUi.order]. */
    val parts: List<TurnPartUi> = emptyList(),
    val createdAt: Long? = null,
    val finish: String? = null,
    val running: Boolean = false,
    /** Bump a ogni modifica; usato per auto-scroll/throttle. */
    val revision: Long = 0,
)

// ---------------------------------------------------------------------------
// Helper condivisi da reducer e mapping REST
// ---------------------------------------------------------------------------

internal fun PartPhase.isTerminal(): Boolean =
    this == PartPhase.Done ||
        this == PartPhase.Ok ||
        this == PartPhase.Failed ||
        this == PartPhase.Denied ||
        this == PartPhase.Interrupted

internal fun PartPhase.isToolTerminal(): Boolean =
    this == PartPhase.Ok ||
        this == PartPhase.Failed ||
        this == PartPhase.Denied ||
        this == PartPhase.Interrupted

internal fun PartPhase.toWireStatus(): String = when (this) {
    PartPhase.Streaming -> "pending"
    PartPhase.Running -> "running"
    PartPhase.Done, PartPhase.Ok -> "completed"
    PartPhase.Failed -> "error"
    PartPhase.Denied -> "denied"
    PartPhase.Interrupted -> "interrupted"
}

/** Compatibilità con il vecchio `ToolChip.state`: JSON derivato, mai il dump grezzo. */
internal fun ToolPartUi.toToolChipState(): String = buildJsonObject {
    put("status", phase.toWireStatus())
    command?.takeIf { it.isNotBlank() }?.let { put("command", it) }
    output?.let { put("output", it) }
    exitCode?.let { put("exit", it) }
    if (truncated) put("truncated", true)
}.toString()

/** Converte il turno live nel vecchio modello testuale (ChatScreen invariata). */
internal fun AssistantTurnUi.toLiveMessage(): LiveMessage {
    val textParts = parts
        .filterIsInstance<TextPartUi>()
        .map { part ->
            val ordinal = part.key.substringAfterLast(':').toIntOrNull() ?: part.order
            LivePart(
                ordinal = ordinal,
                text = part.text,
                ended = part.phase != PartPhase.Streaming,
            )
        }
        .sortedBy { it.ordinal }
    return LiveMessage(id, textParts)
}

/** Materializza un turno live in un messaggio persistito, con i campi derivati. */
internal fun AssistantTurnUi.toMessageUi(): MessageUi {
    val ordered = parts.sortedBy { it.order }
    val text = ordered
        .filterIsInstance<TextPartUi>()
        .joinToString("\n") { it.text }
        .trim()
    val reasoning = ordered
        .filterIsInstance<ReasoningPartUi>()
        .joinToString("\n") { it.text }
        .trim()
        .takeIf { it.isNotEmpty() }
    val tools = ordered
        .filterIsInstance<ToolPartUi>()
        .map { part ->
            ToolChip(
                name = part.name.takeIf { it.isNotBlank() } ?: "tool",
                state = part.toToolChipState(),
            )
        }
    return MessageUi(
        id = id,
        role = Role.Assistant,
        text = text,
        reasoning = reasoning,
        tools = tools,
        createdAt = createdAt,
        finish = finish,
        parts = ordered,
    )
}

/**
 * Estrazione tollerante del comando/soggetto dal JSON di input di un tool.
 * Non lancia mai: un JSON parziale semplicemente non produce un comando.
 */
object ToolInputParsing {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val subjectKeys = listOf(
        "command",
        "cmd",
        "path",
        "filePath",
        "pattern",
        "query",
        "url",
        "description",
    )

    /** `element` può essere lo `state` completo (con `input`) o direttamente l'input. */
    fun commandFromJson(element: JsonElement?): String? {
        val obj = element as? JsonObject ?: return null
        for (key in subjectKeys) {
            val value = (obj[key] as? JsonPrimitive)?.contentOrNull
            if (!value.isNullOrBlank()) return value
        }
        val input = obj["input"] as? JsonObject ?: return null
        return commandFromJson(input)
    }

    /**
     * Prova il parse completo; se il buffer è ancora un JSON parziale,
     * recupera una coppia `"chiave": "valore già chiusa"` con una regex.
     */
    fun commandFromPartial(raw: String): String? {
        if (raw.isBlank()) return null
        runCatching { json.parseToJsonElement(raw) }.getOrNull()?.let { parsed ->
            commandFromJson(parsed)?.let { return it }
        }
        for (key in subjectKeys) {
            val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            val captured = regex.find(raw)?.groupValues?.getOrNull(1) ?: continue
            return unescape(captured)
        }
        return null
    }

    private fun unescape(raw: String): String =
        runCatching {
            (json.parseToJsonElement("\"$raw\"") as? JsonPrimitive)?.contentOrNull
        }.getOrNull() ?: raw
}
