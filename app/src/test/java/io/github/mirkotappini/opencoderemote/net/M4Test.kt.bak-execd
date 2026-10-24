package io.github.mirkotappini.opencoderemote.net

import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test JVM di M4: parsing Permission.Request, mapping delle decisioni,
 * distinction of 404s, notification dedupe, stable id, throttle and backoff.
 * All offline; AndroidKeyStore is not tested (it does not exist in the JVM).
 */
class M4Test {

    // 1. Parsing reale (con `source.type = "tool"`) e lista vuota -------------

    @Test
    fun `permission request reale con source tool`() {
        val body = """
            {"data":[{
              "id":"per_abc123",
              "sessionID":"ses_1",
              "action":"read",
              "resources":["perm-probe.env"],
              "save":["*"],
              "metadata":{},
              "source":{"type":"tool","messageID":"msg_1","id":"call_1"},
              "message":"Serve leggere il file"
            }]}
        """.trimIndent()

        val approvals = ApprovalParsing.parsePermissions(body)

        assertEquals(1, approvals.size)
        val approval = approvals.single()
        assertEquals("per_abc123", approval.id)
        assertEquals("ses_1", approval.sessionId)
        assertEquals("read", approval.action)
        assertEquals(listOf("perm-probe.env"), approval.resources)
        assertEquals(listOf("*"), approval.save)
        assertEquals("tool", approval.sourceLabel)
        assertEquals("Serve leggere il file", approval.message)
    }

    @Test
    fun `empty permission list and missing optional fields`() {
        assertTrue(ApprovalParsing.parsePermissions("""{"data":[]}""").isEmpty())

        val body = """
            {"data":[
              {"id":"per_1","sessionID":"ses_1","action":"bash","resources":[]},
              {"id":"per_2","sessionID":"ses_1","action":"edit"}
            ]}
        """.trimIndent()
        val approvals = ApprovalParsing.parsePermissions(body)
        assertEquals(2, approvals.size)
        assertEquals(emptyList<String>(), approvals[0].resources)
        assertEquals(null, approvals[0].sourceLabel)
        assertEquals(null, approvals[0].message)
    }

    // 2. Mapping decisione → body JSON ---------------------------------------

    @Test
    fun `decision mapped in body without message`() {
        assertEquals("""{"decision":"once"}""", ApprovalReply.bodyJson("once"))
        assertEquals("""{"decision":"always"}""", ApprovalReply.bodyJson("always"))
        assertEquals("""{"decision":"reject"}""", ApprovalReply.bodyJson("reject"))
        assertFalse(ApprovalReply.bodyJson("once").contains("message"))
    }

    @Test
    fun `decisione reject con message include il motivo`() {
        val raw = ApprovalReply.bodyJson("reject", "non serve")
        val obj = Json.parseToJsonElement(raw).jsonObject
        assertEquals("reject", obj["decision"]?.jsonPrimitive?.content)
        assertEquals("non serve", obj["message"]?.jsonPrimitive?.content)

        // Empty message: omitted, not sent as an empty string.
        val blank = Json.parseToJsonElement(ApprovalReply.bodyJson("once", "  ")).jsonObject
        assertFalse(blank.containsKey("message"))
    }

    // 3. 404 PermissionNotFound ≠ 404 session --------------------------------

    @Test
    fun `404 permission not found diventa ApprovalExpiredException`() {
        val body = """
            {"_tag":"PermissionNotFoundError","requestID":"per_finto",
             "message":"Permission request not found: per_finto"}
        """.trimIndent()

        val error = ApprovalErrors.fromHttpFailure(404, body, "/api/x")

        assertTrue(error is ApprovalExpiredException)
        assertEquals("per_finto", (error as ApprovalExpiredException).requestId)
    }

    @Test
    fun `404 session stays a distinct ApiException`() {
        val error = ApprovalErrors.fromHttpFailure(
            404,
            """{"_tag":"SessionNotFoundError","message":"session not found"}""",
            "/api/x",
        )

        assertTrue(error is ApiException)
        assertEquals(404, (error as ApiException).code)
    }

