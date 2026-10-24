package io.github.mirkotappini.opencoderemote.net

import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// ---------------------------------------------------------------------------
// M5 — hardening: versione del server, errori parlanti, politica dei 401.
// Tutto puro (nessun Android) così i test JVM restano offline.
// ---------------------------------------------------------------------------

/** Esito del controllo su `GET /api/info.version`. */
sealed interface VersionCheck {
    /** Major 2.x: si può procedere. */
    data object Supported : VersionCheck

    /**
     * Versione non parsabile: non blocca. Lo stato resta `Connected` con la
     * stringa grezza mostrata come versione del server.
     */
    data object Unknown : VersionCheck

    /** Major diverso da 2: schermata bloccante, nessuna chiamata ulteriore. */
    data class Unsupported(val found: String) : VersionCheck
}

/** Gate di versione: accetta solo la 2.x. */
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

    /** Testo unico della schermata bloccante (nessun URL, nessun dettaglio interno). */
    fun unsupportedMessage(found: String): String =
        "server OpenCode $found non supportato: serve la 2.x"

    /**
     * Estrae il major da stringhe come `2.1.4`, `2.1.4-beta.1`, `v2.0`.
     * `null` se non c'è una componente numerica iniziale.
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

/** Contesto della chiamata: un 400 di paginazione ha un messaggio dedicato. */
enum class ErrorContext { Generic, Pagination }

/**
 * Messaggi utente per gli errori di rete/HTTP. Mai stack trace, mai header,
 * mai URL con credenziali: solo testo fisso o un fallback ripulito.
 */
object ConnectionErrors {

    const val TLS_INVALID = "certificato TLS non valido"
    const val CONNECTION_LOST = "connessione interrotta"
    const val ACCESS_DENIED = "accesso negato"
    const val SERVER_ERROR = "server in errore (HTTP 5xx)"
    const val PAGE_EXPIRED = "pagina non più valida"
    const val INVALID_RESPONSE = "risposta non valida dal server"
    const val CREDENTIALS_REJECTED = "credenziali rifiutate"

    fun describe(error: Throwable, context: ErrorContext = ErrorContext.Generic): String = when {
        error is ApiException && error.code == 401 -> CREDENTIALS_REJECTED
        error is ApiException && error.code == 403 -> ACCESS_DENIED
        error is ApiException && error.code == 400 && context == ErrorContext.Pagination ->
            PAGE_EXPIRED
        error is ApiException && error.code == 400 -> "richiesta non valida"
        error is ApiException && error.code in 500..599 -> SERVER_ERROR
        error is ApiException && error.code == 404 -> "sessione non trovata"
        error is ApiException && error.code == 409 ->
            "sessione occupata: attendi la fine o interrompi"
        // Handshake, pinning, certificato scaduto: tutti indistinguibili per l'utente.
        error is SSLException -> TLS_INVALID
        error is SocketTimeoutException -> "server non raggiungibile (timeout)"
        error is UnknownHostException -> "server non raggiungibile (host sconosciuto)"
        error is ConnectException || error is NoRouteToHostException ->
            "server non raggiungibile"
        error is SocketException || error is EOFException -> CONNECTION_LOST
        isUnknownService(error) -> INVALID_RESPONSE
        else -> safeFallback(error)
    }

    /**
     * `android.net.http.UnknownServiceException` vive nell'SDK Android (non nel
     * jar JVM dei test): si riconosce dal nome, senza dipendere dalla classe.
     */
    private fun isUnknownService(error: Throwable): Boolean =
        error is java.io.IOException && error.javaClass.name.endsWith(".UnknownServiceException")

    /** Il messaggio di OkHttp può contenere l'URL: si tiene solo testo innocuo. */
    private fun safeFallback(error: Throwable): String {
        val raw = error.message?.take(160)?.trim().orEmpty()
        if (raw.isEmpty()) return error.javaClass.simpleName
        val scrubbed = raw.replace(Regex("""//[^/@\s]+@"""), "//***@")
        return scrubbed.ifEmpty { error.javaClass.simpleName }
    }
}

/**
 * Effetti obbligatori di un 401 su una qualunque chiamata REST.
 * Il token resta: serve a precompilare il setup, non si ritenta da soli.
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

    /** `null` se il codice non è 401. */
    fun forHttpCode(code: Int): AuthFailurePolicy? =
        if (code == 401) onUnauthorized else null
}
