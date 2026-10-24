package io.github.mirkotappini.opencoderemote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressione sul crash `Key "model-openai/gpt-4.1" was already used`:
 * `modelID` is the family and repeats (gpt-4.1 / gpt-4.1-fast); the concrete
 * reference is `id` (the one `Model.Ref` accepts).
 */
class ModelRefTest {

    private val payload = """
        {"data":[
          {"id":"gpt-4.1","modelID":"gpt-4.1","providerID":"openai","name":"GPT-4.1"},
          {"id":"gpt-4.1-fast","modelID":"gpt-4.1","providerID":"openai","name":"GPT-4.1 Fast"},
          {"id":"gpt-4.1","modelID":"gpt-4.1","providerID":"openai","name":"GPT-4.1 duplicato esatto"},
          {"id":"deepseek-flash","modelID":"deepseek-flash","providerID":"deepseek","name":"DeepSeek Flash"}
        ]}
    """.trimIndent()

    @Test
    fun `entry keys are unique even if modelID repeats`() {
        val models = CatalogParsing.parseModels(payload)
        val keys = models.map { it.pickerKey() }

        println("MODELS=${models.map { it.id }} KEYS=$keys")
        assertEquals("il duplicato esatto va scartato", 3, models.size)
        assertEquals("nessuna chiave ripetuta", keys.size, keys.toSet().size)
        assertTrue(keys.contains("openai/gpt-4.1"))
        assertTrue(keys.contains("openai/gpt-4.1-fast"))
    }

    @Test
    fun `selection uses id and not the modelID family`() {
        val models = CatalogParsing.parseModels(payload)
        val fast = models.single { it.id == "gpt-4.1-fast" }
        val base = models.single { it.id == "gpt-4.1" }

        // session set to the fast model: only the concrete entry must be selected
        assertTrue(fast.referencesSession("gpt-4.1-fast", "openai"))
        assertFalse(base.referencesSession("gpt-4.1-fast", "openai"))
        // same family but different id must not match
        assertFalse(fast.referencesSession("gpt-4.1", "openai"))
        // different provider must not match
        assertFalse(fast.referencesSession("gpt-4.1-fast", "azure"))
        // provider absent from the session: accept the match on id
        assertTrue(fast.referencesSession("gpt-4.1-fast", null))
        // session without model
        assertFalse(fast.referencesSession(null, null))
    }
}
