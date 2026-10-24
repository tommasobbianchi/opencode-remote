package io.github.mirkotappini.opencoderemote.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Subset of `GET /api/info` (M0: only version + pid). */
@Serializable
data class ApiInfo(
    val version: String = "?",
    val pid: Long = -1,
)

@Serializable
private data class PromptBody(
    val text: String,
    val delivery: String = "steer",
)

@Serializable
private data class PromptAckDto(
    val id: String? = null,
    val sessionID: String? = null,
    val type: String? = null,
)

@Serializable
private data class PromptAckEnvelope(
    val data: PromptAckDto? = null,
)

@Serializable
private data class AgentBody(
    val agent: String,
)

@Serializable
private data class ModelRefBody(
    val id: String,
    val providerID: String,
    val variant: String? = null,
)

@Serializable
private data class ModelBody(
    val model: ModelRefBody,
)

@Serializable
private data class SkillBody(
    val id: String,
    val resume: Boolean? = null,
)

/**
 * Session operations used by `SessionCommands`. A narrow interface so the
 * POST → GET order can be verified without network in the JVM tests.
 */
interface SessionCommandApi {
    fun switchAgent(sessionId: String, agentId: String)

    fun switchModel(sessionId: String, providerId: String, modelId: String, variant: String?)

    fun session(sessionId: String): SessionUi
}

/** Pure logic of the switches: after the 204 it re-reads the session from the server. */
object SessionCommands {

    fun switchAgent(api: SessionCommandApi, sessionId: String, agentId: String): SessionUi {
        api.switchAgent(sessionId, agentId)
        return api.session(sessionId)
    }

    fun switchModel(
        api: SessionCommandApi,
        sessionId: String,
        providerId: String,
        modelId: String,
        variant: String?,
    ): SessionUi {
        api.switchModel(sessionId, providerId, modelId, variant)
        return api.session(sessionId)
    }
}

/** HTTP error with code, no body (the body may contain sensitive data). */
class ApiException(
    val code: Int,
    val path: String,
) : IOException("HTTP $code su $path")

/**
 * REST client for M1.
 *
 * The Authorization header is built here and lives only in memory: it is never
 * logged, serialized, or written to disk.
 */
