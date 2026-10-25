package io.github.mirkotappini.opencoderemote.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules: mime, thresholds, compression steps, and readable weight. */
class AttachmentRulesTest {

    @Test
    fun `mime normalizzato senza parametri e minuscolo`() {
        assertEquals("image/png", AttachmentRules.normalizeMime(" Image/PNG ; charset=binary"))
        assertEquals("", AttachmentRules.normalizeMime(null))
    }

    @Test
    fun `mime verso estensione`() {
        assertEquals("jpg", AttachmentRules.extensionFor("image/jpeg"))
        assertEquals("png", AttachmentRules.extensionFor("image/png"))
        assertEquals("pdf", AttachmentRules.extensionFor("application/pdf"))
        assertEquals("json", AttachmentRules.extensionFor("application/json"))
        assertEquals("txt", AttachmentRules.extensionFor("text/plain"))
        // Unknown MIME: try the file extension, then fall back.
        assertEquals("log", AttachmentRules.extensionFor("application/x-ignoto", "server.log"))
        assertEquals("bin", AttachmentRules.extensionFor("application/x-ignoto", null))
    }

    @Test
    fun `data uri con mime normalizzato`() {
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            AttachmentRules.dataUri("IMAGE/JPEG; charset=binary", "QUJD"),
        )
    }

    @Test
    fun `mime accettati dal server`() {
        assertTrue(AttachmentRules.isAcceptedMime("image/png"))
        assertTrue(AttachmentRules.isAcceptedMime("text/markdown"))
        assertTrue(AttachmentRules.isAcceptedMime("application/pdf"))
        assertTrue(AttachmentRules.isAcceptedMime("application/json"))
        assertTrue(AttachmentRules.isAcceptedMime("application/octet-stream"))
        assertFalse(AttachmentRules.isAcceptedMime("application/zip"))
        assertFalse(AttachmentRules.isAcceptedMime(null))
    }

    @Test
    fun `zip ricade su octet-stream e il nome resta coerente`() {
        val mime = AttachmentRules.outboundMime("application/zip", "archivio.zip")
        assertEquals("application/octet-stream", mime)
        assertEquals("archivio.zip", AttachmentRules.fileNameFor(mime, "archivio.zip"))
    }

    @Test
    fun `nome ripulito da percorsi e caratteri non validi`() {
        assertEquals(
            "prova.pdf",
            AttachmentRules.fileNameFor("application/pdf", "../../prova.pdf"),
        )
        // Spaces are legitimate in a file name: they are not replaced.
        assertEquals(
            "senza estensione.pdf",
            AttachmentRules.fileNameFor("application/pdf", "senza estensione"),
        )
        assertEquals("attachment.pdf", AttachmentRules.fileNameFor("application/pdf", null))
    }

    @Test
    fun `soglie in ingresso e inline`() {
        assertTrue(AttachmentRules.isWithinInputLimit(AttachmentRules.MAX_INPUT_BYTES))
        assertFalse(AttachmentRules.isWithinInputLimit(AttachmentRules.MAX_INPUT_BYTES + 1))
        assertTrue(AttachmentRules.isWithinInlineLimit(AttachmentRules.MAX_INLINE_BYTES))
        assertFalse(AttachmentRules.isWithinInlineLimit(AttachmentRules.MAX_INLINE_BYTES + 1))
        assertEquals(50L * 1024 * 1024, AttachmentRules.MAX_INPUT_BYTES)
        assertEquals(4L * 1024 * 1024, AttachmentRules.MAX_INLINE_BYTES)
    }

    @Test
    fun `passi di ridimensionamento in ordine poi errore`() {
        val first = AttachmentRules.resizeStep(0)
        assertEquals(1600, first?.maxSidePx)
        assertEquals(80, first?.quality)

        val second = AttachmentRules.resizeStep(1)
        assertEquals(1280, second?.maxSidePx)
        assertEquals(70, second?.quality)

        assertNull(AttachmentRules.resizeStep(2))
    }

    @Test
    fun `peso leggibile in inglese`() {
        assertEquals("512 B", AttachmentRules.formatSize(512))
        assertEquals("840 kB", AttachmentRules.formatSize(840_000))
        assertEquals("1.2 MB", AttachmentRules.formatSize(1_200_000))
        assertEquals("4.2 MB", AttachmentRules.formatSize(AttachmentRules.MAX_INLINE_BYTES))
        assertEquals("0 B", AttachmentRules.formatSize(-5))
    }

    @Test
    fun `lunghezza base64`() {
        assertEquals(4L, AttachmentRules.base64Length(3))
        assertEquals(8L, AttachmentRules.base64Length(4))
        assertEquals(0L, AttachmentRules.base64Length(0))
    }
}
