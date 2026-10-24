package io.github.mirkotappini.opencoderemote.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Delivery metadata of the event (monotonic sequence per session). */
@Serializable
data class DurableDto(
    val aggregateID: String? = null,
    val seq: Long? = null,
    val version: Int? = null,
)

/** Real SSE envelope: `id, created, type, data, durable, location`. */
@Serializable
data class SseEnvelope(
    val id: String? = null,
    val created: Long? = null,
    val type: String = "",
    val data: JsonElement? = null,
    val location: JsonElement? = null,
    val durable: DurableDto? = null,
)

/** Event normalized for the UI. Never an exception for an unknown `type`. */
sealed interface ServerEvent {
    val sessionId: String?

    data object ServerConnected : ServerEvent {
        override val sessionId: String? = null
    }

    data class TextStarted(
        override val sessionId: String,
        val messageId: String,
        val ordinal: Int,
    ) : ServerEvent

    data class TextDelta(
        override val sessionId: String,
        val messageId: String,
        val ordinal: Int,
        val delta: String,
    ) : ServerEvent

    data class TextEnded(
        override val sessionId: String,
        val messageId: String,
        val ordinal: Int,
        val text: String,
    ) : ServerEvent

    data class ReasoningStarted(
        override val sessionId: String,
        val messageId: String,
        val ordinal: Int,
    ) : ServerEvent

    data class ReasoningDelta(
        override val sessionId: String,
        val messageId: String,
        val ordinal: Int,
        val delta: String,
    ) : ServerEvent

    data class ReasoningEnded(
        override val sessionId: String,
        val messageId: String,
        val ordinal: Int,
        val text: String,
    ) : ServerEvent

    data class ToolInputStarted(
        override val sessionId: String,
        val messageId: String,
        val callId: String,
        val name: String,
    ) : ServerEvent

    data class ToolInputDelta(
        override val sessionId: String,
        val messageId: String,
        val callId: String,
        val delta: String,
    ) : ServerEvent

    data class ToolInputEnded(
        override val sessionId: String,
        val messageId: String,
        val callId: String,
        val text: String,
    ) : ServerEvent

    data class ToolCalled(
        override val sessionId: String,
        val messageId: String,
        val callId: String,
        val input: JsonElement?,
        val executed: Boolean,
    ) : ServerEvent

    data class ToolProgress(
        override val sessionId: String,
        val messageId: String,
        val callId: String,
        val exitCode: Int?,
        val truncated: Boolean,
    ) : ServerEvent

    data class ToolSuccess(
        override val sessionId: String,
        val messageId: String,
        val callId: String,
        val content: List<String>,
        val executed: Boolean,
        val exitCode: Int?,
        val truncated: Boolean,
    ) : ServerEvent

    data class ToolFailed(
        override val sessionId: String,
        val messageId: String,
        val callId: String,
        val content: List<String>,
        val executed: Boolean,
        val exitCode: Int?,
        val truncated: Boolean,
        /** `metadata.status` (or top-level `status`), to recognize an explicit denial. */
        val status: String? = null,
        /** Server error message, if present. */
        val message: String? = null,
    ) : ServerEvent

    data class StepStarted(
        override val sessionId: String,
        val agent: String?,
        val modelId: String?,
        val messageId: String,
        val startedAt: Long? = null,
    ) : ServerEvent

    data class StepEnded(
        override val sessionId: String,
        val messageId: String,
        val finish: String?,
        val cost: Double?,
        val tokens: TokenUsage?,
    ) : ServerEvent

    /** `session.step.failed`: the turn closes, active parts become Interrupted. */
    data class StepFailed(
        override val sessionId: String,
        val messageId: String?,
        val error: String?,
    ) : ServerEvent

    data class ExecutionStarted(
        override val sessionId: String,
    ) : ServerEvent

    data class ExecutionFinished(
        override val sessionId: String,
        val outcome: String,
    ) : ServerEvent

    data class UsageUpdated(
        override val sessionId: String,
        val cost: Double?,
        val tokens: TokenUsage?,
    ) : ServerEvent

    data class InboxEnqueued(
        override val sessionId: String,
        val inboxId: String?,
        val text: String?,
        val delivery: String?,
    ) : ServerEvent

