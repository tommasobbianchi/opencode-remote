package io.github.mirkotappini.opencoderemote.net

import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// ---------------------------------------------------------------------------
// M5 — hardening: server version, readable errors, 401 policy.
// All pure (no Android) so JVM tests stay offline.
// ---------------------------------------------------------------------------

/** Outcome of the check on `GET /api/info.version`. */
sealed interface VersionCheck {
    /** Major 2.x: safe to proceed. */
    data object Supported : VersionCheck

    /**
     * Unparseable version: not blocking. The state stays `Connected` with the
     * raw string shown as the server version.
     */
    data object Unknown : VersionCheck

    /** Major different from 2: blocking screen, no further calls. */
    data class Unsupported(val found: String) : VersionCheck
}

/** Version gate: accepts only 2.x. */
object ServerVersion {

    const val REQUIRED_MAJOR = 2

    fun check(raw: String?): VersionCheck {
        val found = raw?.trim().orEmpty()
        if (found.isEmpty()) return VersionCheck.Unknown
        val major = majorOf(found) ?: return VersionCheck.Unknown
        return if (major == REQUIRED_MAJOR) {
            VersionCheck.Supported
        } else {
            VersionCheck.Unsupported(found)
        }
    }

    /** Single text for the blocking screen (no URL, no internal detail). */
    fun unsupportedMessage(found: String): String =
        "OpenCode server $found unsupported: 2.x required"

    /**
     * Extracts the major from strings like `2.1.4`, `2.1.4-beta.1`, `v2.0`.
     * `null` if there is no leading numeric component.
     */
    fun majorOf(raw: String): Int? {
        val normalized = raw.trim().removePrefix("v").removePrefix("V")
        val first = normalized
            .substringBefore('.')
            .substringBefore('-')
            .takeWhile { it.isDigit() }
        return first.toIntOrNull()
    }
}

/** Call context: a pagination 400 has a dedicated message. */
enum class ErrorContext { Generic, Pagination }

/**
 * User-facing messages for network/HTTP errors. Never stack traces, never
 * headers, never URLs with credentials: fixed text only, or a scrubbed
 * fallback.
 */
object ConnectionErrors {

    const val TLS_INVALID = "invalid TLS certificate"
    const val CONNECTION_LOST = "connection interrupted"
    const val ACCESS_DENIED = "access denied"
    const val SERVER_ERROR = "server error (HTTP 5xx)"
    const val PAGE_EXPIRED = "page no longer valid"
    const val INVALID_RESPONSE = "invalid response from server"
    const val CREDENTIALS_REJECTED = "credentials rejected"

    fun describe(error: Throwable, context: ErrorContext = ErrorContext.Generic): String = when {
        error is ApiException && error.code == 401 -> CREDENTIALS_REJECTED
        error is ApiException && error.code == 403 -> ACCESS_DENIED
        error is ApiException && error.code == 400 && context == ErrorContext.Pagination ->
            PAGE_EXPIRED
        error is ApiException && error.code == 400 -> "invalid request"
        error is ApiException && error.code in 500..599 -> SERVER_ERROR
        error is ApiException && error.code == 404 -> "session not found"
        error is ApiException && error.code == 409 ->
            "session busy: wait for it to finish or interrupt"
        // Handshake, pinning, expired certificate: all indistinguishable for the user.
        error is SSLException -> TLS_INVALID
        error is SocketTimeoutException -> "server unreachable (timeout)"
        error is UnknownHostException -> "server unreachable (unknown host)"
        error is ConnectException || error is NoRouteToHostException ->
            "server unreachable"
        error is SocketException || error is EOFException -> CONNECTION_LOST
        isUnknownService(error) -> INVALID_RESPONSE
        else -> safeFallback(error)
    }

    /**
     * `android.net.http.UnknownServiceException` lives in the Android SDK (not in
     * the JVM test jar): it is recognized by name, without depending on the class.
     */
    private fun isUnknownService(error: Throwable): Boolean =
        error is java.io.IOException && error.javaClass.name.endsWith(".UnknownServiceException")

    /** The OkHttp message may contain the URL: only harmless text is kept. */
    private fun safeFallback(error: Throwable): String {
        val raw = error.message?.take(160)?.trim().orEmpty()
        if (raw.isEmpty()) return error.javaClass.simpleName
        val scrubbed = raw.replace(Regex("""//[^/@\s]+@"""), "//***@")
        return scrubbed.ifEmpty { error.javaClass.simpleName }
    }
}

/**
 * Mandatory effects of a 401 on any REST call.
 * The token is kept: it pre-fills the setup, no automatic retry is made.
 */
data class AuthFailurePolicy(
    val stopStream: Boolean,
    val stopBackgroundService: Boolean,
    val cancelNotifications: Boolean,
    val keepToken: Boolean,
    val retryFailedRequest: Boolean,
)

object AuthPolicy {

    val onUnauthorized = AuthFailurePolicy(
        stopStream = true,
        stopBackgroundService = true,
        cancelNotifications = true,
        keepToken = true,
        retryFailedRequest = false,
    )

    /** `null` if the code is not 401. */
    fun forHttpCode(code: Int): AuthFailurePolicy? =
        if (code == 401) onUnauthorized else null
}
