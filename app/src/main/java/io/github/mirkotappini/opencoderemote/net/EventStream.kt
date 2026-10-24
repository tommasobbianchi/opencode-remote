package io.github.mirkotappini.opencoderemote.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Watchdog del canale SSE.
 *
 * Il server manda `: heartbeat` ogni ~10-12 s: 30 s senza alcun byte sono una
 * connessione morta, anche se il socket TCP non è ancora stato chiuso (caso
 * tipico della modalità aereo). Politica pura per i test JVM offline.
 */
object SseWatchdog {

    const val TIMEOUT_MS = 30_000L
    const val CHECK_INTERVAL_MS = 5_000L

    /** Nessun byte da più di [timeoutMs]: connessione da riconnettere. */
    fun isStale(lastActivityAt: Long, now: Long, timeoutMs: Long = TIMEOUT_MS): Boolean =
        lastActivityAt <= 0L || now - lastActivityAt > timeoutMs

    /** Simulazione deterministica: [silenceMs] di silenzio dopo l'ultimo byte. */
    fun expiredAfter(lastActivityAt: Long, silenceMs: Long, timeoutMs: Long = TIMEOUT_MS): Boolean =
        isStale(lastActivityAt, lastActivityAt + silenceMs, timeoutMs)
}

/** Evento osservato sul canale SSE `GET /api/event`. */
sealed interface SseEvent {
    data object Opened : SseEvent
    data class Message(val type: String?, val data: String) : SseEvent
    data class Failure(val reason: String, val httpCode: Int? = null) : SseEvent
    data object Closed : SseEvent
}

/**
 * Stream SSE live-only (nessun replay).
 *
 * Lettura raw della risposta per poter osservare anche le righe `: heartbeat`:
 * il timeout di 30 s scatta solo se il server non manda *nulla*, non se è
 * semplicemente una sessione senza eventi. Nessun payload viene loggato qui.
 */
class EventStream(
    private val client: OkHttpClient,
    private val url: String,
    private val authorization: String,
    private val heartbeatTimeoutMs: Long = SseWatchdog.TIMEOUT_MS,
) {
    fun events(): Flow<SseEvent> = callbackFlow {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        val call = client.newCall(request)
        val lastActivity = AtomicLong(System.currentTimeMillis())

        val watchdog = launch {
            while (isActive) {
                delay(SseWatchdog.CHECK_INTERVAL_MS)
                if (SseWatchdog.isStale(lastActivity.get(), System.currentTimeMillis(), heartbeatTimeoutMs)) {
                    trySend(
                        SseEvent.Failure(
                            "nessun dato dal server da ${heartbeatTimeoutMs / 1000}s",
                        ),
                    )
                    call.cancel()
                    close()
                    break
                }
            }
        }

        launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        trySend(
                            SseEvent.Failure("stream rifiutato", response.code),
                        )
                        close()
                        return@use
                    }

                    val source = response.body?.source()
                    if (source == null) {
                        trySend(SseEvent.Failure("risposta SSE senza corpo"))
                        close()
                        return@use
                    }

                    trySend(SseEvent.Opened)
                    lastActivity.set(System.currentTimeMillis())

                    val data = StringBuilder()
                    var eventType: String? = null

                    while (isActive) {
                        val line = source.readUtf8Line() ?: break
                        lastActivity.set(System.currentTimeMillis())
                        when {
                            line.isEmpty() -> {
                                if (data.isNotEmpty()) {
                                    trySend(SseEvent.Message(eventType, data.toString()))
                                    data.setLength(0)
                                    eventType = null
                                }
                            }
                            line.startsWith(":") -> Unit // heartbeat/commento
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.removePrefix("data:").removePrefix(" "))
                            }
                            line.startsWith("event:") ->
                                eventType = line.removePrefix("event:").trim()
                            line.startsWith("id:") -> Unit
                            line.startsWith("retry:") -> Unit
                            else -> Unit
                        }
                    }

                    if (isActive) {
                        trySend(SseEvent.Closed)
                        close()
                    }
                }
            } catch (e: IOException) {
                if (isActive) {
                    trySend(SseEvent.Failure("stream interrotto: ${e.javaClass.simpleName}"))
                    close()
                }
            } catch (e: Exception) {
                if (isActive) {
                    trySend(SseEvent.Failure("stream interrotto: ${e.javaClass.simpleName}"))
                    close()
                }
            }
        }

        awaitClose {
            watchdog.cancel()
            call.cancel()
        }
    }
}