    data class InboxDelivered(
        override val sessionId: String,
        val inboxId: String?,
    ) : ServerEvent

    /** `permission.asked`: `data` is the entire Permission.Request. */
    data class PermissionAsked(
        override val sessionId: String,
        val approval: ApprovalUi,
    ) : ServerEvent

    /** `permission.replied`: resolution confirmation (request id optional). */
    data class PermissionReplied(
        override val sessionId: String?,
        val requestId: String?,
    ) : ServerEvent

    data class Unknown(
        val type: String,
        override val sessionId: String?,
    ) : ServerEvent
}

/** Interpreted envelope: `wireType` is only for logging, never for rendering. */
data class IncomingEvent(
    val id: String?,
    val created: Long?,
    val wireType: String,
    val event: ServerEvent,
)

/**
 * Tolerant parser for the SSE envelope (docs/API-M2.md §2).
 * Rules: unknown type → [ServerEvent.Unknown]; missing `data.sessionID` on a
 * session event → event dropped (`null`); malformed JSON → `null`.
 */
object EventParsing {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun parse(raw: String): IncomingEvent? {
        val envelope = runCatching { json.decodeFromString<SseEnvelope>(raw) }.getOrNull()
            ?: return null
        val payload = envelope.data as? JsonObject
        val sessionId = payload?.string("sessionID")?.takeIf { it.isNotBlank() }
        val type = envelope.type.ifBlank { "unknown" }

