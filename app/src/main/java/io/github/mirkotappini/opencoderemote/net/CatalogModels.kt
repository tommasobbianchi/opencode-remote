package io.github.mirkotappini.opencoderemote.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ---------------------------------------------------------------------------
// DTO grezzi dei cataloghi M3. I campi non dichiarati (agent.system,
// agent.permissions, skill.content, model.settings) vengono ignorati dal parser:
// `settings` può contenere apiKey e non deve mai entrare nel modello UI.
// ---------------------------------------------------------------------------

/** Involucro comune a `/api/agent`, `/api/skill`, `/api/mcp`, `/api/model`. */
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

/** `status` è normalmente un oggetto `{status:"connected"}`; accettiamo anche stringa. */
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

/** `GET /api/location` (nessun involucro `data`). */
@Serializable
data class LocationDto(
    val directory: String = "",
    val project: ProjectDto? = null,
)

// ---------------------------------------------------------------------------
// Modelli normalizzati per la UI (i composable non vedono JSON)
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
 * Modello per la UI. Volutamente senza `settings`: il DTO di origine può
 * contenere `settings.apiKey`, che non deve avere alcun campo in cui finire.
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

/** Snapshot dei cataloghi M3, con cache in memoria a TTL. */
data class Catalog(
    val agents: List<AgentUi> = emptyList(),
    val skills: List<SkillUi> = emptyList(),
    val mcps: List<McpUi> = emptyList(),
    val models: List<ModelUi> = emptyList(),
    val location: LocationUi? = null,
    val loadedAt: Long? = null,
) {
    /** Agenti mostrabili nel drawer: né nascosti né subagent. */
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
// Parsing tollerante dei cataloghi
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
            // `id` è il riferimento concreto (Model.Ref.id); `modelID` è la famiglia e si ripete
            // (es. gpt-4.1 e gpt-4.1-fast). Dedupe sulla coppia provider+id.
            .distinctBy { it.providerID + "/" + it.id }

    fun parseLocation(body: String): LocationUi? =
        runCatching { json.decodeFromString<LocationDto>(body) }
            .getOrNull()
            ?.let(::normalizeLocation)

    /** `GET /api/session?parentID=…`: stessa forma di `/api/session`. */
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
 * Chiave univoca per le liste: il riferimento concreto è `id` (quello che accetta
 * `Model.Ref`), mentre `modelID` è la famiglia e può ripetersi su più voci.
 */
fun ModelUi.pickerKey(): String = providerID + "/" + id

/** Vero se questa voce è quella salvata nella sessione (`Session.Info.model`). */
fun ModelUi.referencesSession(sessionModelId: String?, sessionProvider: String?): Boolean {
    if (sessionModelId.isNullOrBlank()) return false
    if (sessionModelId != id) return false
    return sessionProvider.isNullOrBlank() || sessionProvider == providerID
}

