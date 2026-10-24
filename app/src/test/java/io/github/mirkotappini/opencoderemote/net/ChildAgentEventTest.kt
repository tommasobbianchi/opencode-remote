package io.github.mirkotappini.opencoderemote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `session.created` / `session.updated` di una sessione figlia: il payload
 * porta `sessionID` del **figlio** e `parentID` della madre. L'evento deve
 * riportare la madre come sessione di riferimento, così il filtro della chat
 * aperta funziona senza casi speciali.
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
