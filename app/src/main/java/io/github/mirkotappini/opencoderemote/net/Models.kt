package io.github.mirkotappini.opencoderemote.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

// ---------------------------------------------------------------------------
// Raw DTOs (mirror the API contract, all fields optional)
// ---------------------------------------------------------------------------

@Serializable
data class TimeDto(
    val created: Long? = null,
    val updated: Long? = null,
    val idle: Long? = null,
    val viewed: Long? = null,
    val archived: Long? = null,
    val start: Long? = null,
    val end: Long? = null,
    /** REST parts: `reasoning`/`text` use `completed`, tools also `ran`. */
    val completed: Long? = null,
    val ran: Long? = null,
    val streamed: Long? = null,
)

@Serializable
data class ModelDto(
    val id: String? = null,
    val providerID: String? = null,
    val variant: String? = null,
)

@Serializable
data class TokenCacheDto(
    val read: Long? = null,
    val write: Long? = null,
)

@Serializable
data class TokenUsageDto(
    val input: Long? = null,
    val output: Long? = null,
    val reasoning: Long? = null,
    val cache: TokenCacheDto? = null,
)

@Serializable
data class SessionDto(
    val id: String = "",
    val parentID: String? = null,
    val projectID: String? = null,
    val agent: String? = null,
    val model: ModelDto? = null,
    val cost: Double? = null,
    val tokens: TokenUsageDto? = null,
    val outcome: String? = null,
    val time: TimeDto? = null,
    val title: String? = null,
    val subpath: String? = null,
)

@Serializable
data class MessageDto(
    val id: String = "",
    val type: String = "",
    val time: TimeDto? = null,
    val text: String? = null,
    val agent: String? = null,
    val model: ModelDto? = null,
    val content: JsonElement? = null,
    val outcome: String? = null,
    val finish: String? = null,
    val cost: Double? = null,
)

@Serializable
data class CursorDto(
    val previous: String? = null,
    val next: String? = null,
)

@Serializable
data class SessionsEnvelope(
    val data: List<SessionDto> = emptyList(),
    val cursor: CursorDto? = null,
)

/** `GET /api/session/{id}` responds with the envelope `{data: Session.Info}`. */
@Serializable
data class SessionEnvelope(
    val data: SessionDto? = null,
)

@Serializable
data class MessagesEnvelope(
    val data: List<MessageDto> = emptyList(),
    val cursor: CursorDto? = null,
)

/** `GET /api/session/active` returns `{"data":{}}` when there is no session. */
@Serializable
data class ActiveData(
    val id: String? = null,
    val sessionID: String? = null,
)

@Serializable
data class ActiveEnvelope(
    val data: ActiveData? = null,
)

// ---------------------------------------------------------------------------
// Polymorphic parts: `type` discriminator, generic fallback for unknowns
// ---------------------------------------------------------------------------

@Serializable(with = PartDtoSerializer::class)
sealed interface PartDto {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String = "",
        val time: TimeDto? = null,
    ) : PartDto

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val text: String = "",
        val time: TimeDto? = null,
    ) : PartDto

    @Serializable
    @SerialName("tool")
    data class Tool(
        val name: String = "",
        val tool: String? = null,
        val state: JsonElement? = null,
        val callID: String? = null,
        val id: String? = null,
        val executed: Boolean? = null,
        val time: TimeDto? = null,
    ) : PartDto

    /** Any unexpected `type`: does not make parsing fail. */
    data class Unknown(val type: String = "") : PartDto
}

