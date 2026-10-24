package io.github.mirkotappini.opencoderemote.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test JVM della risposta a un form: i `Form.Value` devono arrivare al server
 * nei tipi JSON giusti (stringa, numero, booleano, array di stringhe).
 */
class FormsReplyTest {

    private fun answer(raw: String) =
        Json.parseToJsonElement(raw).jsonObject.getValue("answer").jsonObject

    @Test
    fun `tipi misti serializzati correttamente`() {
        val raw = FormReply.bodyJson(
            mapOf(
                "nome" to FormValue.Text("mario"),
                "eta" to FormValue.Number(42.0),
                "ok" to FormValue.Bool(true),
                "tag" to FormValue.Multi(listOf("a", "b")),
            ),
        )

        val answer = answer(raw)

        val nome = answer.getValue("nome").jsonPrimitive
        assertTrue(nome.isString)
        assertEquals("mario", nome.content)

        val eta = answer.getValue("eta").jsonPrimitive
        assertFalse(eta.isString)
        assertEquals("42", eta.content)

        val ok = answer.getValue("ok").jsonPrimitive
        assertFalse(ok.isString)
        assertTrue(ok.boolean)

        val tag = answer.getValue("tag") as JsonArray
        assertEquals(listOf("a", "b"), tag.map { (it as JsonPrimitive).content })
    }

    @Test
    fun `numero decimale resta un numero`() {
        val answer = answer(FormReply.bodyJson(mapOf("prezzo" to FormValue.Number(3.5))))
        val prezzo = answer.getValue("prezzo").jsonPrimitive
        assertFalse(prezzo.isString)
        assertEquals("3.5", prezzo.content)
    }

    @Test
    fun `stringa numerica resta una stringa`() {
        val answer = answer(FormReply.bodyJson(mapOf("cap" to FormValue.Text("00100"))))
        val cap = answer.getValue("cap").jsonPrimitive
        assertTrue(cap.isString)
        assertEquals("00100", cap.content)
    }

    @Test
    fun `risposta vuota produce un answer vuoto`() {
        assertEquals("""{"answer":{}}""", FormReply.bodyJson(emptyMap()))
    }

    @Test
    fun `booleano false non viene perso`() {
        val answer = answer(FormReply.bodyJson(mapOf("attivo" to FormValue.Bool(false))))
        assertFalse(answer.getValue("attivo").jsonPrimitive.boolean)
    }
}
