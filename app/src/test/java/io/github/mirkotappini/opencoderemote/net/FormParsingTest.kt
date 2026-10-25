package io.github.mirkotappini.opencoderemote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM test for form parsing (M9): realistic payload, mixed types, `hidden`/`when`
 * fields ignored, and broken payload never crashes.
 */
class FormParsingTest {

    private val detail = """
        {
          "id": "frm_1",
          "sessionID": "ses_1",
          "title": "Che facciamo?",
          "metadata": {"origine": "test"},
          "state": {"status": "pending"},
          "fields": [
            {
              "key": "scelta",
              "type": "string",
              "title": "Scrivi la scelta",
              "description": "testo libero",
              "required": true,
              "hidden": false
            },
            {
              "key": "attivo",
              "type": "boolean",
              "title": "Attivo?",
              "required": false
            },
            {
              "key": "quanti",
              "type": "integer",
              "title": "Quanti",
              "minimum": 1,
              "maximum": 10,
              "required": true
            },
            {
              "key": "prezzo",
              "type": "number",
              "title": "Prezzo",
              "minimum": 0.5
            },
            {
              "key": "servizi",
              "type": "multiselect",
              "title": "Servizi",
              "options": [
                {"value": "a", "label": "A", "description": "primo"},
                {"value": "b", "label": "B"}
              ]
            },
            {
              "key": "nascosto",
              "type": "string",
              "title": "Non mostrare",
              "hidden": true,
              "required": true
            },
            {
              "key": "condizionale",
              "type": "string",
              "title": "Solo se...",
              "when": {"field": "attivo", "equals": true}
            },
            {
              "key": "sito",
              "type": "external",
              "title": "Sul server",
              "url": "https://example.test/form"
            },
            {
              "key": "ignoto",
              "type": "date",
              "title": "Tipo non previsto"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `form detail normalizzato con tipi misti`() {
        val form = FormParsing.parseDetailBody(detail)!!

        assertEquals("frm_1", form.id)
        assertEquals("ses_1", form.sessionId)
        assertEquals("Che facciamo?", form.title)

        val keys = form.fields.map { it.key }
        assertEquals(listOf("scelta", "attivo", "quanti", "prezzo", "servizi", "condizionale", "sito"), keys)

        val scelta = form.fields.first { it.key == "scelta" }
        assertEquals("string", scelta.type)
        assertTrue(scelta.required)
        assertEquals("testo libero", scelta.description)

        assertEquals("boolean", form.fields.first { it.key == "attivo" }.type)

        val quanti = form.fields.first { it.key == "quanti" }
        assertEquals("integer", quanti.type)
        assertEquals(1.0, quanti.minimum)
        assertEquals(10.0, quanti.maximum)

        val prezzo = form.fields.first { it.key == "prezzo" }
        assertEquals("number", prezzo.type)
        assertEquals(0.5, prezzo.minimum)

        val servizi = form.fields.first { it.key == "servizi" }
        assertEquals("multiselect", servizi.type)
        assertEquals(listOf("a", "b"), servizi.options.map { it.value })
        assertEquals(listOf("A", "B"), servizi.options.map { it.label })
        assertEquals("primo", servizi.options.first().description)

        val sito = form.fields.first { it.key == "sito" }
        assertEquals("external", sito.type)
        assertEquals("https://example.test/form", sito.externalUrl)
    }

    @Test
    fun `campi hidden e when esclusi o ignorati, tipo ignoto scartato`() {
        val form = FormParsing.parseDetailBody(detail)!!

        assertTrue(form.fields.none { it.key == "nascosto" })
        assertTrue(form.fields.none { it.key == "ignoto" })
        // `when` does not block the field: in v1, conditions are ignored.
        assertTrue(form.fields.any { it.key == "condizionale" })
    }

    @Test
    fun `evento form created porta il dettaglio`() {
        val raw = """
            {"id":"evt_1","type":"form.created","data":$detail}
        """.trimIndent()

        val event = EventParsing.parseEvent(raw)

        assertTrue(event is ServerEvent.FormCreated)
        val created = event as ServerEvent.FormCreated
        assertEquals("ses_1", created.sessionId)
        assertEquals("frm_1", created.form.id)
        assertEquals(7, created.form.fields.size)
    }

    @Test
    fun `form created con sessionID solo nell envelope`() {
        val raw = """
            {"type":"form.created","data":{
              "sessionID":"ses_9",
              "form":{"id":"frm_9","title":"Senza descrizione","fields":[]}
            }}
        """.trimIndent()

        val created = EventParsing.parseEvent(raw) as ServerEvent.FormCreated
        assertEquals("ses_9", created.sessionId)
        assertEquals("frm_9", created.form.id)
        assertEquals("Senza descrizione", created.form.title)
        assertEquals(emptyList<FormFieldUi>(), created.form.fields)
    }

    @Test
    fun `payload inatteso degrada a Unknown senza crash`() {
        val casi = listOf(
            """{"type":"form.created","data":{"boh":1}}""",
            """{"type":"form.created","data":"testo"}""",
            """{"type":"form.created","data":[]}""",
            """{"type":"form.created"}""",
        )

        casi.forEach { raw ->
            val event = EventParsing.parseEvent(raw)
            assertTrue("atteso Unknown per: $raw", event is ServerEvent.Unknown)
        }
    }

    @Test
    fun `json malformato non produce eventi`() {
        assertNull(EventParsing.parse("""{"type":"form.created","data":{"""))
    }

    @Test
    fun `form replied e cancelled portano il form id`() {
        val replied = EventParsing.parseEvent(
            """{"type":"form.replied","data":{"sessionID":"ses_1","formID":"frm_1"}}""",
        )
        assertTrue(replied is ServerEvent.FormReplied)
        assertEquals("frm_1", (replied as ServerEvent.FormReplied).formId)
        assertEquals("ses_1", replied.sessionId)

        val cancelled = EventParsing.parseEvent(
            """{"type":"form.cancelled","data":{"sessionID":"ses_1","id":"frm_2"}}""",
        )
        assertTrue(cancelled is ServerEvent.FormCancelled)
        assertEquals("frm_2", (cancelled as ServerEvent.FormCancelled).formId)
    }

    @Test
    fun `replied senza id degrada a Unknown`() {
        assertTrue(
            EventParsing.parseEvent("""{"type":"form.replied","data":{"sessionID":"ses_1"}}""")
                is ServerEvent.Unknown,
        )
    }

    @Test
    fun `lista form info senza campi non inventa un form vuoto`() {
        val body = """
            {"location":{"directory":"/tmp"},"data":[
              {"id":"frm_1","sessionID":"ses_1","title":"Titolo","state":{"status":"pending"}}
            ]}
        """.trimIndent()

        assertTrue(FormParsing.parseForms(body).isEmpty())

        val infos = FormParsing.parseInfos(body)
        assertEquals(1, infos.size)
        assertEquals("frm_1", infos.single().id)
        assertEquals("ses_1", infos.single().sessionID)
    }

    @Test
    fun `string con options conserva le scelte (tool question)`() {
        // Payload as sent by the "question" tool: string type + options.
        val payload = """
            {
              "id": "frm_q",
              "sessionID": "ses_1",
              "title": "Questions",
              "fields": [
                {
                  "key": "q0",
                  "title": "Colore preferito",
                  "type": "string",
                  "required": true,
                  "options": [
                    {"value": "Rosso", "label": "Rosso"},
                    {"value": "Blu", "label": "Blu", "description": "più freddo"}
                  ]
                }
              ]
            }
        """
        val form = FormParsing.parseDetailBody(payload) ?: error("form non parsato")
        val colore = form.fields.first { it.key == "q0" }
        assertEquals("string", colore.type)
        assertEquals(listOf("Rosso", "Blu"), colore.options.map { it.value })
        assertEquals("più freddo", colore.options[1].description)
    }
}