object PartDtoSerializer : KSerializer<PartDto> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("PartDto")

    private val partJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    override fun deserialize(decoder: Decoder): PartDto {
        val input = decoder as? JsonDecoder ?: return PartDto.Unknown()
        val element = input.decodeJsonElement()
        val type = ((element as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull.orEmpty()
        return try {
            when (type) {
                "text" -> partJson.decodeFromJsonElement<PartDto.Text>(element)
                "reasoning" -> partJson.decodeFromJsonElement<PartDto.Reasoning>(element)
                "tool" -> partJson.decodeFromJsonElement<PartDto.Tool>(element)
                else -> PartDto.Unknown(type)
            }
        } catch (_: Exception) {
            PartDto.Unknown(type)
        }
    }

    override fun serialize(encoder: Encoder, value: PartDto) {
        // M1 is read-only: we never serialize parts back to the server.
        encoder.encodeString(value::class.simpleName ?: "unknown")
    }
}

// ---------------------------------------------------------------------------
// Normalized model for the UI (composables see no JSON)
// ---------------------------------------------------------------------------

enum class Role { User, Assistant, Meta }

data class ToolChip(
    val name: String,
    val state: String?,
)

/** Normalized token counters for the session list. */
data class TokenUsage(
    val input: Long? = null,
    val output: Long? = null,
    val reasoning: Long? = null,
    val cacheRead: Long? = null,
    val cacheWrite: Long? = null,
) {
    val total: Long get() = (input ?: 0L) + (output ?: 0L) + (reasoning ?: 0L)
}

data class SessionUi(
    val id: String,
    val title: String,
    val agent: String? = null,
    val modelId: String? = null,
    val modelProvider: String? = null,
    val variant: String? = null,
    val updatedAt: Long? = null,
    val costUsd: Double? = null,
    val tokens: TokenUsage? = null,
    val outcome: String? = null,
    val isActive: Boolean = false,
)

data class MessageUi(
    val id: String,
    val role: Role,
    val text: String = "",
    val reasoning: String? = null,
    val tools: List<ToolChip> = emptyList(),
    val createdAt: Long? = null,
    val finish: String? = null,
    val metaLabel: String? = null,
    /** Optimistic user message not yet confirmed by the session. */
    val pending: Boolean = false,
    /** Parts ordered by `order`; this is the source of truth of the turn renderer. */
    val parts: List<TurnPartUi> = emptyList(),
)

data class SessionsPage(
    val sessions: List<SessionUi>,
    val previous: String? = null,
    val next: String? = null,
)

/** `messages` are in chronological order (oldest → newest). */
data class MessagesPage(
    val messages: List<MessageUi>,
    val previous: String? = null,
    val next: String? = null,
)

// ---------------------------------------------------------------------------
// Tolerant parsing
// ---------------------------------------------------------------------------

object ApiParsing {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun parseSessions(body: String): SessionsPage {
        val envelope = json.decodeFromString<SessionsEnvelope>(body)
        return SessionsPage(
            sessions = envelope.data.map(::normalizeSession),
            previous = envelope.cursor?.previous,
            next = envelope.cursor?.next,
        )
    }

    fun parseMessages(body: String): MessagesPage {
        val envelope = json.decodeFromString<MessagesEnvelope>(body)
        val chronological = envelope.data
            .mapNotNull(::normalizeMessage)
            .asReversed()
        return MessagesPage(
            messages = chronological,
            previous = envelope.cursor?.previous,
            next = envelope.cursor?.next,
        )
    }

    fun parseActiveSession(body: String): String? {
        val envelope = json.decodeFromString<ActiveEnvelope>(body)
        return envelope.data?.id?.takeIf { it.isNotBlank() }
            ?: envelope.data?.sessionID?.takeIf { it.isNotBlank() }
    }

    /** `GET /api/session/{id}`; `null` if the envelope does not contain a session. */
    fun parseSession(body: String): SessionUi? {
        val envelope = json.decodeFromString<SessionEnvelope>(body)
        return envelope.data?.let(::normalizeSession)
    }

    fun normalizeSession(dto: SessionDto): SessionUi = SessionUi(
        id = dto.id,
        title = dto.title?.takeIf { it.isNotBlank() } ?: dto.id,
        agent = dto.agent?.takeIf { it.isNotBlank() },
        modelId = dto.model?.id?.takeIf { it.isNotBlank() },
        modelProvider = dto.model?.providerID?.takeIf { it.isNotBlank() },
        variant = dto.model?.variant?.takeIf { it.isNotBlank() },
        updatedAt = dto.time?.updated ?: dto.time?.created,
        costUsd = dto.cost,
        tokens = dto.tokens?.let {
            TokenUsage(
                input = it.input,
                output = it.output,
                reasoning = it.reasoning,
                cacheRead = it.cache?.read,
                cacheWrite = it.cache?.write,
            )
        },
        outcome = dto.outcome?.takeIf { it.isNotBlank() },
    )

    fun normalizeMessage(dto: MessageDto): MessageUi? {
        if (dto.id.isBlank()) return null
        val createdAt = dto.time?.created
        return when (dto.type) {
            "user" -> MessageUi(
                id = dto.id,
                role = Role.User,
                text = dto.text.orEmpty(),
                createdAt = createdAt,
            )

            "assistant" -> {
                val parsed = parsePartsIndexed(dto.content)
                val toolStates = HashMap<String, JsonElement?>()
                val turnParts = parsed.mapNotNull { (index, part) ->
                    when (part) {
                        is PartDto.Text -> TextPartUi(
                            key = "text:$index",
                            order = index,
                            phase = PartPhase.Done,
                            text = part.text,
                        )

                        is PartDto.Reasoning -> ReasoningPartUi(
                            key = "reasoning:$index",
                            order = index,
                            phase = PartPhase.Done,
                            text = part.text,
                            startedAt = part.time?.start ?: part.time?.created,
                            endedAt = part.time?.end ?: part.time?.completed
                                ?: part.time?.streamed,
                        )

                        is PartDto.Tool -> {
                            val callId = part.callID
                                ?: part.id
                                ?: index.toString()
                            val key = if (part.callID != null || part.id != null) {
                                "tool:$callId"
                            } else {
                                "tool:$index"
                            }
                            toolStates[key] = part.state
                            mapToolPart(
                                key = key,
                                order = index,
                                callId = callId,
                                name = part.name.ifBlank { part.tool.orEmpty() },
                                state = part.state,
                                time = part.time,
                            )
                        }

                        is PartDto.Unknown -> null
                    }
                }

                val text = turnParts.filterIsInstance<TextPartUi>()
                    .joinToString("\n") { it.text }
                    .trim()
                val reasoning = turnParts.filterIsInstance<ReasoningPartUi>()
                    .joinToString("\n") { it.text }
                    .trim()
                    .takeIf { it.isNotEmpty() }
                val tools = turnParts.filterIsInstance<ToolPartUi>().map { part ->
                    ToolChip(
                        name = part.name.takeIf { it.isNotBlank() } ?: "tool",
                        state = legacyToolState(part, toolStates[part.key]),
                    )
                }
                MessageUi(
                    id = dto.id,
                    role = Role.Assistant,
                    text = text,
                    reasoning = reasoning,
                    tools = tools,
                    createdAt = createdAt,
                    finish = dto.finish,
                    parts = turnParts,
                )
            }

            // idle/status and any unknown type: treat as a divider, never crash.
            else -> MessageUi(
                id = dto.id,
                role = Role.Meta,
                createdAt = createdAt,
                metaLabel = dto.type.ifBlank { "evento" } +
                    (dto.outcome?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            )
        }
    }

    /** Keeps the original index: `order` at resync is the index in `content[]`. */
    private fun parsePartsIndexed(content: JsonElement?): List<Pair<Int, PartDto>> {
        val array = content as? JsonArray ?: return emptyList()
        return array.mapIndexedNotNull { index, element ->
            if (element is JsonNull) return@mapIndexedNotNull null
            runCatching {
                json.decodeFromJsonElement<PartDto>(element)
            }.getOrNull()?.let { index to it }
        }
    }

    private fun mapToolPart(
        key: String,
        order: Int,
        callId: String,
        name: String,
        state: JsonElement?,
        time: TimeDto?,
    ): ToolPartUi {
        val stateObj = state as? JsonObject
        val input = stateObj?.get("input")
        val status = stateObj?.string("status")
        val contentOutput = (stateObj?.get("content") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.string("text") }
            ?.joinToString("")
            ?.trimEnd()
            ?.takeIf { it.isNotEmpty() }
        val output = contentOutput
            ?: stateObj?.string("output")
            ?: (input as? JsonObject)?.string("output")
        val metadata = stateObj?.get("metadata") as? JsonObject
        val stateTime = stateObj?.get("time") as? JsonObject
        val phase = phaseOf(status, output)
        val command = ToolInputParsing.commandFromJson(input ?: stateObj)
            ?: (input as? JsonPrimitive)?.contentOrNull

        return ToolPartUi(
            key = key,
            order = order,
            phase = phase,
            callId = callId,
            name = name.takeIf { it.isNotBlank() } ?: "tool",
            command = command,
            output = output,
            exitCode = metadata?.int("exit") ?: metadata?.int("exitCode")
                ?: stateObj?.int("exit"),
            truncated = metadata?.bool("truncated")
                ?: stateObj?.bool("truncated")
                ?: false,
            // UI-facing field: `executed=false` only for an explicit denial.
            // The phase never derives from this value.
            executed = phase != PartPhase.Denied,
            startedAt = stateTime?.long("start") ?: time?.start ?: time?.ran ?: time?.created,
            endedAt = stateTime?.long("end") ?: time?.end ?: time?.completed,
        )
    }

    private fun phaseOf(
        status: String?,
        output: String?,
    ): PartPhase = when (status?.lowercase(java.util.Locale.ROOT)) {
        "pending", "queued", "starting", "started" -> PartPhase.Streaming
        "running", "in_progress", "in-progress" -> PartPhase.Running
        "completed", "complete", "success", "succeeded", "done", "ok" -> PartPhase.Ok
        "error", "failed", "failure" -> PartPhase.Failed
        "denied", "rejected", "cancelled", "canceled" -> PartPhase.Denied
        "interrupted", "aborted", "stopped" -> PartPhase.Interrupted
        else -> when {
            !output.isNullOrBlank() -> PartPhase.Ok
            else -> PartPhase.Streaming
        }
    }

    /**
     * Compatibility with the old chip: uses the JSON derived from the
     * structured fields. Only for unrecognized states does it fall back to
     * the old summarize.
     */
    private fun legacyToolState(part: ToolPartUi, raw: JsonElement?): String? {
        val obj = raw as? JsonObject
        val recognized = obj != null && listOf(
            "status",
            "input",
            "content",
            "output",
            "metadata",
            "time",
            "executed",
        ).any { obj.containsKey(it) }
        return if (recognized) part.toToolChipState() else summarize(raw)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    /** Tool state as compact, truncated JSON for the expandable chip. */
    fun summarize(state: JsonElement?): String? {
        if (state == null || state is JsonNull) return null
        val raw = state.toString()
        return if (raw.length > 160) raw.take(160) + "…" else raw
    }
}