        val event = when (type) {
            "server.connected" -> ServerEvent.ServerConnected

            "session.text.started" -> {
                val messageId = payload?.string("assistantMessageID")
                if (sessionId == null || messageId.isNullOrBlank()) return null
                ServerEvent.TextStarted(sessionId, messageId, payload?.int("ordinal") ?: 0)
            }

            "session.text.delta" -> {
                val messageId = payload?.string("assistantMessageID")
                if (sessionId == null || messageId.isNullOrBlank()) return null
                ServerEvent.TextDelta(
                    sessionId = sessionId,
                    messageId = messageId,
                    ordinal = payload?.int("ordinal") ?: 0,
                    delta = payload?.string("delta").orEmpty(),
                )
            }

            "session.text.ended" -> {
                val messageId = payload?.string("assistantMessageID")
                if (sessionId == null || messageId.isNullOrBlank()) return null
                ServerEvent.TextEnded(
                    sessionId = sessionId,
                    messageId = messageId,
                    ordinal = payload?.int("ordinal") ?: 0,
                    text = payload?.string("text").orEmpty(),
                )
            }

            "session.reasoning.started" -> {
                val messageId = payload?.string("assistantMessageID")
                if (sessionId == null || messageId.isNullOrBlank()) return null
                ServerEvent.ReasoningStarted(
                    sessionId = sessionId,
                    messageId = messageId,
                    ordinal = payload?.int("ordinal") ?: 0,
                )
            }

            "session.reasoning.delta" -> {
                val messageId = payload?.string("assistantMessageID")
                if (sessionId == null || messageId.isNullOrBlank()) return null
                ServerEvent.ReasoningDelta(
                    sessionId = sessionId,
                    messageId = messageId,
                    ordinal = payload?.int("ordinal") ?: 0,
                    delta = payload?.string("delta").orEmpty(),
                )
            }

            "session.reasoning.ended" -> {
                val messageId = payload?.string("assistantMessageID")
                if (sessionId == null || messageId.isNullOrBlank()) return null
                ServerEvent.ReasoningEnded(
                    sessionId = sessionId,
                    messageId = messageId,
                    ordinal = payload?.int("ordinal") ?: 0,
                    text = payload?.string("text").orEmpty(),
                )
            }

            "session.tool.input.started" -> {
                val messageId = payload?.string("assistantMessageID")
                val callId = payload?.string("id") ?: payload?.string("callID")
                if (sessionId == null || messageId.isNullOrBlank() || callId.isNullOrBlank()) return null
                ServerEvent.ToolInputStarted(
                    sessionId = sessionId,
                    messageId = messageId,
                    callId = callId,
                    name = payload?.string("name")
                        ?: payload?.string("tool")
                        ?: "tool",
                )
            }

            "session.tool.input.delta" -> {
                val messageId = payload?.string("assistantMessageID")
                val callId = payload?.string("id") ?: payload?.string("callID")
                if (sessionId == null || messageId.isNullOrBlank() || callId.isNullOrBlank()) return null
                ServerEvent.ToolInputDelta(
                    sessionId = sessionId,
                    messageId = messageId,
                    callId = callId,
                    delta = payload?.string("delta").orEmpty(),
                )
            }

            "session.tool.input.ended" -> {
                val messageId = payload?.string("assistantMessageID")
                val callId = payload?.string("id") ?: payload?.string("callID")
                if (sessionId == null || messageId.isNullOrBlank() || callId.isNullOrBlank()) return null
                ServerEvent.ToolInputEnded(
                    sessionId = sessionId,
                    messageId = messageId,
                    callId = callId,
                    text = payload?.string("text").orEmpty(),
                )
            }

            "session.tool.called" -> {
                val messageId = payload?.string("assistantMessageID")
                val callId = payload?.string("id") ?: payload?.string("callID")
                if (sessionId == null || messageId.isNullOrBlank() || callId.isNullOrBlank()) return null
                ServerEvent.ToolCalled(
                    sessionId = sessionId,
                    messageId = messageId,
                    callId = callId,
                    input = payload?.get("input"),
                    executed = payload?.bool("executed") ?: true,
                )
            }

            "session.tool.progress" -> {
                val messageId = payload?.string("assistantMessageID")
                val callId = payload?.string("id") ?: payload?.string("callID")
                if (sessionId == null || messageId.isNullOrBlank() || callId.isNullOrBlank()) return null
                val metadata = payload?.get("metadata") as? JsonObject
                ServerEvent.ToolProgress(
                    sessionId = sessionId,
                    messageId = messageId,
                    callId = callId,
                    exitCode = metadata?.int("exit") ?: metadata?.int("exitCode"),
                    truncated = metadata?.bool("truncated") ?: false,
                )
            }

            "session.tool.success" -> {
                if (sessionId == null) return null
                val event = parseToolResult(payload) ?: return null
                ServerEvent.ToolSuccess(
                    sessionId = sessionId,
                    messageId = event.messageId,
                    callId = event.callId,
                    content = event.content,
                    executed = event.executed,
                    exitCode = event.exitCode,
                    truncated = event.truncated,
                )
            }

            "session.tool.failed" -> {
                if (sessionId == null) return null
                val event = parseToolResult(payload) ?: return null
                ServerEvent.ToolFailed(
                    sessionId = sessionId,
                    messageId = event.messageId,
                    callId = event.callId,
                    content = event.content,
                    executed = event.executed,
                    exitCode = event.exitCode,
                    truncated = event.truncated,
                    status = event.status,
                    message = event.message,
                )
            }

            "session.step.started" -> {
                val messageId = payload?.string("assistantMessageID")
                if (sessionId == null || messageId.isNullOrBlank()) return null
                ServerEvent.StepStarted(
                    sessionId = sessionId,
                    agent = payload?.string("agent")?.takeIf { it.isNotBlank() },
                    modelId = modelIdOf(payload?.get("model")),
                    messageId = messageId,
                    startedAt = payload?.long("started") ?: envelope.created,
                )
            }

            "session.step.failed" -> {
                if (sessionId == null) return null
                ServerEvent.StepFailed(
                    sessionId = sessionId,
                    messageId = payload?.string("assistantMessageID")
                        ?: payload?.string("messageID"),
                    error = errorText(payload?.get("error")),
                )
            }

            "session.step.ended" -> {
                val messageId = payload?.string("assistantMessageID")
                if (sessionId == null || messageId.isNullOrBlank()) return null
                ServerEvent.StepEnded(
                    sessionId = sessionId,
                    messageId = messageId,
                    finish = payload?.string("finish"),
                    cost = payload?.double("cost"),
                    tokens = parseTokens(payload?.get("tokens")),
                )
            }

            "session.execution.started" -> {
                if (sessionId == null) return null
                ServerEvent.ExecutionStarted(sessionId)
            }

            "session.execution.succeeded" -> {
                if (sessionId == null) return null
                ServerEvent.ExecutionFinished(sessionId, "succeeded")
            }

            "session.execution.failed" -> {
                if (sessionId == null) return null
                ServerEvent.ExecutionFinished(sessionId, "failed")
            }

            "session.execution.interrupted" -> {
                if (sessionId == null) return null
                ServerEvent.ExecutionFinished(sessionId, "interrupted")
            }

            "session.usage.updated" -> {
                if (sessionId == null) return null
                ServerEvent.UsageUpdated(
                    sessionId = sessionId,
                    cost = payload?.double("cost"),
                    tokens = parseTokens(payload?.get("tokens")),
                )
            }

            "session.inbox.enqueued" -> {
                if (sessionId == null) return null
                val item = payload?.get("item") as? JsonObject
                val itemPayload = item?.get("payload") as? JsonObject
                ServerEvent.InboxEnqueued(
                    sessionId = sessionId,
                    inboxId = payload?.string("inboxID"),
                    text = itemPayload?.string("text"),
                    delivery = item?.string("delivery"),
                )
            }

            "session.inbox.delivered" -> {
                if (sessionId == null) return null
                ServerEvent.InboxDelivered(sessionId, payload?.string("inboxID"))
            }

            "permission.asked" -> {
                val approval = ApprovalParsing.parsePermission(payload) ?: return null
                ServerEvent.PermissionAsked(approval.sessionId, approval)
            }

            "permission.replied" -> {
                val requestId = payload?.string("requestID")
                    ?: payload?.string("permissionID")
                    ?: payload?.string("id")
                if (requestId == null) return null
                ServerEvent.PermissionReplied(sessionId, requestId)
            }

            else -> ServerEvent.Unknown(type, sessionId)
        }

