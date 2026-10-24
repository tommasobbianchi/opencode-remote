package io.github.mirkotappini.opencoderemote

import io.github.mirkotappini.opencoderemote.net.SessionUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test JVM offline della pila padre → figlio (`SessionNav`). */
class SessionNavTest {

    private fun session(id: String, title: String = id) = SessionUi(id = id, title = title)

    private fun frame(id: String, readOnly: Boolean = false) =
        SessionFrame(session(id), readOnly)

    @Test
    fun `apertura dalla lista parte con pila vuota e sessione scrivibile`() {
        val plan = SessionNav.planOpen(
            stack = listOf(frame("stale")),
            current = null,
            nextSession = session("root"),
        )

        assertTrue(plan.stack.isEmpty())
        assertFalse(plan.readOnly)
        assertFalse(SessionNav.canGoBackToParent(plan.stack))
    }

    @Test
    fun `aprire un figlio impila il padre e attiva sola lettura`() {
        val plan = SessionNav.planOpen(
            stack = emptyList(),
            current = frame("parent"),
            nextSession = session("child"),
        )

        assertEquals(listOf("parent"), plan.stack.map { it.session.id })
        assertFalse(plan.stack.single().readOnly)
        assertTrue(plan.readOnly)
        assertTrue(SessionNav.canGoBackToParent(plan.stack))
    }

    @Test
    fun `pila annidata conserva readOnly dei livelli`() {
        val first = SessionNav.planOpen(emptyList(), frame("root"), session("child"))
        val second = SessionNav.planOpen(
            stack = first.stack,
            current = frame("child", readOnly = first.readOnly),
            nextSession = session("grandchild"),
        )

        assertEquals(listOf("root", "child"), second.stack.map { it.session.id })
        assertEquals(listOf(false, true), second.stack.map { it.readOnly })
        assertTrue(second.readOnly)
    }

    @Test
    fun `retry della stessa sessione non impila duplicati`() {
        val stack = listOf(frame("parent"))
        val plan = SessionNav.planOpen(
            stack = stack,
            current = frame("child", readOnly = true),
            nextSession = session("child"),
        )

        assertEquals(stack, plan.stack)
        assertTrue(plan.readOnly)
    }

    @Test
    fun `id gia in pila non crea cicli e chiude i livelli sopra`() {
        val stack = listOf(frame("root"), frame("child", readOnly = true))
        val plan = SessionNav.planOpen(
            stack = stack,
            current = frame("grandchild", readOnly = true),
            nextSession = session("root"),
        )

        assertTrue(plan.stack.isEmpty())
        assertFalse(plan.readOnly)

        val nearest = SessionNav.planOpen(
            stack = stack,
            current = frame("grandchild", readOnly = true),
            nextSession = session("child"),
        )
        assertEquals(listOf("root"), nearest.stack.map { it.session.id })
        assertTrue(nearest.readOnly)
    }

    @Test
    fun `back riapre l'ultimo padre e accorcia la pila`() {
        val stack = listOf(frame("root"), frame("child", readOnly = true))
        val popped = SessionNav.pop(stack)

        assertEquals("child", popped.frame?.session?.id)
        assertTrue(popped.frame?.readOnly == true)
        assertEquals(listOf("root"), popped.stack.map { it.session.id })
        assertTrue(SessionNav.canGoBackToParent(popped.stack))
    }

    @Test
    fun `back con pila vuota segnala il ritorno alla lista`() {
        val popped = SessionNav.pop(emptyList())

        assertNull(popped.frame)
        assertTrue(popped.stack.isEmpty())
        assertFalse(SessionNav.canGoBackToParent(popped.stack))
    }

    @Test
    fun `backToList e disconnect azzerano la pila`() {
        val stack = listOf(frame("root"), frame("child", readOnly = true))

        assertEquals(emptyList<SessionFrame>(), SessionNav.cleared())
        assertTrue(stack.isNotEmpty())
        assertFalse(SessionNav.canGoBackToParent(SessionNav.cleared()))
    }
}
