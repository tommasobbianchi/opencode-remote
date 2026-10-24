package io.github.mirkotappini.opencoderemote.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test JVM di M3 (layer dati del drawer): cataloghi, parsing difensivo e
 * sequenza POST → GET degli switch. Tutto offline, payload inline.
 */
class M3Test {

    // 1. Agenti: nascosti fuori dal drawer, subagent in sezione separata ------

    @Test
    fun `hidden agents excluded from the drawer and subagents separate`() {
        val body = """
            {"location":{"directory":"/Users/patchouli"},"data":[
              {"id":"orchestrator","name":"orchestrator","mode":"primary","hidden":false,
               "description":"orchestratore",
               "model":{"id":"deepseek-flash","providerID":"deepseek","variant":"default"},
               "system":"prompt enorme da ignorare","permissions":[{"action":"read"}]},
              {"id":"build","name":"build","mode":"primary","hidden":false},
              {"id":"plan","name":"plan","mode":"primary","hidden":false},
              {"id":"compaction","name":"compaction","mode":"primary","hidden":true},
              {"id":"title","name":"title","mode":"primary","hidden":true},
              {"id":"summary","name":"summary","mode":"primary","hidden":true},
              {"id":"explorer","name":"explorer","mode":"subagent","hidden":false},
              {"id":"fixer","name":"fixer","mode":"subagent","hidden":false}
            ]}
        """.trimIndent()

        val agents = CatalogParsing.parseAgents(body)
        val catalog = Catalog(agents = agents)

        assertEquals(8, agents.size)
        assertEquals(
            listOf("orchestrator", "build", "plan"),
            catalog.agentsForDrawer.map { it.id },
        )
        assertEquals(
            listOf("explorer", "fixer"),
            catalog.subagents.map { it.id },
        )
        assertTrue(catalog.agentsForDrawer.none { it.hidden })
        assertTrue(catalog.agentsForDrawer.none { it.mode == "subagent" })

        val orchestrator = agents.first { it.id == "orchestrator" }
        assertEquals("deepseek-flash", orchestrator.modelId)
        assertEquals("deepseek", orchestrator.modelProvider)
        // `system` and `permissions` have no field in the UI model.
        assertNull(agents.firstOrNull { it.id == "build" }?.modelId)
    }

    // 2. Models: settings/apiKey must not end up in ModelUi -------------------

    @Test
    fun `apiKey in settings does not appear in the normalized model`() {
        val body = """
            {"location":{"directory":"/Users/patchouli"},"data":[
              {"id":"deepseek-flash","modelID":"deepseek-flash","providerID":"deepseek",
               "name":"DeepSeek Flash","family":"deepseek",
               "capabilities":{"tools":true,"input":["text","image"],"output":["text"]},
               "settings":{"apiKey":"sk-segreto","baseURL":"https://example.invalid"},
               "variants":[{"id":"high","settings":{"reasoningEffort":"high"}}]}
            ]}
        """.trimIndent()

        val model = CatalogParsing.parseModels(body).single()

        assertEquals("deepseek-flash", model.id)
        assertEquals("deepseek-flash", model.modelID)
        assertEquals("deepseek", model.providerID)
        assertEquals("DeepSeek Flash", model.name)
        assertEquals("deepseek", model.family)
        assertEquals(listOf("text", "image"), model.inputModalities)
        assertEquals(listOf("text"), model.outputModalities)

        val encoded = Json.encodeToString(ModelUi.serializer(), model)
        assertFalse(encoded.contains("sk-segreto"))
        assertFalse(encoded.contains("settings"))
        assertFalse(encoded.contains("apiKey"))
        assertFalse(model.toString().contains("sk-segreto"))
    }

    // 3. MCP: status.status e fallback "unknown" ------------------------------

