package io.github.mirkotappini.opencoderemote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The submit button unlocks only with at least one letter or digit: spaces,
 * punctuation, and symbols alone are not enough.
 */
class SendableTextTest {

    @Test
    fun `testo con lettere o cifre e' inviabile`() {
        assertTrue(hasSendableText("ciao"))
        assertTrue(hasSendableText("  42  "))
        assertTrue(hasSendableText("/close"))
        assertTrue(hasSendableText("perché"))
        assertTrue(hasSendableText("日本語"))
    }

    @Test
    fun `invio e spaziature sono ignorati a campo vuoto`() {
        assertTrue("una lettera passa", acceptDraftChange("", "a"))
        assertFalse("solo a capo su campo vuoto", acceptDraftChange("", "\n"))
        assertFalse("solo spazi su campo vuoto", acceptDraftChange("", "   "))
    }

    @Test
    fun `con del testo dentro il campo il ritorno a capo passa`() {
        assertTrue(acceptDraftChange("ciao", "ciao\n"))
        assertTrue(acceptDraftChange("ciao\n", "ciao\n\n"))
        assertTrue("svuotare il campo e' sempre lecito", acceptDraftChange("ciao", ""))
    }

    @Test
    fun `solo spazi o punteggiatura non sono inviabili`() {
        assertFalse(hasSendableText(""))
        assertFalse(hasSendableText("   "))
        assertFalse(hasSendableText("/"))
        assertFalse(hasSendableText("…"))
        assertFalse(hasSendableText("!!!"))
        assertFalse(hasSendableText(" - "))
        assertFalse(hasSendableText("🙂"))
    }
}
