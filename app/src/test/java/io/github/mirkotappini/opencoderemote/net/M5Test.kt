package io.github.mirkotappini.opencoderemote.net

import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM stand-in for `android.net.http.UnknownServiceException`, which is not
 * cannot be instantiated in local tests. Production recognizes it by name.
 */
private class UnknownServiceException(message: String) : java.io.IOException(message)

/**
 * JVM tests for M5 (hardening). All offline: version gate, error
 * taxonomy, 401 policy, backoff cap, expired pagination and parsing
 * of a payload as large as `/api/skill`.
 */
class M5Test {

    // 1. Version gate ---------------------------------------------------------

    @Test
    fun `version 2x accepted`() {
        assertEquals(VersionCheck.Supported, ServerVersion.check("2.1.4"))
        assertEquals(VersionCheck.Supported, ServerVersion.check("2.0.0"))
        assertEquals(VersionCheck.Supported, ServerVersion.check(" 2.1.4-beta.1 "))
        assertEquals(VersionCheck.Supported, ServerVersion.check("v2.1"))
    }

    @Test
    fun `version 1x blocked with a clear message`() {
        val check = ServerVersion.check("1.18.35")
        assertTrue(check is VersionCheck.Unsupported)
        assertEquals("1.18.35", (check as VersionCheck.Unsupported).found)
        assertTrue(ServerVersion.unsupportedMessage(check.found).contains("2.x required"))
        assertFalse(ServerVersion.unsupportedMessage(check.found).contains("https://"))
    }

    @Test
    fun `unparsable version does not block`() {
        assertEquals(VersionCheck.Unknown, ServerVersion.check("boh"))
        assertEquals(VersionCheck.Unknown, ServerVersion.check(""))
        assertEquals(VersionCheck.Unknown, ServerVersion.check(null))
        // Unknown is not Unsupported: the ViewModel stays Connected with the raw version.
        assertNotEquals(VersionCheck.Unsupported("boh"), ServerVersion.check("boh"))
    }

    // 2. Tassonomia errori ----------------------------------------------------

    @Test
    fun `tassonomia errori con messaggi attesi`() {
        val cases = listOf(
            SSLHandshakeException("bad") to ConnectionErrors.TLS_INVALID,
            SSLPeerUnverifiedException("bad") to ConnectionErrors.TLS_INVALID,
            SSLException("bad") to ConnectionErrors.TLS_INVALID,
            SocketException("reset by peer") to ConnectionErrors.CONNECTION_LOST,
            EOFException("eof") to ConnectionErrors.CONNECTION_LOST,
            SocketTimeoutException("timeout") to "server unreachable (timeout)",
            ApiException(403, "/api/session") to ConnectionErrors.ACCESS_DENIED,
            ApiException(500, "/api/session") to ConnectionErrors.SERVER_ERROR,
            ApiException(503, "/api/skill") to ConnectionErrors.SERVER_ERROR,
            UnknownServiceException("unexpected content type") to ConnectionErrors.INVALID_RESPONSE,
        )

        cases.forEach { (error, expected) ->
            assertEquals("error ${error.javaClass.simpleName}", expected, ConnectionErrors.describe(error))
        }
    }

    @Test
    fun `messages without credentials or URL`() {
        val leaky = RuntimeException("failed for https://user:secret@patchouli/api/info")
        val message = ConnectionErrors.describe(leaky)
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("user:"))
    }

    // 3. Politica globale 401 -------------------------------------------------

    @Test
    fun `401 stops stream and service keeps token and does not retry`() {
        val policy = AuthPolicy.forHttpCode(401)
        assertTrue(policy != null)
        policy!!
        assertTrue(policy.stopStream)
        assertTrue(policy.stopBackgroundService)
        assertTrue(policy.cancelNotifications)
        assertTrue(policy.keepToken)
        assertFalse(policy.retryFailedRequest)
        assertEquals(AuthPolicy.onUnauthorized, policy)

        // The other codes do not activate the policy.
        assertEquals(null, AuthPolicy.forHttpCode(500))
        assertEquals(null, AuthPolicy.forHttpCode(403))
    }

    // 4. Backoff with cap and jitter ------------------------------------------

    @Test
    fun `backoff does not exceed 30s and jitter stays in range`() {
        (1..200).forEach { attempt ->
            val base = Backoff.baseDelayMs(attempt)
            assertTrue("base $base above the cap", base <= Backoff.MAX_MS)
            // No infinite loop: the value is always finite and bounded.
            assertTrue(base >= Backoff.BASE_MS)
        }

        val random = Random(1234)
        (1..50).forEach { attempt ->
            val base = Backoff.baseDelayMs(attempt)
            val delay = Backoff.delayMs(attempt, random)
            assertTrue(delay >= base)
            assertTrue(delay <= Backoff.MAX_MS)
            val maxJitter = if (base >= Backoff.MAX_MS) 0L else base / 5L
            assertTrue(delay <= base + maxJitter)
        }
        assertEquals(30_000L, Backoff.delayMs(Int.MAX_VALUE, random))
    }

    // 5. Paginazione scaduta --------------------------------------------------

    @Test
    fun `400 in paginazione ha messaggio dedicato`() {
        val expired = ApiException(400, "/api/session/ses_1/message")
        assertEquals(
            "page no longer valid",
            ConnectionErrors.describe(expired, ErrorContext.Pagination),
        )
        // The same 400 outside pagination does not use that text.
        assertNotEquals(
            "page no longer valid",
            ConnectionErrors.describe(expired, ErrorContext.Generic),
        )
    }

    // 6. Parsing a large payload ----------------------------------------

    @Test
    fun `skill payload of about 100KB parsed without errors`() {
        val longContent = "c".repeat(5_500)
        val longDescription = "d".repeat(900)
        val body = buildString {
            append("""{"data":[""")
            repeat(16) { index ->
                if (index > 0) append(',')
                append(
                    """{"id":"skill_$index","name":"Skill $index",""" +
                        """"description":"$longDescription","path":"/tmp/skill$index",""" +
                        """"content":"$longContent"}""",
                )
            }
            append("]}")
        }
        assertTrue("payload too small: ${body.length}", body.length >= 90_000)

        val started = System.nanoTime()
        val skills = CatalogParsing.parseSkills(body)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertEquals(16, skills.size)
        assertEquals("skill_15", skills.last().id)
        assertTrue("parsing too slow: ${elapsedMs}ms", elapsedMs < 2_000L)
    }
}