    @Test
    fun `status mcp normalizzato con fallback unknown`() {
        val body = """
            {"location":{"directory":"/Users/patchouli"},"data":[
              {"name":"context7","status":{"status":"connected"}},
              {"name":"senza-stato"},
              {"name":"guasto","status":{"status":"failed"}}
            ]}
        """.trimIndent()

        val mcps = CatalogParsing.parseMcps(body)

        assertEquals(3, mcps.size)
        assertEquals("connected", mcps.first { it.name == "context7" }.status)
        assertEquals("unknown", mcps.first { it.name == "senza-stato" }.status)
        assertEquals("failed", mcps.first { it.name == "guasto" }.status)
    }

    // 4. Sessioni figlie: mapping e parentID assente --------------------------

    @Test
    fun `children mapped and missing parentID does not break`() {
        val body = """
            {"data":[
              {"id":"ses_child","parentID":"ses_parent","agent":"fixer",
               "model":{"id":"deepseek-flash","providerID":"deepseek","variant":"high"},
               "time":{"created":1000,"updated":2000},"title":"task figlio"},
              {"id":"ses_orphan","time":{"created":1500},"title":""}
            ],"cursor":{"previous":null,"next":null}}
        """.trimIndent()

        val children = CatalogParsing.parseChildren(body)

        assertEquals(2, children.size)
        val child = children.first { it.id == "ses_child" }
        assertEquals("task figlio", child.title)
        assertEquals("fixer", child.agent)
        assertEquals("deepseek", child.modelProvider)
        assertEquals("high", child.variant)
        assertEquals(2000L, child.updatedAt)

        val orphan = children.first { it.id == "ses_orphan" }
        assertEquals("ses_orphan", orphan.title)
        assertNull(orphan.modelId)
    }

    // 5. TTL di 5 minuti ------------------------------------------------------

    @Test
    fun `catalogo fresco sotto cinque minuti e stantio sopra`() {
        val now = 1_000_000L
        val fresh = Catalog(loadedAt = now)
        val almostStale = Catalog(loadedAt = now)

        assertTrue(fresh.isFresh(now))
        assertTrue(almostStale.isFresh(now + 5 * 60_000L - 1))
        assertFalse(almostStale.isFresh(now + 5 * 60_000L))
        assertFalse(Catalog().isFresh(now))
    }

    // 6. switchAgent: POST seguito da GET /api/session/{id} -------------------

    @Test
    fun `switchAgent rereads the session after success`() {
        val fake = RecordingSessionApi(
            session = SessionUi(id = "ses_1", title = "sessione", agent = "fixer"),
        )

        val updated = SessionCommands.switchAgent(fake, "ses_1", "fixer")

        assertEquals(
            listOf("switchAgent:ses_1:fixer", "session:ses_1"),
            fake.calls,
        )
        assertEquals("fixer", updated.agent)
        assertEquals("sessione", updated.title)
    }

    @Test
    fun `switchModel rereads the session after success`() {
        val fake = RecordingSessionApi(
            session = SessionUi(
                id = "ses_2",
                title = "sessione",
                modelId = "deepseek-flash",
                modelProvider = "deepseek",
                variant = "high",
            ),
        )

        val updated = SessionCommands.switchModel(
            fake,
            "ses_2",
            "deepseek",
            "deepseek-flash",
            "high",
        )

        assertEquals(
            listOf("switchModel:ses_2:deepseek:deepseek-flash:high", "session:ses_2"),
            fake.calls,
        )
        assertEquals("deepseek-flash", updated.modelId)
        assertEquals("high", updated.variant)
    }

    private class RecordingSessionApi(
        private val session: SessionUi,
    ) : SessionCommandApi {
        val calls = mutableListOf<String>()

        override fun switchAgent(sessionId: String, agentId: String) {
            calls += "switchAgent:$sessionId:$agentId"
        }

        override fun switchModel(
            sessionId: String,
            providerId: String,
            modelId: String,
            variant: String?,
        ) {
            calls += "switchModel:$sessionId:$providerId:$modelId:$variant"
        }

        override fun session(sessionId: String): SessionUi {
            calls += "session:$sessionId"
            return session
        }
    }
}
