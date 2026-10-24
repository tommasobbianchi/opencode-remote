package io.github.mirkotappini.opencoderemote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test JVM dei comandi slash (M9): parsing di `GET /api/command` e scelta
 * `/comando argomenti` → nome + resto del testo.
 */
class CommandsTest {

    private val body = """
        {
          "location": {"directory": "/tmp"},
          "data": [
            {"name": "init", "description": "Inizializza il progetto", "extra": true},
            {"name": "review", "description": "Rivedi le modifiche"},
            {"name": "deepwork"},
            {"name": "reflect", "description": ""},
            {"name": "implement", "description": "Implementa"},
            {"name": "", "description": "senza nome"},
            {"description": "senza nome davvero"},
            {"name": "init", "description": "duplicato"}
          ]
        }
    """.trimIndent()

    private val commands = CommandParsing.parseCommands(body)

    @Test
    fun `catalogo letto dal server senza elenco fisso`() {
        assertEquals(listOf("init", "review", "deepwork", "reflect", "implement"), commands.map { it.name })
        assertEquals("Inizializza il progetto", commands.first().description)
        assertEquals(null, commands.first { it.name == "deepwork" }.description)
        // Descrizione vuota normalizzata a null.
        assertEquals(null, commands.first { it.name == "reflect" }.description)
    }

    @Test
    fun `risposta vuota o rotta non fa crashare`() {
        assertTrue(CommandParsing.parseCommands("""{"data":[]}""").isEmpty())
        assertTrue(CommandParsing.parseCommands("""{"location":{}}""").isEmpty())
        assertTrue(CommandParsing.parseCommands("non-json").isEmpty())
    }

    @Test
    fun `comando con argomenti`() {
        val call = SlashCommands.parse("/review main", commands)!!
        assertEquals("review", call.name)
        assertEquals("main", call.text)
    }

    @Test
    fun `comando senza argomenti`() {
        val call = SlashCommands.parse("/init", commands)!!
        assertEquals("init", call.name)
        assertEquals("", call.text)
    }

    @Test
    fun `argomenti su piu righe`() {
        val call = SlashCommands.parse("/implement riga uno\nriga due", commands)!!
        assertEquals("implement", call.name)
        assertEquals("riga uno\nriga due", call.text)
    }

    @Test
    fun `spazi iniziali e finali non cambiano il comando`() {
        val call = SlashCommands.parse("  /review   main  ", commands)!!
        assertEquals("review", call.name)
        assertEquals("main", call.text)
    }

    @Test
    fun `comando sconosciuto non viene riconosciuto`() {
        assertNull(SlashCommands.parse("/sconosciuto main", commands))
        assertNull(SlashCommands.parse("/init-tutto", commands))
    }

    @Test
    fun `testo normale o solo slash restano prompt`() {
        assertNull(SlashCommands.parse("ciao /review", commands))
        assertNull(SlashCommands.parse("/", commands))
        assertNull(SlashCommands.parse("", commands))
        assertNull(SlashCommands.parse("/" + " ", commands))
    }
}
