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
 * Sostituto JVM di `android.net.http.UnknownServiceException`, che non è
 * istanziabile nei test locali. La produzione lo riconosce dal nome.
 */
private class UnknownServiceException(message: String) : java.io.IOException(message)

/**
 * Test JVM di M5 (hardening). Tutto offline: gate di versione, tassonomia
 * errori, politica dei 401, tetto del backoff, paginazione scaduta e parsing
 * di un payload grande come `/api/skill`.
 */
class M5Test {

    // 1. Version gate ---------------------------------------------------------

    @Test
    fun `versione 2x accettata`() {
        assertEquals(VersionCheck.Supported, ServerVersion.check("2.1.4"))
        assertEquals(VersionCheck.Supported, ServerVersion.check("2.0.0"))
        assertEquals(VersionCheck.Supported, ServerVersion.check(" 2.1.4-beta.1 "))
        assertEquals(VersionCheck.Supported, ServerVersion.check("v2.1"))
    }

    @Test
    fun `versione 1x bloccata con messaggio chiaro`() {
        val check = ServerVersion.check("1.18.35")
        assertTrue(check is VersionCheck.Unsupported)
        assertEquals("1.18.35", (check as VersionCheck.Unsupported).found)
        assertTrue(ServerVersion.unsupportedMessage(check.found).contains("serve la 2.x"))
        assertFalse(ServerVersion.unsupportedMessage(check.found).contains("https://"))
    }

    @Test
    fun `versione non parsabile non blocca`() {
        assertEquals(VersionCheck.Unknown, ServerVersion.check("boh"))
        assertEquals(VersionCheck.Unknown, ServerVersion.check(""))
        assertEquals(VersionCheck.Unknown, ServerVersion.check(null))
        // Unknown non è Unsupported: il ViewModel resta Connected con la grezza.
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
            SocketTimeoutException("timeout") to "server non raggiungibile (timeout)",
            ApiException(403, "/api/session") to ConnectionErrors.ACCESS_DENIED,
            ApiException(500, "/api/session") to ConnectionErrors.SERVER_ERROR,
            ApiException(503, "/api/skill") to ConnectionErrors.SERVER_ERROR,
            UnknownServiceException("unexpected content type") to ConnectionErrors.INVALID_RESPONSE,
        )

        cases.forEach { (error, expected) ->
            assertEquals("errore ${error.javaClass.simpleName}", expected, ConnectionErrors.describe(error))
        }
    }

    @Test
    fun `messaggi senza credenziali o URL`() {
        val leaky = RuntimeException("failed for https://user:secret@patchouli/api/info")
        val message = ConnectionErrors.describe(leaky)
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("user:"))
    }

    // 3. Politica globale 401 -------------------------------------------------

    @Test
    fun `401 ferma stream e service mantiene token e non ritenta`() {
        val policy = AuthPolicy.forHttpCode(401)
        assertTrue(policy != null)
        policy!!
        assertTrue(policy.stopStream)
        assertTrue(policy.stopBackgroundService)
        assertTrue(policy.cancelNotifications)
        assertTrue(policy.keepToken)
        assertFalse(policy.retryFailedRequest)
        assertEquals(AuthPolicy.onUnauthorized, policy)

        // Gli altri codici non attivano la policy.
        assertEquals(null, AuthPolicy.forHttpCode(500))
        assertEquals(null, AuthPolicy.forHttpCode(403))
    }

    // 4. Backoff con tetto e jitter ------------------------------------------

    @Test
    fun `backoff non supera 30s e il jitter resta nel range`() {
        (1..200).forEach { attempt ->
            val base = Backoff.baseDelayMs(attempt)
            assertTrue("base $base oltre il tetto", base <= Backoff.MAX_MS)
            // Nessun ciclo infinito: il valore è sempre finito e limitato.
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
            "pagina non più valida",
            ConnectionErrors.describe(expired, ErrorContext.Pagination),
        )
        // Lo stesso 400 fuori dalla paginazione non usa quel testo.
        assertNotEquals(
            "pagina non più valida",
            ConnectionErrors.describe(expired, ErrorContext.Generic),
        )
    }

    // 6. Parsing di un payload grande ----------------------------------------

    @Test
    fun `payload skill da circa 100KB parsato senza errori`() {
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
        assertTrue("payload troppo piccolo: ${body.length}", body.length >= 90_000)

        val started = System.nanoTime()
        val skills = CatalogParsing.parseSkills(body)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertEquals(16, skills.size)
        assertEquals("skill_15", skills.last().id)
        assertTrue("parsing troppo lento: ${elapsedMs}ms", elapsedMs < 2_000L)
    }
}
