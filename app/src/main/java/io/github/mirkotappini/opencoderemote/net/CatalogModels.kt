package io.github.mirkotappini.opencoderemote.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ---------------------------------------------------------------------------
// Raw DTOs of the M3 catalogs. Undeclared fields (agent.system,
// agent.permissions, skill.content, model.settings) are ignored by the parser:
// `settings` may contain apiKey and must never enter the UI model.
// ---------------------------------------------------------------------------

/** Common envelope for `/api/agent`, `/api/skill`, `/api/mcp`, `/api/model`. */
@Serializable
data class CatalogEnvelope<T>(
    val data: List<T> = emptyList(),
    val location: LocationDto? = null,
)

@Serializable
data class AgentDto(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val mode: String? = null,
    val hidden: Boolean = false,
    val model: ModelDto? = null,
)

@Serializable
data class SkillDto(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val path: String? = null,
)

/** `status` is normally an object `{status:"connected"}`; we also accept a string. */
@Serializable
data class McpDto(
    val name: String = "",
    val status: JsonElement? = null,
)

@Serializable
data class ModelCapabilitiesDto(
    val input: List<String> = emptyList(),
    val output: List<String> = emptyList(),
)

@Serializable
data class ModelCatalogDto(
    val id: String = "",
    val modelID: String = "",
    val providerID: String = "",
    val name: String? = null,
    val family: String? = null,
    val capabilities: ModelCapabilitiesDto? = null,
)

@Serializable
data class ProjectDto(
    val id: String? = null,
    val name: String? = null,
    val directory: String? = null,
    val canonical: String? = null,
)

/** `GET /api/location` (no `data` envelope). */
@Serializable
data class LocationDto(
    val directory: String = "",
    val project: ProjectDto? = null,
)

// ---------------------------------------------------------------------------
// Normalized models for the UI (composables see no JSON)
// ---------------------------------------------------------------------------

data class AgentUi(
    val id: String,
    val name: String,
    val description: String? = null,
    val mode: String? = null,
    val hidden: Boolean = false,
    val modelId: String? = null,
    val modelProvider: String? = null,
)

data class SkillUi(
    val id: String,
    val name: String,
    val description: String? = null,
    val path: String? = null,
)

data class McpUi(
    val name: String,
    val status: String = "unknown",
)

/**
 * Model for the UI. Deliberately without `settings`: the source DTO may
 * contain `settings.apiKey`, which must have no field to land in.
 */
@Serializable
data class ModelUi(
    val id: String,
    val modelID: String,
    val providerID: String,
    val name: String? = null,
    val family: String? = null,
    val inputModalities: List<String> = emptyList(),
    val outputModalities: List<String> = emptyList(),
)

data class LocationUi(
    val directory: String,
    val projectName: String? = null,
    val projectId: String? = null,
)

/** Snapshot of the M3 catalogs, with an in-memory TTL cache. */
data class Catalog(
    val agents: List<AgentUi> = emptyList(),
    val skills: List<SkillUi> = emptyList(),
    val mcps: List<McpUi> = emptyList(),
    val models: List<ModelUi> = emptyList(),
    val location: LocationUi? = null,
    val loadedAt: Long? = null,
) {
    /** Agents showable in the drawer: neither hidden nor subagents. */
    val agentsForDrawer: List<AgentUi>
        get() = agents.filter { !it.hidden && it.mode != "subagent" }

    val subagents: List<AgentUi>
        get() = agents.filter { !it.hidden && it.mode == "subagent" }

    fun isFresh(now: Long, ttlMs: Long = DEFAULT_TTL_MS): Boolean {
        val loaded = loadedAt ?: return false
        return now >= loaded && now - loaded < ttlMs
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 5 * 60_000L
    }
}

// ---------------------------------------------------------------------------
// Tolerant parsing of the catalogs
// ---------------------------------------------------------------------------

object CatalogParsing {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun parseAgents(body: String): List<AgentUi> =
        json.decodeFromString<CatalogEnvelope<AgentDto>>(body).data
            .mapNotNull(::normalizeAgent)
            .distinctBy { it.id }

    fun parseSkills(body: String): List<SkillUi> =
        json.decodeFromString<CatalogEnvelope<SkillDto>>(body).data
            .mapNotNull(::normalizeSkill)
            .distinctBy { it.id }

