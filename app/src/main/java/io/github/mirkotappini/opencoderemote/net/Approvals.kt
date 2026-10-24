package io.github.mirkotappini.opencoderemote.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.IOException
import java.security.MessageDigest

// ---------------------------------------------------------------------------
// Raw DTO for `Permission.Request` (docs/API-M4.md)
// ---------------------------------------------------------------------------

@Serializable
data class PermissionSourceDto(
    val type: String? = null,
    val messageID: String? = null,
    val id: String? = null,
)

@Serializable
data class PermissionRequestDto(
    val id: String = "",
    val sessionID: String = "",
    val action: String = "",
    val resources: List<String> = emptyList(),
    val save: List<String> = emptyList(),
    val metadata: JsonElement? = null,
    val source: PermissionSourceDto? = null,
    val message: String? = null,
    val created: Long? = null,
    val time: Long? = null,
)

@Serializable
data class PermissionsEnvelope(val data: List<PermissionRequestDto> = emptyList())

/** Normalized model seen by the UI: no JSON, no unknown field. */
data class ApprovalUi(
    val id: String,
    val sessionId: String,
    val action: String,
    val resources: List<String> = emptyList(),
    val save: List<String> = emptyList(),
    val sourceLabel: String? = null,
    val message: String? = null,
    val createdAt: Long? = null,
)

object ApprovalParsing {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** `GET /api/session/{id}/permission` → lenient list. */
    fun parsePermissions(body: String): List<ApprovalUi> =
        json.decodeFromString<PermissionsEnvelope>(body).data.mapNotNull(::normalize)

    /** `data` of `permission.asked` (same shape as the GET, without envelope). */
    fun parsePermission(element: JsonElement?): ApprovalUi? {
        val obj = element as? JsonObject ?: return null
        val dto = runCatching {
            json.decodeFromJsonElement<PermissionRequestDto>(obj)
        }.getOrNull() ?: return null
        return normalize(dto)
    }

    fun normalize(dto: PermissionRequestDto): ApprovalUi? {
        if (dto.id.isBlank() || dto.sessionID.isBlank()) return null
        return ApprovalUi(
            id = dto.id,
            sessionId = dto.sessionID,
            action = dto.action.takeIf { it.isNotBlank() } ?: "permission",
            resources = dto.resources.filter { it.isNotBlank() },
            save = dto.save.filter { it.isNotBlank() },
            sourceLabel = dto.source?.type?.takeIf { it.isNotBlank() }
                ?: dto.source?.id?.takeIf { it.isNotBlank() },
            message = dto.message?.takeIf { it.isNotBlank() },
            createdAt = dto.created ?: dto.time,
        )
    }
}

// ---------------------------------------------------------------------------
// Response: `{"decision":"once|always|reject"}` (+ optional `message`)
// ---------------------------------------------------------------------------

@Serializable
internal data class PermissionReplyBody(
    val decision: String,
    val message: String? = null,
)

object ApprovalReply {
    const val ONCE = "once"
    const val ALWAYS = "always"
    const val REJECT = "reject"

    val decisions: Set<String> = setOf(ONCE, ALWAYS, REJECT)

    private val json = Json { explicitNulls = false }

    fun isDecision(value: String): Boolean = value in decisions

    /**
     * Compact JSON body. `message` is omitted when null or empty: the
     * contract accepts both `{"decision":"once"}` and the variant with reason.
     * Does not throw for unknown decisions: the caller drops them first.
     */
    fun bodyJson(decision: String, message: String? = null): String =
        json.encodeToString(
            PermissionReplyBody.serializer(),
            PermissionReplyBody(
                decision = decision,
                message = message?.takeIf { it.isNotBlank() },
            ),
        )
}

// ---------------------------------------------------------------------------
// Errors: 404 PermissionNotFound ≠ 404 session
// ---------------------------------------------------------------------------

/** The request no longer exists (expired or already answered). */
class ApprovalExpiredException(
    val requestId: String? = null,
) : IOException("request no longer valid")

object ApprovalErrors {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /**
     * Translates a non-2xx HTTP of the reply. The body is read only to
     * distinguish `PermissionNotFoundError`; it never ends up in the exception.
     */
    fun fromHttpFailure(code: Int, body: String?, path: String): IOException {
        if (code == 404) {
            val obj = parseObject(body)
            val tag = (obj?.get("_tag") as? JsonPrimitive)?.contentOrNull
            if (tag == "PermissionNotFoundError") {
                val requestId = (obj["requestID"] as? JsonPrimitive)?.contentOrNull
                return ApprovalExpiredException(requestId)
            }
        }
        return ApiException(code, path)
    }

    private fun parseObject(body: String?): JsonObject? {
        if (body.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    }
}

// ---------------------------------------------------------------------------
// Pure shared utilities for notifier and watcher
// ---------------------------------------------------------------------------

object ApprovalIds {

    /**
     * Stable notification id for `requestID` (SHA-256, 28 bits). Stable across
     * processes: the same requestID always produces the same id. The low
     * threshold avoids collisions with the service's fixed ids.
     */
    fun notificationId(requestId: String): Int {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(requestId.toByteArray(Charsets.UTF_8))
        var value = 0
        for (i in 0 until 4) {
            value = (value shl 8) or (digest[i].toInt() and 0xFF)
        }
        return (value and 0x0FFFFFFF) + 0x10000000
    }

    /** requestCode distinct per request and action (PendingIntent ignores the extras). */
    fun actionRequestCode(requestId: String, action: String): Int =
        (notificationId(requestId) * 31 + action.hashCode()) and 0x7FFFFFFF
}

/**
 * Notification dedupe: one per `requestID`, until resolution.
 * Pure and testable on JVM.
 */
class ApprovalNotificationTracker {

    private val shown = LinkedHashSet<String>()

    fun isShown(requestId: String): Boolean = requestId in shown

    /** true only the first time for a request: in that case it must be notified. */
    fun markShown(requestId: String): Boolean = shown.add(requestId)

    /** true if the request was notified: in that case it must be cancelled. */
    fun markResolved(requestId: String): Boolean = shown.remove(requestId)

    fun shownIds(): Set<String> = shown.toSet()

    fun clear() = shown.clear()
}

/**
 * Per-session throttle (~2 s): limits the alignment GETs when bursts of
 * events arrive on the same session.
 */
class ApprovalThrottle(
    private val minIntervalMs: Long = 2_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastFetch = HashMap<String, Long>()

    fun shouldFetch(sessionId: String): Boolean {
        val now = clock()
        val previous = lastFetch[sessionId]
        if (previous != null && now - previous < minIntervalMs) return false
        lastFetch[sessionId] = now
        return true
    }

    /** Force the next fetch (e.g. after `permission.replied`). */
    fun reset(sessionId: String) {
        lastFetch.remove(sessionId)
    }

    fun clear() = lastFetch.clear()
}
