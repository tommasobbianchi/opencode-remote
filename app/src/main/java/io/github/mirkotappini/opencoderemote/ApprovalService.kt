package io.github.mirkotappini.opencoderemote

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import io.github.mirkotappini.opencoderemote.net.ApiException
import io.github.mirkotappini.opencoderemote.net.ApprovalThrottle
import io.github.mirkotappini.opencoderemote.net.Backoff
import io.github.mirkotappini.opencoderemote.net.EventParsing
import io.github.mirkotappini.opencoderemote.net.EventStream
import io.github.mirkotappini.opencoderemote.net.OpenCodeApi
import io.github.mirkotappini.opencoderemote.net.ServerEvent
import io.github.mirkotappini.opencoderemote.net.SseEvent
import io.github.mirkotappini.opencoderemote.security.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service (`dataSync`) that maintains an SSE independent from the
 * ViewModel.
 *
 * Primary source: `permission.asked` (carries the full Permission.Request) and
 * `permission.replied` to cancel. The `GET …/permission` is used only for alignment
 * on open/reconnect and after a turn ends, with per-session throttling. Requests
 * from every session are notified, not just those of the session open in the app.
 */
class ApprovalService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watcherJob: Job? = null
    private lateinit var notifier: ApprovalNotifier
    private val authRejected = AtomicBoolean(false)

    /** Sessions viewed at least once: to be realigned on every reconnection. */
    private val knownSessions = LinkedHashSet<String>()

    override fun onCreate() {
        super.onCreate()
        notifier = ApprovalNotifier(this)
        notifier.ensureChannels()
        startForeground(
            SERVICE_NOTIFICATION_ID,
            notifier.serviceNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // M5: if the pairing has already been rejected, it does not restart in a
        // loop.
        if (TokenStore(this).isAuthRejected()) {
            stopAndCancelNotifications()
            return START_NOT_STICKY
        }
        if (watcherJob?.isActive != true) startWatcher()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        // A dying service must not leave orphaned notifications.
        ApprovalNotifier.cancelAll(this)
        NotificationManagerCompat.from(this).cancel(SERVICE_NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun stopAndCancelNotifications() {
        ApprovalNotifier.cancelAll(this)
        stopSelf()
    }

    /**
     * M5: the token was rejected (SSE or REST). Do not clear the token, but stop
     * the service and remove notifications: no retry loop.
     */
    private fun handleUnauthorized() {
        if (!authRejected.compareAndSet(false, true)) return
        TokenStore(this).markAuthRejected()
        stopAndCancelNotifications()
    }

    private fun startWatcher() {
        watcherJob = scope.launch {
            val credentials = TokenStore(this@ApprovalService).load()
            if (credentials == null) {
                stopAndCancelNotifications()
                return@launch
            }

            val rest = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
            val sse = rest.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            val api = OpenCodeApi(
                rest,
                credentials.baseUrl,
                credentials.user,
                credentials.password,
            )
            val stream = EventStream(sse, api.eventUrl(), api.authHeader())
            val throttle = ApprovalThrottle()

            var attempt = 0
            while (isActive) {
                var failure: SseEvent.Failure? = null
                stream.events().collect { event ->
                    when (event) {
                        SseEvent.Opened -> {
                            attempt = 0
                            realignAll(api)
                            throttle.clear()
                        }

                        is SseEvent.Message -> handleRawEvent(api, event.data, throttle)

                        is SseEvent.Failure -> failure = event

                        SseEvent.Closed -> Unit
                    }
                }

                if (!isActive) break
                if (failure?.httpCode == 401) {
                    // Token no longer valid: no point staying in the foreground.
                    handleUnauthorized()
                    break
                }

                attempt += 1
                delay(Backoff.delayMs(attempt))
            }
        }
    }

    private fun handleRawEvent(api: OpenCodeApi, raw: String, throttle: ApprovalThrottle) {
        val incoming = EventParsing.parse(raw) ?: return
        when (val event = incoming.event) {
            is ServerEvent.PermissionAsked -> {
                knownSessions += event.sessionId
                notifier.notify(event.approval)
            }

            is ServerEvent.PermissionReplied -> {
                event.requestId?.let { notifier.cancel(it) }
            }

            is ServerEvent.ExecutionFinished -> {
                event.sessionId?.let { sessionId ->
                    throttle.reset(sessionId)
                    if (throttle.shouldFetch(sessionId)) refreshSession(api, sessionId)
                }
            }

            is ServerEvent.ExecutionStarted -> {
                event.sessionId?.let { sessionId ->
                    if (throttle.shouldFetch(sessionId)) refreshSession(api, sessionId)
                }
            }

            is ServerEvent.StepStarted -> {
                event.sessionId.let { sessionId ->
                    if (throttle.shouldFetch(sessionId)) refreshSession(api, sessionId)
                }
            }

            else -> {
                // Defensive: any type with "permission" forces alignment.
                val sessionId = event.sessionId
                if (sessionId != null && incoming.wireType.contains("permission", true)) {
                    throttle.reset(sessionId)
                    if (throttle.shouldFetch(sessionId)) refreshSession(api, sessionId)
                }
            }
        }
    }

    /** On open/reconnect: active session + all previously seen ones. */
    private fun realignAll(api: OpenCodeApi) {
        val active = runCatching { api.activeSession() }
            .onFailure { onRestFailure(it) }
            .getOrNull()
        if (active != null) knownSessions += active

        // Copy: refreshSession does not modify the set, but let's be explicit.
        knownSessions.toList().forEach { refreshSession(api, it) }
    }

    private fun refreshSession(api: OpenCodeApi, sessionId: String) {
        runCatching { api.permissions(sessionId) }
            .onSuccess { notifier.sync(sessionId, it) }
            .onFailure { onRestFailure(it) }
    }

    private fun onRestFailure(error: Throwable) {
        if (error is ApiException && error.code == 401) handleUnauthorized()
    }

    companion object {
        private const val SERVICE_NOTIFICATION_ID = 1
        const val ACTION_STOP = "io.github.mirkotappini.opencoderemote.action.STOP_APPROVAL_SERVICE"
    }
}