        return IncomingEvent(
            id = envelope.id,
            created = envelope.created,
            wireType = type,
            event = event,
        )
    }

    /** Test convenience: only the normalized event. */
    fun parseEvent(raw: String): ServerEvent? = parse(raw)?.event

    private fun modelIdOf(element: JsonElement?): String? = when (element) {
        is JsonObject -> element.string("id")?.takeIf { it.isNotBlank() }
        is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
        else -> null
    }

    private data class ToolResultPayload(
        val messageId: String,
        val callId: String,
        val content: List<String>,
        val executed: Boolean,
        val exitCode: Int?,
        val truncated: Boolean,
        val status: String?,
        val message: String?,
    )

    private fun parseToolResult(payload: JsonObject?): ToolResultPayload? {
        if (payload == null) return null
        val messageId = payload.string("assistantMessageID")
        val callId = payload.string("id") ?: payload.string("callID")
        if (messageId.isNullOrBlank() || callId.isNullOrBlank()) return null
        val metadata = payload["metadata"] as? JsonObject
        return ToolResultPayload(
            messageId = messageId,
            callId = callId,
            content = parseContentTexts(payload["content"]),
            executed = payload.bool("executed") ?: true,
            exitCode = metadata?.int("exit") ?: metadata?.int("exitCode"),
            truncated = metadata?.bool("truncated") ?: false,
            status = metadata?.string("status") ?: payload.string("status"),
            message = payload.string("message")
                ?: metadata?.string("message")
                ?: errorText(payload["error"])
                ?: errorText(payload["reason"])
                ?: metadata?.string("error"),
        )
    }

    /** `content:[{type:"text",text}]`; a missing payload yields an empty list. */
    private fun parseContentTexts(element: JsonElement?): List<String> =
        (element as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.string("text") }
            .orEmpty()

    private fun errorText(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonObject -> element.string("message")?.takeIf { it.isNotBlank() }
            ?: element.string("name")?.takeIf { it.isNotBlank() }
        else -> null
    }

    private fun parseTokens(element: JsonElement?): TokenUsage? {
        val obj = element as? JsonObject ?: return null
        val cache = obj["cache"] as? JsonObject
        return TokenUsage(
            input = obj.long("input"),
            output = obj.long("output"),
            reasoning = obj.long("reasoning"),
            cacheRead = cache?.long("read"),
            cacheWrite = cache?.long("write"),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}
