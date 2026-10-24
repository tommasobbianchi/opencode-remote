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
// DTO grezzi di `Permission.Request` (docs/API-M4.md)
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

/** Modello normalizzato visto dalla UI: nessun JSON, nessun campo sconosciuto. */
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

    /** `GET /api/session/{id}/permission` → lista tollerante. */
    fun parsePermissions(body: String): List<ApprovalUi> =
        json.decodeFromString<PermissionsEnvelope>(body).data.mapNotNull(::normalize)

    /** `data` di `permission.asked` (stessa forma della GET, senza involucro). */
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
// Risposta: `{"decision":"once|always|reject"}` (+ `message` facoltativo)
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
     * Body JSON compatto. `message` viene omesso quando null o vuoto: il
     * contratto accetta sia `{"decision":"once"}` sia la variante con motivo.
     * Non lancia per decisioni ignote: il chiamante le scarta prima.
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
// Errori: 404 PermissionNotFound ≠ 404 sessione
// ---------------------------------------------------------------------------

/** La richiesta non esiste più (scaduta o già risposta). */
class ApprovalExpiredException(
    val requestId: String? = null,
) : IOException("richiesta non più valida")

object ApprovalErrors {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /**
     * Traduce un HTTP non-2xx della reply. Il corpo viene letto solo per
     * distinguere `PermissionNotFoundError`; non finisce mai nell'eccezione.
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
// Utilità pure condivise da notifier e watcher
// ---------------------------------------------------------------------------

object ApprovalIds {

    /**
     * Id stabile di notifica per `requestID` (SHA-256, 28 bit). Stabile tra
     * processi: lo stesso requestID produce sempre lo stesso id. La soglia
     * bassa evita collisioni con gli id fissi del servizio.
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

    /** requestCode distinto per richiesta e azione (PendingIntent ignora gli extra). */
    fun actionRequestCode(requestId: String, action: String): Int =
        (notificationId(requestId) * 31 + action.hashCode()) and 0x7FFFFFFF
}

/**
 * Dedupe delle notifiche: una sola per `requestID`, fino alla risoluzione.
 * Puro e testabile in JVM.
 */
class ApprovalNotificationTracker {

    private val shown = LinkedHashSet<String>()

    fun isShown(requestId: String): Boolean = requestId in shown

    /** true solo la prima volta per una richiesta: in quel caso va notificata. */
    fun markShown(requestId: String): Boolean = shown.add(requestId)

    /** true se la richiesta era notificata: in quel caso va annullata. */
    fun markResolved(requestId: String): Boolean = shown.remove(requestId)

    fun shownIds(): Set<String> = shown.toSet()

    fun clear() = shown.clear()
}

/**
 * Throttle per sessione (~2 s): limita le GET di allineamento quando arrivano
 * raffiche di eventi sulla stessa sessione.
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

    /** Forza il prossimo fetch (es. dopo `permission.replied`). */
    fun reset(sessionId: String) {
        lastFetch.remove(sessionId)
    }

    fun clear() = lastFetch.clear()
}
