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
 * Foreground service (`dataSync`) che tiene una SSE indipendente dal ViewModel.
 *
 * Fonte primaria: `permission.asked` (porta l'intero Permission.Request) e
 * `permission.replied` per annullare. La `GET …/permission` viene usata solo
 * come allineamento all'apertura/riconnessione e dopo la fine di un turno,
 * con throttle per sessione. Le richieste di ogni sessione vengono notificate,
 * non solo quelle della sessione aperta nell'app.
 */
class ApprovalService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watcherJob: Job? = null
    private lateinit var notifier: ApprovalNotifier
    private val authRejected = AtomicBoolean(false)

    /** Sessioni viste almeno una volta: da riallineare a ogni riconnessione. */
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
        // M5: se il pairing è già stato rifiutato non si riavvia in loop.
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
        // Il servizio che muore non deve lasciare notifiche orfane.
        ApprovalNotifier.cancelAll(this)
        NotificationManagerCompat.from(this).cancel(SERVICE_NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun stopAndCancelNotifications() {
        ApprovalNotifier.cancelAll(this)
        stopSelf()
    }

    /**
     * M5: il token è stato rifiutato (SSE o REST). Non si cancella il token,
     * ma si ferma il servizio e si rimuovono le notifiche: niente retry loop.
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
                    // Token non più valido: inutile restare in foreground.
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
                // Difensivo: qualunque type con "permission" forza un allineamento.
                val sessionId = event.sessionId
                if (sessionId != null && incoming.wireType.contains("permission", true)) {
                    throttle.reset(sessionId)
                    if (throttle.shouldFetch(sessionId)) refreshSession(api, sessionId)
                }
            }
        }
    }

    /** All'apertura/riconnessione: sessione attiva + tutte quelle già viste. */
    private fun realignAll(api: OpenCodeApi) {
        val active = runCatching { api.activeSession() }
            .onFailure { onRestFailure(it) }
            .getOrNull()
        if (active != null) knownSessions += active

        // Copia: refreshSession non modifica l'insieme, ma restiamo espliciti.
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
