package io.github.mirkotappini.opencoderemote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of attachments in saved messages (inline and by URI). */
class MessageFilesTest {

    @Test
    fun `files inline popolano dataBase64 e ignorano i campi ignoti`() {
        val body = """
            {"data":[{"id":"msg_u","type":"user","time":{"created":1000},
              "text":"guarda",
              "files":[
                {"data":"QUJD","mime":"image/png","source":{"type":"inline"},
                 "name":"prova.png","description":"screenshot","campoIgnoto":{"x":1}},
                {"mime":"application/pdf","name":"doc.pdf",
                 "source":{"type":"uri","uri":"file:///tmp/doc.pdf"}},
                {"mime":"text/plain"}
              ]}]}
        """.trimIndent()

        val message = ApiParsing.parseMessages(body).messages.single()

        assertEquals(Role.User, message.role)
        assertEquals(3, message.files.size)

        val inline = message.files[0]
        assertEquals("prova.png", inline.name)
        assertEquals("image/png", inline.mime)
        assertEquals("screenshot", inline.description)
        assertEquals("QUJD", inline.dataBase64)
        assertNull(inline.uri)

        val byUri = message.files[1]
        assertEquals("doc.pdf", byUri.name)
        assertNull(byUri.dataBase64)
        assertEquals("file:///tmp/doc.pdf", byUri.uri)

        // File without date or uri: remains valid, the UI will only display the
        // name.
        val bare = message.files[2]
        assertEquals("text/plain", bare.mime)
        assertNull(bare.dataBase64)
        assertNull(bare.uri)
    }

    @Test
    fun `messaggio senza files resta valido`() {
        val body = """
            {"data":[{"id":"msg_old","type":"user","time":{"created":1},"text":"prima di m8"}]}
        """.trimIndent()

        val message = ApiParsing.parseMessages(body).messages.single()

        assertTrue(message.files.isEmpty())
        assertEquals("prima di m8", message.text)
    }
}
