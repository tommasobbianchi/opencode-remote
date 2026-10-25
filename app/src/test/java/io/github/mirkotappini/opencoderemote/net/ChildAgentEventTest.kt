package io.github.mirkotappini.opencoderemote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `session.created` / `session.updated` for a child session: the payload carries
 * the **child's** `sessionID` and the mother's `parentID`. The event must report
 * the mother as the reference session, so the open chat filter works without
 * special cases.
 */
class ChildAgentEventTest {

    private val created = """
        {"id":"evt_1","created":1792876240985,"type":"session.created",
         "location":{"directory":"/tmp"},
         "data":{"sessionID":"ses_child","parentID":"ses_parent","agent":"designer",
                 "title":"prova subagent","projectID":"p","slug":"lucky-cabin","version":"2.1.4"},
         "durable":{"aggregateID":"ses_child","seq":0,"version":1}}
    """.trimIndent()

    @Test
    fun `session created di un figlio diventa ChildAgentStarted sulla madre`() {
        val event = EventParsing.parse(created)?.event
        assertTrue("atteso ChildAgentStarted, trovato $event", event is ServerEvent.ChildAgentStarted)
        val child = event as ServerEvent.ChildAgentStarted
        assertEquals("ses_parent", child.sessionId)
        assertEquals("ses_child", child.childId)
        assertEquals("designer", child.agent)
        assertEquals("prova subagent", child.title)
    }

    @Test
    fun `una sessione di primo livello non e' un subagente`() {
        val root = created.replace("\"parentID\":\"ses_parent\",", "")
        val event = EventParsing.parse(root)?.event
        assertTrue(event is ServerEvent.Unknown)
    }

    @Test
    fun `senza agente non si annuncia nulla`() {
        val noAgent = created.replace("\"agent\":\"designer\",", "")
        assertTrue(EventParsing.parse(noAgent)?.event is ServerEvent.Unknown)
    }
}