    // 4. Notification dedupe ------------------------------------------------------

    @Test
    fun `same requestID notified once and cancelled at resolution`() {
        val tracker = ApprovalNotificationTracker()

        assertTrue(tracker.markShown("per_1"))
        assertFalse(tracker.markShown("per_1"))
        assertTrue(tracker.isShown("per_1"))

        assertTrue(tracker.markResolved("per_1"))
        assertFalse(tracker.isShown("per_1"))
        // A second resolution must not cancel again.
        assertFalse(tracker.markResolved("per_1"))
    }

    // 5. Stable notification id ------------------------------------------------

    @Test
    fun `stable notification id per requestID`() {
        val first = ApprovalIds.notificationId("per_abc")
        val second = ApprovalIds.notificationId("per_abc")
        val other = ApprovalIds.notificationId("per_xyz")

        assertEquals(first, second)
        assertNotEquals(first, other)
        assertTrue(first > 0)
        assertTrue(other > 0)
        assertNotEquals(
            ApprovalIds.actionRequestCode("per_abc", "once"),
            ApprovalIds.actionRequestCode("per_abc", "reject"),
        )
    }

    // 6. Throttle puro e backoff ----------------------------------------------

    @Test
    fun `throttle watcher per session`() {
        var now = 0L
        val throttle = ApprovalThrottle(minIntervalMs = 2_000L) { now }

        assertTrue(throttle.shouldFetch("ses_1"))
        now = 1_999L
        assertFalse(throttle.shouldFetch("ses_1"))
        assertTrue(throttle.shouldFetch("ses_2"))
        now = 2_000L
        assertTrue(throttle.shouldFetch("ses_1"))

        throttle.reset("ses_1")
        assertTrue(throttle.shouldFetch("ses_1"))
    }

    @Test
    fun `backoff invariato e limitato`() {
        assertEquals(1_000L, Backoff.baseDelayMs(1))
        assertEquals(2_000L, Backoff.baseDelayMs(2))
        assertEquals(4_000L, Backoff.baseDelayMs(3))
        assertEquals(30_000L, Backoff.baseDelayMs(99))
        assertTrue(Backoff.delayMs(10, Random(7)) <= 30_000L)
    }

    // 7. Eventi SSE verificati ------------------------------------------------

    @Test
    fun `permission asked carries the whole request`() {
        val raw = """
            {"id":"evt_1","created":1792796421246,"type":"permission.asked",
             "location":{"directory":"/Users/patchouli"},
             "data":{"id":"per_1","sessionID":"ses_1","action":"read",
                     "resources":["perm-probe.env"],"save":["*"],
                     "source":{"type":"tool","messageID":"msg_1","id":"call_1"}}}
        """.trimIndent()

        val event = EventParsing.parseEvent(raw)

        assertTrue(event is ServerEvent.PermissionAsked)
        val asked = event as ServerEvent.PermissionAsked
        assertEquals("ses_1", asked.sessionId)
        assertEquals("per_1", asked.approval.id)
        assertEquals("read", asked.approval.action)
        assertEquals("tool", asked.approval.sourceLabel)
    }

    @Test
    fun `permission replied carries the request id`() {
        val raw = """
            {"id":"evt_2","created":1792796421247,"type":"permission.replied",
             "data":{"sessionID":"ses_1","requestID":"per_1",
                     "decision":"once"}}
        """.trimIndent()

        val event = EventParsing.parseEvent(raw)

        assertTrue(event is ServerEvent.PermissionReplied)
        assertEquals("per_1", (event as ServerEvent.PermissionReplied).requestId)
        assertEquals("ses_1", event.sessionId)
    }

    @Test
    fun `permission asked without id discarded without exceptions`() {
        val raw = """{"type":"permission.asked","data":{"action":"read"}}"""
        assertEquals(null, EventParsing.parseEvent(raw))
        assertEquals(null, EventParsing.parse("""{"type":"permission.asked","data":{}}"""))
    }
}
