package io.github.mirkotappini.opencoderemote.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serialization of the prompt body with `files` and compatibility without
 * attachments.
 */
class PromptFilesTest {

    // Same client configuration: nulls and defaults are not written.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun `files serializza uri nome e descrizione`() {
        val body = PromptBody(
            text = "guarda",
            files = listOf(
                PromptFile(
                    uri = "data:image/png;base64,QUJD",
                    name = "prova.png",
                    description = "screenshot",
                ),
            ),
        )

        val encoded = json.parseToJsonElement(
            json.encodeToString(PromptBody.serializer(), body),
        ).jsonObject
        assertEquals("guarda", encoded["text"]?.jsonPrimitive?.content)

        val files = encoded["files"]?.jsonArray
        assertEquals(1, files?.size)
        val file = files?.single()?.jsonObject
        assertEquals("data:image/png;base64,QUJD", file?.get("uri")?.jsonPrimitive?.content)
        assertEquals("prova.png", file?.get("name")?.jsonPrimitive?.content)
        assertEquals("screenshot", file?.get("description")?.jsonPrimitive?.content)
    }

    @Test
    fun `senza allegati il campo files resta assente`() {
        val encoded = json.parseToJsonElement(
            json.encodeToString(
                PromptBody.serializer(),
                PromptBody(text = "solo testo", files = null),
            ),
        ).jsonObject

        assertFalse(encoded.containsKey("files"))
        assertEquals("solo testo", encoded["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `prompt file senza campi opzionali non li scrive`() {
        val encoded = json.parseToJsonElement(
            json.encodeToString(
                PromptFile.serializer(),
                PromptFile(uri = "data:text/plain;base64,QUJD"),
            ),
        ).jsonObject

        assertEquals("data:text/plain;base64,QUJD", encoded["uri"]?.jsonPrimitive?.content)
        assertFalse(encoded.containsKey("name"))
        assertFalse(encoded.containsKey("description"))
        assertTrue(encoded.size == 1)
    }
}