    fun parseMcps(body: String): List<McpUi> =
        json.decodeFromString<CatalogEnvelope<McpDto>>(body).data
            .mapNotNull(::normalizeMcp)
            .distinctBy { it.name }

    fun parseModels(body: String): List<ModelUi> =
        json.decodeFromString<CatalogEnvelope<ModelCatalogDto>>(body).data
            .mapNotNull(::normalizeModel)
            // `id` is the concrete reference (Model.Ref.id); `modelID` is the family and repeats
            // (e.g. gpt-4.1 and gpt-4.1-fast). Dedupe on the provider+id pair.
            .distinctBy { it.providerID + "/" + it.id }

    fun parseLocation(body: String): LocationUi? =
        runCatching { json.decodeFromString<LocationDto>(body) }
            .getOrNull()
            ?.let(::normalizeLocation)

    /** `GET /api/session?parentID=…`: same shape as `/api/session`. */
    fun parseChildren(body: String): List<SessionUi> = ApiParsing.parseSessions(body).sessions

    fun normalizeAgent(dto: AgentDto): AgentUi? {
        if (dto.id.isBlank()) return null
        return AgentUi(
            id = dto.id,
            name = dto.name.takeIf { it.isNotBlank() } ?: dto.id,
            description = dto.description?.takeIf { it.isNotBlank() },
            mode = dto.mode?.takeIf { it.isNotBlank() },
            hidden = dto.hidden,
            modelId = dto.model?.id?.takeIf { it.isNotBlank() },
            modelProvider = dto.model?.providerID?.takeIf { it.isNotBlank() },
        )
    }

    fun normalizeSkill(dto: SkillDto): SkillUi? {
        if (dto.id.isBlank()) return null
        return SkillUi(
            id = dto.id,
            name = dto.name.takeIf { it.isNotBlank() } ?: dto.id,
            description = dto.description?.takeIf { it.isNotBlank() },
            path = dto.path?.takeIf { it.isNotBlank() },
        )
    }

    fun normalizeMcp(dto: McpDto): McpUi? {
        if (dto.name.isBlank()) return null
        return McpUi(
            name = dto.name,
            status = mcpStatus(dto.status),
        )
    }

    fun normalizeModel(dto: ModelCatalogDto): ModelUi? {
        val id = dto.id.takeIf { it.isNotBlank() }
            ?: dto.modelID.takeIf { it.isNotBlank() }
            ?: return null
        val modelId = dto.modelID.takeIf { it.isNotBlank() } ?: id
        return ModelUi(
            id = id,
            modelID = modelId,
            providerID = dto.providerID,
            name = dto.name?.takeIf { it.isNotBlank() },
            family = dto.family?.takeIf { it.isNotBlank() },
            inputModalities = dto.capabilities?.input.orEmpty(),
            outputModalities = dto.capabilities?.output.orEmpty(),
        )
    }

    fun normalizeLocation(dto: LocationDto): LocationUi {
        val project = dto.project
        return LocationUi(
            directory = dto.directory.takeIf { it.isNotBlank() }
                ?: project?.directory.orEmpty(),
            projectName = project?.name?.takeIf { it.isNotBlank() }
                ?: basename(project?.canonical)
                ?: basename(project?.directory),
            projectId = project?.id?.takeIf { it.isNotBlank() },
        )
    }

    private fun mcpStatus(element: JsonElement?): String {
        val raw = when (element) {
            is JsonObject -> (element["status"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> element.contentOrNull
            else -> null
        }
        return raw?.takeIf { it.isNotBlank() } ?: "unknown"
    }

    private fun basename(path: String?): String? =
        path?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}

/**
 * Unique key for the lists: the concrete reference is `id` (the one that
 * `Model.Ref` accepts), while `modelID` is the family and may repeat across
 * entries.
 */
fun ModelUi.pickerKey(): String = providerID + "/" + id

/** True if this entry is the one saved in the session (`Session.Info.model`). */
fun ModelUi.referencesSession(sessionModelId: String?, sessionProvider: String?): Boolean {
    if (sessionModelId.isNullOrBlank()) return false
    if (sessionModelId != id) return false
    return sessionProvider.isNullOrBlank() || sessionProvider == providerID
}

