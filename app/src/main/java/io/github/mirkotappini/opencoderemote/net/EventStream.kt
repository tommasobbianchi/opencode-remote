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
 * Watchdog for the SSE channel.
 *
 * The server sends `: heartbeat` every ~10-12 s: 30 s without any byte is a
 * dead connection, even if the TCP socket has not been closed yet (typical
 * of airplane mode). Pure policy for offline JVM tests.
 */
object SseWatchdog {

    const val TIMEOUT_MS = 30_000L
    const val CHECK_INTERVAL_MS = 5_000L

    /** No byte for more than [timeoutMs]: connection to be reconnected. */
    fun isStale(lastActivityAt: Long, now: Long, timeoutMs: Long = TIMEOUT_MS): Boolean =
        lastActivityAt <= 0L || now - lastActivityAt > timeoutMs

    /** Deterministic simulation: [silenceMs] of silence after the last byte. */
    fun expiredAfter(lastActivityAt: Long, silenceMs: Long, timeoutMs: Long = TIMEOUT_MS): Boolean =
        isStale(lastActivityAt, lastActivityAt + silenceMs, timeoutMs)
}

/** Event observed on the SSE channel `GET /api/event`. */
sealed interface SseEvent {
    data object Opened : SseEvent
    data class Message(val type: String?, val data: String) : SseEvent
    data class Failure(val reason: String, val httpCode: Int? = null) : SseEvent
    data object Closed : SseEvent
}

/**
 * Live-only SSE stream (no replay).
 *
 * Raw reading of the response so that `: heartbeat` lines can be observed
 * too: the 30 s timeout fires only if the server sends *nothing*, not if it
 * is simply a session without events. No payload is logged here.
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
                            "no data from server for ${heartbeatTimeoutMs / 1000}s",
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
                        trySend(SseEvent.Failure("SSE response without body"))
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
