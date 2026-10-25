package io.github.mirkotappini.opencoderemote.net

import io.github.mirkotappini.opencoderemote.SessionNav
import io.github.mirkotappini.opencoderemote.prependSession
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Offline JVM tests for "new session": contract of `POST /api/session` and
 * state transition (row on top, writable chat). The HTTP contract uses
 * MockWebServer on loopback; the ViewModel cannot be instantiated without Android,
 * so the transition is covered by the pure functions it uses internally
 * (`prependSession` + `SessionNav`).
 */
class NewSessionTest {

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun api() = OpenCodeApi(
        client = OkHttpClient(),
        baseUrl = server.url("/").toString(),
        user = "opencode",
        password = "secret",
    )

    @Test
    fun `creates the session with empty POST body and correct auth`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"data":{"id":"ses_new","title":"New session",
                     "time":{"created":1,"updated":2},
                     "location":{"directory":"/Users/patchouli"},
                     "cost":0,"tokens":{"input":0,"output":0},
                     "agent":"build","model":{"id":"m","providerID":"p"},
                     "outcome":"running"}}
                    """.trimIndent(),
                ),
        )

        val created = api().createSession()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/session", request.path)
        assertEquals("{}", request.body.readUtf8())
        assertEquals(Credentials.basic("opencode", "secret", Charsets.UTF_8), request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals(1, server.requestCount)
        assertEquals("ses_new", created.id)
        assertEquals("New session", created.title)
        assertEquals("build", created.agent)
        assertEquals("m", created.modelId)
    }

    @Test
    fun `created session goes on top and chat opens writable`() {
        val existing = listOf(
            SessionUi(id = "ses_old", title = "vecchia"),
            SessionUi(id = "ses_older", title = "più vecchia"),
        )
        val created = SessionUi(id = "ses_new", title = "nuova")

        val sessions = prependSession(existing, created)

        assertEquals(listOf("ses_new", "ses_old", "ses_older"), sessions.map { it.id })
        // Same id does not duplicate (like opening from a notification).
        assertEquals(sessions, prependSession(sessions, created))

        // `openSession(created.id)` resumes from the list row with an empty stack.
        val plan = SessionNav.planOpen(
            stack = emptyList(),
            current = null,
            nextSession = sessions.first(),
        )
        assertTrue(plan.stack.isEmpty())
        assertFalse(plan.readOnly)
    }

    @Test
    fun `server error does not modify the list`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"_tag":"UnknownError","message":"boom"}"""),
        )
        var sessions = listOf(SessionUi(id = "ses_old", title = "vecchia"))

        val result = runCatching { api().createSession() }
        val error = result.exceptionOrNull()
        // Same sequence as the ViewModel: prepends only on success.
        result.onSuccess { created -> sessions = prependSession(sessions, created) }

        assertTrue(error is ApiException)
        val apiError = error as ApiException
        assertEquals(500, apiError.code)
        assertEquals(ConnectionErrors.SERVER_ERROR, ConnectionErrors.describe(apiError))
        assertEquals(listOf("ses_old"), sessions.map { it.id })
    }

    @Test
    fun `401 maps to rejected credentials`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"_tag":"Unauthorized","message":"no"}"""),
        )

        val error = runCatching { api().createSession() }.exceptionOrNull()

        assertTrue(error is ApiException)
        val apiError = error as ApiException
        assertEquals(401, apiError.code)
        assertEquals(ConnectionErrors.CREDENTIALS_REJECTED, ConnectionErrors.describe(apiError))
    }
}
