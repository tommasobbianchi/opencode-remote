package io.github.mirkotappini.opencoderemote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressione sul crash `Key "model-openai/gpt-4.1" was already used`:
 * `modelID` è la famiglia e si ripete (gpt-4.1 / gpt-4.1-fast), il riferimento
 * concreto è `id` (quello che accetta `Model.Ref`).
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
    fun `le chiavi delle voci sono univoche anche se modelID si ripete`() {
        val models = CatalogParsing.parseModels(payload)
        val keys = models.map { it.pickerKey() }

        println("MODELS=${models.map { it.id }} KEYS=$keys")
        assertEquals("il duplicato esatto va scartato", 3, models.size)
        assertEquals("nessuna chiave ripetuta", keys.size, keys.toSet().size)
        assertTrue(keys.contains("openai/gpt-4.1"))
        assertTrue(keys.contains("openai/gpt-4.1-fast"))
    }

    @Test
    fun `la selezione usa id e non la famiglia modelID`() {
        val models = CatalogParsing.parseModels(payload)
        val fast = models.single { it.id == "gpt-4.1-fast" }
        val base = models.single { it.id == "gpt-4.1" }

        // sessione impostata sul modello veloce: solo la voce concreto deve risultare selezionata
        assertTrue(fast.referencesSession("gpt-4.1-fast", "openai"))
        assertFalse(base.referencesSession("gpt-4.1-fast", "openai"))
        // stessa famiglia ma id diverso non deve combaciare
        assertFalse(fast.referencesSession("gpt-4.1", "openai"))
        // provider diverso non deve combaciare
        assertFalse(fast.referencesSession("gpt-4.1-fast", "azure"))
        // provider assente nella sessione: accetta il match sull'id
        assertTrue(fast.referencesSession("gpt-4.1-fast", null))
        // sessione senza modello
        assertFalse(fast.referencesSession(null, null))
    }
}