class OpenCodeApi(
    private val client: OkHttpClient,
    baseUrl: String,
    user: String,
    password: String,
) : SessionCommandApi {
    private val base = baseUrl.trim().trimEnd('/')

    @Suppress("unused")
    private val authorization: String = Credentials.basic(user, password, Charsets.UTF_8)

    /** Header ready for the requests (never logged). */
    internal fun authHeader(): String = authorization

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Throws(IOException::class)
    fun info(): ApiInfo {
        return try {
            json.decodeFromString<ApiInfo>(get(url("/api/info")))
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Invalid /api/info response (${e.javaClass.simpleName})")
        }
    }

    /** `GET /api/session?limit=..&order=desc` */
    @Throws(IOException::class)
    fun sessions(limit: Int = 50): SessionsPage {
        val url = url("/api/session")
            .newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("order", "desc")
            .build()
        return ApiParsing.parseSessions(get(url))
    }

    /** `GET /api/session/{id}/message?order=desc` — page in chronological order. */
    @Throws(IOException::class)
    fun messages(
        sessionId: String,
        limit: Int = 50,
        cursor: String? = null,
    ): MessagesPage {
        val builder = url("/api/session")
            .newBuilder()
            .addPathSegment(sessionId)
            .addPathSegment("message")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("order", "desc")
        if (cursor != null) builder.addQueryParameter("cursor", cursor)
        return ApiParsing.parseMessages(get(builder.build()))
    }

    /** `GET /api/session/{id}/permission` — pending requests (may be empty). */
    @Throws(IOException::class)
    fun permissions(sessionId: String): List<ApprovalUi> {
        val url = url("/api/session")
            .newBuilder()
            .addPathSegment(sessionId)
            .addPathSegment("permission")
            .build()
        return ApprovalParsing.parsePermissions(get(url))
    }

    /**
     * `POST /api/session/{id}/permission/{requestID}/reply` → 204.
     * A 404 with `_tag: PermissionNotFoundError` becomes [ApprovalExpiredException].
     */
    @Throws(IOException::class)
    fun replyPermission(
        sessionId: String,
        requestId: String,
        decision: String,
        message: String? = null,
    ) {
        if (!ApprovalReply.isDecision(decision)) {
            throw IOException("invalid decision")
        }
        val url = url("/api/session")
            .newBuilder()
            .addPathSegment(sessionId)
            .addPathSegment("permission")
            .addPathSegment(requestId)
            .addPathSegment("reply")
            .build()
        val body = ApprovalReply.bodyJson(decision, message).toRequestBody(JSON)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .header("Accept", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) return
            // The body is only used to discriminate the 404; it is not exposed.
            val payload = response.body?.string()
            throw ApprovalErrors.fromHttpFailure(response.code, payload, url.encodedPath)
        }
    }

    /**
     * `POST /api/session` with body `{}`: the server applies the defaults
     * (title, agent, model). Responds with the envelope `{data: Session.Info}`.
     */
    @Throws(IOException::class)
    fun createSession(): SessionUi {
        val payload = post(url("/api/session"), EMPTY_JSON_BODY)
        return ApiParsing.parseSession(payload) ?: throw IOException("session not created")
    }

    /** `GET /api/session/active`; empty `data` (`{}`) = no active session. */
    @Throws(IOException::class)
    fun activeSession(): String? {
        return ApiParsing.parseActiveSession(get(url("/api/session/active")))
    }

    /** `GET /api/session/{id}` — authoritative state after a switch. */
    @Throws(IOException::class)
    override fun session(sessionId: String): SessionUi {
        val value = ApiParsing.parseSession(
            get(url("/api/session").newBuilder().addPathSegment(sessionId).build()),
        )
        return value ?: throw IOException("session not found")
    }

    /** `GET /api/session?parentID=…` — child sessions (background tasks). */
    @Throws(IOException::class)
    fun children(sessionId: String, limit: Int = 50): List<SessionUi> {
        val url = url("/api/session")
            .newBuilder()
            .addQueryParameter("parentID", sessionId)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("order", "desc")
            .build()
        return ApiParsing.parseSessions(get(url)).sessions
    }

    /** `GET /api/agent` — only the showable fields; `system`/`permissions` ignored. */
    @Throws(IOException::class)
    fun agents(): List<AgentUi> = CatalogParsing.parseAgents(get(url("/api/agent")))

    /** `GET /api/skill` — `content` ignored in parsing. */
    @Throws(IOException::class)
    fun skills(): List<SkillUi> = CatalogParsing.parseSkills(get(url("/api/skill")))

    /** `GET /api/mcp` — state normalized to a string. */
    @Throws(IOException::class)
    fun mcps(): List<McpUi> = CatalogParsing.parseMcps(get(url("/api/mcp")))

    /** `GET /api/model` — `settings` (apiKey) is never parsed. */
    @Throws(IOException::class)
    fun models(): List<ModelUi> = CatalogParsing.parseModels(get(url("/api/model")))

    /** `GET /api/location` — response without a `data` envelope. */
    @Throws(IOException::class)
    fun location(): LocationUi? = CatalogParsing.parseLocation(get(url("/api/location")))

    /** `POST /api/session/{id}/agent` → 204. */
    @Throws(IOException::class)
    override fun switchAgent(sessionId: String, agentId: String) {
        val url = url("/api/session")
            .newBuilder()
            .addPathSegment(sessionId)
            .addPathSegment("agent")
            .build()
        val body = json.encodeToString(
            AgentBody.serializer(),
            AgentBody(agent = agentId),
        ).toRequestBody(JSON)
        post(url, body)
    }

    /** `POST /api/session/{id}/model` → 204. */
    @Throws(IOException::class)
    override fun switchModel(
        sessionId: String,
        providerId: String,
        modelId: String,
        variant: String?,
    ) {
        val url = url("/api/session")
            .newBuilder()
            .addPathSegment(sessionId)
            .addPathSegment("model")
            .build()
        val body = json.encodeToString(
            ModelBody.serializer(),
            ModelBody(
                model = ModelRefBody(
                    id = modelId,
                    providerID = providerId,
                    variant = variant,
                ),
            ),
        ).toRequestBody(JSON)
        post(url, body)
    }

    /** `POST /api/experimental/session/{id}/skill` → 204. */
    @Throws(IOException::class)
    fun activateSkill(sessionId: String, skillId: String) {
        val url = url("/api/experimental/session")
            .newBuilder()
            .addPathSegment(sessionId)
            .addPathSegment("skill")
            .build()
        val body = json.encodeToString(
            SkillBody.serializer(),
            SkillBody(id = skillId),
        ).toRequestBody(JSON)
        post(url, body)
    }

    /**
     * `POST /api/session/{id}/prompt` — the 200 ack is not the output: the text
     * arrives only via SSE. Returns the confirmed user message id, if present.
     */
    @Throws(IOException::class)
    fun prompt(sessionId: String, text: String, delivery: String = "steer"): String? {
        val url = url("/api/session")
            .newBuilder()
            .addPathSegment(sessionId)
            .addPathSegment("prompt")
            .build()
        val body = json.encodeToString(
            PromptBody.serializer(),
            PromptBody(text = text, delivery = delivery),
        ).toRequestBody(JSON)
        val response = post(url, body)
        return runCatching {
            json.decodeFromString<PromptAckEnvelope>(response).data?.id
        }.getOrNull()
    }

    /** `POST /api/session/{id}/interrupt` — the outcome arrives as an SSE event. */
    @Throws(IOException::class)
    fun interrupt(sessionId: String) {
        val url = url("/api/session")
            .newBuilder()
            .addPathSegment(sessionId)
            .addPathSegment("interrupt")
            .build()
        post(url, EMPTY_BODY)
    }

    fun eventUrl(): String = "$base/api/event"

    private fun url(path: String): HttpUrl = "$base$path".toHttpUrl()

    @Throws(IOException::class)
    private fun post(url: HttpUrl, body: RequestBody): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .header("Accept", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(response.code, url.encodedPath)
            }
            return payload
        }
    }

    @Throws(IOException::class)
    private fun get(url: HttpUrl): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(response.code, url.encodedPath)
            }
            return body
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
        val EMPTY_JSON_BODY: RequestBody = "{}".toRequestBody(JSON)
    }
}
