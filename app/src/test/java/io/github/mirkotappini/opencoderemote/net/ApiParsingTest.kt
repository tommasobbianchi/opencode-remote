package io.github.mirkotappini.opencoderemote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test JVM sul parsing/normalizzazione con payload inline minimi.
 * Nessuna rete, nessuna credenziale, nessun dato reale.
 */
class ApiParsingTest {

    @Test
    fun `sessione normalizzata con titolo e fallback id`() {
        val body = """
            {"data":[
              {"id":"ses_1","agent":"orchestrator",
               "model":{"id":"deepseek-flash","providerID":"deepseek"},
               "cost":0.000861444,"outcome":"succeeded",
               "time":{"created":1000,"updated":2000},
               "title":"Greeting","campoIgnoto":true}
            ],"cursor":{"previous":null,"next":"tok"}}
        """.trimIndent()

        val page = ApiParsing.parseSessions(body)

        assertEquals(1, page.sessions.size)
        val session = page.sessions.single()
        assertEquals("ses_1", session.id)
        assertEquals("Greeting", session.title)
        assertEquals("orchestrator", session.agent)
        assertEquals("deepseek-flash", session.modelId)
        assertEquals(2000L, session.updatedAt)
        assertEquals("succeeded", session.outcome)
        assertEquals("tok", page.next)
    }

    @Test
    fun `titolo vuoto ricade su id`() {
        val page = ApiParsing.parseSessions("""{"data":[{"id":"ses_2","title":""}]}""")
        assertEquals("ses_2", page.sessions.single().title)
    }

    @Test
    fun `active vuoto significa nessuna sessione`() {
        assertNull(ApiParsing.parseActiveSession("""{"data":{}}"""))
        assertEquals("ses_9", ApiParsing.parseActiveSession("""{"data":{"id":"ses_9"}}"""))
    }

    @Test
    fun `messaggi normalizzati e type ignoto non fa crashare`() {
        // Il server risponde in ordine desc: il parser deve restituire cronologico.
        val body = """
            {"data":[
              {"id":"msg_a","type":"assistant","time":{"created":3000},
               "content":[
                 {"type":"reasoning","text":"riga uno\nriga due","native":{"x":1}},
                 {"type":"text","text":"ciao "},
                 {"type":"text","text":"mondo"},
                 {"type":"tool","id":"t1","name":"bash","executed":true,
                  "state":{"status":"completed","output":"ok"}},
                 {"type":"parte-ignota","payload":{"a":1}},
                 {"nocontent":true}
               ]},
              {"id":"msg_b","type":"tipo-sconosciuto","time":{"created":2000},"outcome":"ok"},
              {"id":"msg_c","type":"user","time":{"created":1000},"text":"domanda"}
            ],"cursor":{"previous":"prev-tok","next":null}}
        """.trimIndent()

        val page = ApiParsing.parseMessages(body)

        assertEquals(3, page.messages.size)
        assertEquals("prev-tok", page.previous)

        // Ordine cronologico: user → meta → assistant
        assertEquals(Role.User, page.messages[0].role)
        assertEquals("domanda", page.messages[0].text)

        assertEquals(Role.Meta, page.messages[1].role)
        assertTrue(page.messages[1].metaLabel!!.contains("tipo-sconosciuto"))

        val assistant = page.messages[2]
        assertEquals(Role.Assistant, assistant.role)
        assertEquals("ciao \nmondo", assistant.text)
        assertEquals("riga uno\nriga due", assistant.reasoning)
        assertEquals(1, assistant.tools.size)
        assertEquals("bash", assistant.tools.single().name)
        assertNotNull(assistant.tools.single().state)
        assertTrue(assistant.tools.single().state!!.contains("completed"))
        assertEquals(3000L, assistant.createdAt)
    }

    @Test
    fun `tool state lungo viene troncato e tool ignorato senza nome non crasha`() {
        val body = """
            {"data":[{"id":"msg_x","type":"assistant","content":[
              {"type":"tool","name":"","state":{"blob":"${"x".repeat(400)}"}},
              {"type":"tool","state":null}
            ]}]}
        """.trimIndent()

        val page = ApiParsing.parseMessages(body)
        val tools = page.messages.single().tools
        assertEquals(2, tools.size)
        assertEquals("tool", tools[0].name)
        assertTrue(tools[0].state!!.endsWith("…"))
        assertTrue(tools[0].state!!.length <= 161)
        assertNull(tools[1].state)
    }

    @Test
    fun `messaggio senza id viene scartato`() {
        val page = ApiParsing.parseMessages(
            """{"data":[{"type":"user","text":"senza id"},{"id":"ok","type":"user","text":"a"}]}""",
        )
        assertEquals(1, page.messages.size)
        assertEquals("ok", page.messages.single().id)
        assertFalse(page.messages.single().text.isBlank())
    }
}
