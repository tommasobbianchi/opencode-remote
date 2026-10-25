package io.github.mirkotappini.opencoderemote

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import io.github.mirkotappini.opencoderemote.attachments.AttachmentPreparer
import io.github.mirkotappini.opencoderemote.attachments.AttachmentResult
import io.github.mirkotappini.opencoderemote.net.AgentUi
import io.github.mirkotappini.opencoderemote.net.ApiException
import io.github.mirkotappini.opencoderemote.net.ApprovalExpiredException
import io.github.mirkotappini.opencoderemote.net.ApprovalUi
import io.github.mirkotappini.opencoderemote.net.AuthPolicy
import io.github.mirkotappini.opencoderemote.net.Backoff
import io.github.mirkotappini.opencoderemote.net.Catalog
import io.github.mirkotappini.opencoderemote.net.ChatReducer
import io.github.mirkotappini.opencoderemote.net.ChatStream
import io.github.mirkotappini.opencoderemote.net.CommandUi
import io.github.mirkotappini.opencoderemote.net.ConnectionErrors
import io.github.mirkotappini.opencoderemote.net.ErrorContext
import io.github.mirkotappini.opencoderemote.net.EventParsing
import io.github.mirkotappini.opencoderemote.net.EventStream
import io.github.mirkotappini.opencoderemote.net.FormUi
import io.github.mirkotappini.opencoderemote.net.FormValue
import io.github.mirkotappini.opencoderemote.net.LiveMessage
import io.github.mirkotappini.opencoderemote.net.LocationUi
import io.github.mirkotappini.opencoderemote.net.McpUi
import io.github.mirkotappini.opencoderemote.net.MessageFileUi
import io.github.mirkotappini.opencoderemote.net.MessageUi
import io.github.mirkotappini.opencoderemote.net.ModelUi
import io.github.mirkotappini.opencoderemote.net.OpenChatState
import io.github.mirkotappini.opencoderemote.net.OpenCodeApi
import io.github.mirkotappini.opencoderemote.net.PromptFile
import io.github.mirkotappini.opencoderemote.net.PublishGate
import io.github.mirkotappini.opencoderemote.net.ReconnectPolicy
import io.github.mirkotappini.opencoderemote.net.RetryState
import io.github.mirkotappini.opencoderemote.net.RetryTransitions
import io.github.mirkotappini.opencoderemote.net.Role
import io.github.mirkotappini.opencoderemote.net.ServerVersion
import io.github.mirkotappini.opencoderemote.net.SkillUi
import io.github.mirkotappini.opencoderemote.net.ServerEvent
import io.github.mirkotappini.opencoderemote.net.SessionCommands
import io.github.mirkotappini.opencoderemote.net.SessionUi
import io.github.mirkotappini.opencoderemote.net.SlashCommands
import io.github.mirkotappini.opencoderemote.net.SseEvent
import io.github.mirkotappini.opencoderemote.net.SseWatchdog
import io.github.mirkotappini.opencoderemote.net.VersionCheck
import io.github.mirkotappini.opencoderemote.net.publishedImmediately
import io.github.mirkotappini.opencoderemote.net.reconcileMessages
import io.github.mirkotappini.opencoderemote.security.StoredCredentials
import io.github.mirkotappini.opencoderemote.security.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Status of the REST connection to the OpenCode server. */
sealed interface Connection {
    data object Disconnected : Connection
    data object Connecting : Connection
    data class Connected(val version: String, val pid: Long) : Connection
    data class Error(val message: String) : Connection

    /**
     * M5: the server responds but it is not 2.x. Blocking screen, no further calls
     * except for an explicit "Retry".
     */
    data class Incompatible(val found: String) : Connection
}

/** Live SSE channel state (independent from the already connected REST). */
sealed interface StreamState {
    data object Idle : StreamState
    data object Live : StreamState

    /**
     * SSE handshake in progress after a failure: no countdown, so the UI does not
     * display "retrying in 0s" while the socket waits for the response.
     */
    data class Connecting(val attempt: Int) : StreamState

    /**
     * Waiting for the next attempt. [nextAttemptAt] is the absolute instant: the
     * countdown is derived from it, never from a manually decremented counter.
     * [suspended] = network absent, attempts paused until it returns.
     */
    data class Reconnecting(
        val attempt: Int,
        val nextAttemptAt: Long,
        val suspended: Boolean = false,
    ) : StreamState
}

/**
 * Attachment ready to display in the composer. `previewUri` remains the local URI
 * (content://) for the thumbnail; the content is only sent during transmission.
 */
data class AttachmentUi(
    val name: String,
    val mime: String,
    /** Original bytes declared by the provider. */
    val sizeBytes: Long,
    val isImage: Boolean,
    val previewUri: String,
)

/**
 * Subagent just activated, shown for a few seconds above the chat. `token` changes
 * on every activation: the UI uses it as a key to restart the animation even when
 * the agent is the same.
 */
data class SubagentFlashUi(
    val agent: String,
    val title: String?,
    val token: Long,
)

/**
 * Single UI state.
 *
 * Credentials are encrypted and persisted (`TokenStore`, Keystore) for the
 * foreground service; only what is needed to render the screen remains here.
 */
data class UiState(
    val url: String = "",
    val user: String = "opencode",
    val password: String = "",
    val connection: Connection = Connection.Disconnected,
    val sessions: List<SessionUi> = emptyList(),
    val sessionsLoading: Boolean = false,
    /** Creating a new session in progress: progress and button disabled. */
    val creatingSession: Boolean = false,
    val openSession: SessionUi? = null,
    /**
     * Parent → child stack: each frame is the session that reopens with `back()`.
     * Empty = the current session was opened from the list.
     */
    val sessionStack: List<SessionFrame> = emptyList(),
    /** The open session is a child task: read-only chat. */
    val openReadOnly: Boolean = false,
    val messages: List<MessageUi> = emptyList(),
    val messagesLoading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    // M0: SSE log available as a secondary screen.
    val streaming: Boolean = false,
    val events: List<String> = emptyList(),
    // M2: send, live streaming, and reconnection.
    val draft: String = "",
    /** Recently activated subagent: the UI displays it for a couple of seconds. */
    val subagentFlash: SubagentFlashUi? = null,
    // M8: a single attachment per prompt, with its preparation/error states.
    val attachment: AttachmentUi? = null,
    val attachmentPreparing: Boolean = false,
    val attachmentError: String? = null,
    val running: Boolean = false,
    val sessionOutcome: String? = null,
    val chat: ChatStream = ChatStream(),
    val streamState: StreamState = StreamState.Idle,
    /** Seconds until the next attempt, derived from [nextAttemptAt] on each tick. */
    val reconnectInSeconds: Int = 0,
    /** Absolute time of the next SSE attempt (0 = none scheduled). */
    val nextAttemptAt: Long = 0L,
    /** No network: attempts are suspended, no number in the banner. */
    val retrySuspended: Boolean = false,
    // M3: drawer catalogs (agents, skills, MCP, models, locations).
    val catalog: Catalog = Catalog(),
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val children: List<SessionUi> = emptyList(),
    val childrenLoading: Boolean = false,
    // M4: approval requests for the open session only.
    val approvals: List<ApprovalUi> = emptyList(),
    val approvalsLoading: Boolean = false,
    val approvalsError: String? = null,
    /**
     * Presentation only: pending requestIDs per session, for list and drawer
     * badges. Not a second source of truth: it aligns with events and the GET of
     * the open session, without new network calls.
     */
    val pendingApprovals: Map<String, Set<String>> = emptyMap(),
    // M9: pending questions for the single open session (effectively one).
    val forms: List<FormUi> = emptyList(),
    val formsLoading: Boolean = false,
    val formsError: String? = null,
    // M9: slash command catalog, read from the server at runtime.
    val commands: List<CommandUi> = emptyList(),
    val commandsLoading: Boolean = false,
    val commandsError: String? = null,
) {
    val isConnected: Boolean get() = connection is Connection.Connected

    /** At least one parent is stacked: back returns to it instead of the list. */
    val canGoBackToParent: Boolean get() = SessionNav.canGoBackToParent(sessionStack)

    val liveMessage: LiveMessage? get() = chat.live

    /** Badge: pending requests noted for a session. */
    fun approvalCount(sessionId: String?): Int =
        sessionId?.let { pendingApprovals[it]?.size } ?: 0

    /** Drawer badge: pending requests across all known sessions. */
    val approvalCountTotal: Int get() = pendingApprovals.values.sumOf { it.size }

    /**
     * Presentation helper: child tasks with the observed activity on sessions.
     * `GET /api/session?parentID=` does not carry a live state, while SSE events
     * update the `sessions` rows (children also appear in the top-level list):
     * here the two views are reconciled for the task badge and the "active" dot
     * on the rows.
     */
    val childrenWithActivity: List<SessionUi>
        get() = children.map { child ->
            val running = child.isActive || sessions.any { it.id == child.id && it.isActive }
            if (running == child.isActive) child else child.copy(isActive = running)
        }

    /** Running children, for the drawer badge. */
    val runningChildren: Int get() = childrenWithActivity.count { it.isActive }
}

/** Results of the five parallel calls to the M3 catalog. */
private data class CatalogFetch(
    val agents: Result<List<AgentUi>>,
    val skills: Result<List<SkillUi>>,
    val mcps: Result<List<McpUi>>,
    val models: Result<List<ModelUi>>,
    val location: Result<LocationUi?>,
)

// --- M4: session badge (presentation-only map) --------------------

private fun Map<String, Set<String>>.withSessionApprovals(
    sessionId: String,
    ids: Collection<String>,
): Map<String, Set<String>> {
    val set = ids.toSet()
    return if (set.isEmpty()) this - sessionId else this + (sessionId to set)
}

private fun Map<String, Set<String>>.withoutApproval(
    requestId: String,
    sessionId: String? = null,
): Map<String, Set<String>> {
    if (sessionId != null) {
        val remaining = this[sessionId].orEmpty() - requestId
        return if (remaining.isEmpty()) this - sessionId else this + (sessionId to remaining)
    }
    return mapValues { (_, ids) -> ids - requestId }
        .filterValues { it.isNotEmpty() }
}

/**
 * Prefers [session] over the deduplicated list: same schema as the notification
 * open (`openSessionRow`). Pure function for JVM tests, since the ViewModel cannot
 * be instantiated without Android.
 */
internal fun prependSession(sessions: List<SessionUi>, session: SessionUi): List<SessionUi> =
    if (sessions.any { it.id == session.id }) sessions else listOf(session) + sessions

/**
 * Chat snapshot ready for the UI: what [PublishGate] accumulates and publishes.
 * Contains the reduced state, not individual events.
 */
internal data class ChatSnapshot(
    val sessionId: String,
    val messages: List<MessageUi>,
    val stream: ChatStream,
    val running: Boolean,
    val outcome: String?,
)


class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val tokenStore = TokenStore(application)

    /** Encrypted credentials: reconnects on first launch without prompting. */
    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            onProcessForeground()
        }
    }

    /** REST client; standard timeouts. */
    private val restClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** SSE client: no read timeout (the stream stays open). */
    private val sseClient = restClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var api: OpenCodeApi? = null
    private var stream: EventStream? = null
    private var streamJob: Job? = null
    private var streamGeneration = 0

    /** Cursor to load older messages from the open session. */
    private var olderCursor: String? = null

    /** Dedupe SSE events by `event.id` (bounded). */
    private val seenEventIds = LinkedHashSet<String>()

    private var optimisticSeq = 0

    /** Subagent flash: one job at a time, the last one wins. */
    private var flashJob: Job? = null
    private var flashToken = 0L

    /** Children already "flashed": `session.updated` keeps arriving on the same one. */
    private val flashedChildren = LinkedHashSet<String>()

    /** Timestamp of the last successful `GET /api/command`: catalog TTL. */
    private var commandsLoadedAt = 0L

    /**
     * Already read/compressed attachment, kept alongside [AttachmentUi]: sending
     * does not re-read the file and does not recompress it.
     */
    private var preparedAttachment: PromptFile? = null

    /** In progress: cancellable with `clearAttachment()`. */
    private var attachJob: Job? = null

    /**
     * §4.4: deltas arrive in bursts; the reduced state is always complete, but the
     * UI receives a snapshot at most every [CHAT_PUBLISH_INTERVAL_MS], with
     * phase/end-of-turn events delivered immediately.
     */
    private val chatGate = PublishGate<ChatSnapshot>(CHAT_PUBLISH_INTERVAL_MS)
    private var chatFlushJob: Job? = null

    /** M5: last signal received on the SSE (open or event). */
    @Volatile
    private var lastSseActivityAt = 0L

    /**
     * M5 F1: the last reason a REST declared the server unreachable. When the SSE
     * shows signs of life again, the error is removed only if it is still that one
     * (not an error from an action).
     */
    @Volatile
    private var lastOfflineReason: String? = null

    /** M5.1: network down — SSE attempts remain pending until it recovers. */
    @Volatile
    private var networkAbsent = false

    /** M5.1: pure retry state, shared with the SSE loop. */
    @Volatile
    private var retryState = RetryState()

    private val connectivity = application.getSystemService(Context.CONNECTIVITY_SERVICE)
        as? ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Session to open after tapping a notification, pending connection. */
    private var pendingSessionId: String? = null

    init {
        registerNetworkCallback()
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
        viewModelScope.launch {
            ApprovalIntentRouter.sessionId.collect { sessionId ->
                if (sessionId != null) {
                    pendingSessionId = sessionId
                    maybeOpenPendingSession()
                    ApprovalIntentRouter.consume()
                }
            }
        }
        val saved = tokenStore.load()
        if (saved != null) {
            _state.update {
                it.copy(url = saved.baseUrl, user = saved.user, password = saved.password)
            }
            if (tokenStore.isAuthRejected()) {
                // 401 already seen: the token remains to precompile the setup, but
                // no automatic retry until the user re-pairs.
                _state.update {
                    it.copy(
                        connection = Connection.Error(ConnectionErrors.CREDENTIALS_REJECTED),
                        error = "${ConnectionErrors.CREDENTIALS_REJECTED} · redo the pairing",
                    )
                }
            } else {
                connectInternal(
                    url = saved.baseUrl,
                    user = saved.user,
                    password = saved.password,
                    persist = false,
                )
            }
        }
    }

    // --- M5.1: real network (ConnectivityManager) -----------------------------

    /**
     * Registers the callback on the default network. In airplane mode, the SSE
     * socket can hang for minutes: this callback is the immediate source used to
     * switch to "no network" and suspend retries. The foreground service already
     * has its own SSE watcher: no duplication here.
     */
    private fun registerNetworkCallback() {
        val manager = connectivity ?: return
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (hasInternet(manager)) onNetworkAvailable()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    onNetworkAvailable()
                }
            }

            override fun onLost(network: Network) {
                onNetworkLost()
            }

            override fun onUnavailable() {
                onNetworkLost()
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun unregisterNetworkCallback() {
        val manager = connectivity ?: return
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    private fun hasInternet(manager: ConnectivityManager): Boolean {
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Network lost: immediately close the hung socket and suspended retry. */
    private fun onNetworkLost() {
        viewModelScope.launch {
            if (!_state.value.isConnected || networkAbsent) return@launch
            networkAbsent = true
            // The SSE collect won't detect the loss until the socket goes silent
            // (30 s watchdog): it closes immediately.
            streamGeneration += 1
            streamJob?.cancel()
            streamJob = null
            markOffline(ReconnectPolicy.NETWORK_ABSENT)
        }
    }

    /**
     * Network restored: reset the backoff and resume with a single reconnection.
     * Duplicate callbacks do not trigger a second restart.
     */
    private fun onNetworkAvailable() {
        viewModelScope.launch {
            if (!networkAbsent && !_state.value.retrySuspended) return@launch
            networkAbsent = false
            val now = System.currentTimeMillis()
            val resumed = RetryTransitions.networkAvailable(retryState, now)
            retryState = resumed ?: RetryTransitions.clear()
            lastOfflineReason = null
            _state.update { current ->
                current.copy(
                    nextAttemptAt = 0L,
                    reconnectInSeconds = 0,
                    retrySuspended = false,
                    error = if (current.error == ReconnectPolicy.NETWORK_ABSENT) null else current.error,
                )
            }
            if (!_state.value.isConnected) return@launch
            // One pass: the previous job was closed on loss.
            stopStreamInternal()
            startStreamInternal()
            if (_state.value.openSession != null) {
                resyncOpenSession()
                refreshApprovals()
                refreshForms()
            }
            appendLog("net", "network available · reconnecting")
        }
    }

    fun onUrlChange(value: String) = _state.update { it.copy(url = value) }

    fun onUserChange(value: String) = _state.update { it.copy(user = value) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value) }

    fun onDraftChange(value: String) = _state.update { it.copy(draft = value) }

    // --- M8: allegati -------------------------------------------------------

    /**
     * Prepare the selected attachment (read, resize, cap) and expose it in state.
     * One attachment per message: the previous one is replaced.
     */
    fun attach(uri: Uri) {
        attachJob?.cancel()
        preparedAttachment = null
        _state.update {
            it.copy(
                attachment = null,
                attachmentPreparing = true,
                attachmentError = null,
            )
        }
        val preparer = AttachmentPreparer(getApplication())
        attachJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { preparer.prepare(uri) }
            when (result) {
                is AttachmentResult.Ok -> {
                    preparedAttachment = result.file
                    _state.update {
                        it.copy(
                            attachment = result.ui,
                            attachmentPreparing = false,
                            attachmentError = null,
                        )
                    }
                }

                AttachmentResult.TooLarge ->
                    failAttachment("file too large: maximum 50 MB")

                AttachmentResult.NotReadable ->
                    failAttachment("cannot read the file")

                is AttachmentResult.TooLargeAfterCompression -> failAttachment(
                    if (result.isImage) {
                        "image too large: maximum 4 MB after compression"
                    } else {
                        "the server accepts up to 4 MB for non-image files"
                    },
                )
            }
        }
    }

    /** Removes attachment and error; also cancels an in-progress preparation. */
    fun clearAttachment() {
        attachJob?.cancel()
        attachJob = null
        preparedAttachment = null
        _state.update {
            it.copy(
                attachment = null,
                attachmentPreparing = false,
                attachmentError = null,
            )
        }
    }

    fun dismissAttachmentError() = _state.update { it.copy(attachmentError = null) }

    private fun failAttachment(message: String) {
        preparedAttachment = null
        _state.update {
            it.copy(
                attachment = null,
                attachmentPreparing = false,
                attachmentError = message,
            )
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Connection + `GET /api/info` + `GET /api/session`, then stream and list. */
    fun connect(url: String, user: String, password: String) {
        connectInternal(url, user, password, persist = true)
    }

    /**
     * [persist] saves encrypted credentials after an explicit and successful
     * `connect()`. On startup, auto-connect uses `persist = false`.
     */
    private fun connectInternal(
        url: String,
        user: String,
        password: String,
        persist: Boolean,
    ) {
        val base = url.trim().trimEnd('/')
        _state.update {
            it.copy(
                url = url,
                user = user,
                password = password,
                connection = Connection.Connecting,
                error = null,
            )
        }

        if (!base.startsWith("https://", ignoreCase = true)) {
            setConnectionError("Invalid URL: only https:// is allowed")
            return
        }
        if (user.isBlank()) {
            setConnectionError("Missing user")
            return
        }
        if (password.isEmpty()) {
            setConnectionError("Missing password/token")
            return
        }

        stopStreamInternal()
        api = null
        stream = null
        resetSeenEventIds()
        registerNetworkCallback()
        networkAbsent = false
        retryState = RetryTransitions.clear()

        val client = OpenCodeApi(restClient, base, user, password)

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.info() }
            }
            result
                .onSuccess { info ->
                    when (val verdict = ServerVersion.check(info.version)) {
                        is VersionCheck.Unsupported -> {
                            // Blocking version: no list, no stream, no service.
                            // Only the mismatch screen.
                            api = null
                            stream = null
                            stopStreamInternal()
                            stopApprovalService()
                            ApprovalNotifier.cancelAll(getApplication())
                            _state.update {
                                it.copy(
                                    connection = Connection.Incompatible(verdict.found),
                                    error = ServerVersion.unsupportedMessage(verdict.found),
                                )
                            }
                        }

                        else -> {
                            // Supported or Unknown (raw version displayed).
                            if (persist) {
                                tokenStore.save(StoredCredentials(base, user, password))
                            }
                            api = client
                            stream = EventStream(sseClient, client.eventUrl(), client.authHeader())
                            resetChatPublish()
                            commandsLoadedAt = 0L
                            _state.update {
                                it.copy(
                                    connection = Connection.Connected(info.version, info.pid),
                                    openSession = null,
                                    sessionStack = SessionNav.cleared(),
                                    openReadOnly = false,
                                    messages = emptyList(),
                                    hasMore = false,
                                    running = false,
                                    sessionOutcome = null,
                                    chat = ChatStream(),
                                    children = emptyList(),
                                    childrenLoading = false,
                                    approvals = emptyList(),
                                    approvalsLoading = false,
                                    approvalsError = null,
                                    pendingApprovals = emptyMap(),
                                    forms = emptyList(),
                                    formsLoading = false,
                                    formsError = null,
                                    commands = emptyList(),
                                    commandsLoading = false,
                                    commandsError = null,
                                    error = null,
                                )
                            }
                            loadSessions(client)
                            loadCatalog(force = true)
                            loadCommands(force = true)
                            startStreamInternal()
                            startApprovalService()
                            maybeOpenPendingSession()
                        }
                    }
                }
                .onFailure { error ->
                    api = null
                    stream = null
                    if (error is ApiException && error.code == 401) {
                        onUnauthorized()
                        return@onFailure
                    }
                    setConnectionError(describeConnectionError(error))
                }
        }
    }

    /** "Retry" from the incompatible version screen (explicit request). */
    fun retryConnect() {
        val current = _state.value
        connectInternal(
            url = current.url,
            user = current.user,
            password = current.password,
            persist = true,
        )
    }

    fun disconnect() {
        stopStreamInternal()
        stopApprovalService()
        tokenStore.clear()
        unregisterNetworkCallback()
        attachJob?.cancel()
        attachJob = null
        preparedAttachment = null
        networkAbsent = false
        retryState = RetryTransitions.clear()
        api = null
        stream = null
        olderCursor = null
        lastOfflineReason = null
        resetSeenEventIds()
        _state.update {
            it.copy(
                connection = Connection.Disconnected,
                sessions = emptyList(),
                sessionsLoading = false,
                openSession = null,
                sessionStack = SessionNav.cleared(),
                openReadOnly = false,
                messages = emptyList(),
                messagesLoading = false,
                hasMore = false,
                error = null,
                events = emptyList(),
                draft = "",
                subagentFlash = null,
                attachment = null,
                attachmentPreparing = false,
                attachmentError = null,
                running = false,
                sessionOutcome = null,
                chat = ChatStream(),
                streamState = StreamState.Idle,
                reconnectInSeconds = 0,
                nextAttemptAt = 0L,
                retrySuspended = false,
                catalog = Catalog(),
                catalogLoading = false,
                catalogError = null,
                children = emptyList(),
                childrenLoading = false,
                approvals = emptyList(),
                approvalsLoading = false,
                approvalsError = null,
                pendingApprovals = emptyMap(),
            )
        }
    }

    fun refreshSessions() {
        val client = api
        if (client == null) {
            _state.update { it.copy(error = "not connected") }
            return
        }
        viewModelScope.launch { loadSessions(client) }
    }

    /**
     * Creates a session with the server defaults and immediately opens it in chat,
     * placing it at the top of the list like a notification open. On error, the
     * list remains unchanged and the message is routed to `error`.
     */
    fun createSession() {
        val client = api
        if (client == null) {
            _state.update { it.copy(error = "not connected") }
            return
        }
        if (_state.value.creatingSession) return
        _state.update { it.copy(creatingSession = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.createSession() }
            }
            result
                .onSuccess { session ->
                    _state.update { it.copy(creatingSession = false) }
                    openSessionRow(session)
                }
                .onFailure { error ->
                    _state.update { it.copy(creatingSession = false) }
                    handleActionError(error)
                }
        }
    }

    /**
     * "Retry" from the list: immediately re-reads the list and, if the SSE was
     * stalled, restarts it (a hung socket produces neither events nor failures).
     * During reconnection, the existing job remains: the backoff is not forced.
     */
    fun retryConnection() {
        val client = api
        if (client == null) {
            _state.update { it.copy(error = "not connected") }
            return
        }
        viewModelScope.launch { loadSessions(client) }
        when (_state.value.streamState) {
            StreamState.Idle, is StreamState.Connecting -> {
                if (streamJob?.isActive == true) stopStreamInternal()
                startStreamInternal()
            }
            // While waiting for backoff or with the network absent, the existing
            // job remains: no forced restart.
            else -> Unit
        }
    }

    /**
     * Opens a session. From the list, it restarts with an empty stack; if a session
     * is already open, the current one is pushed onto the stack and the new one
     * becomes a read-only child task. Same id = refresh: no duplicates in the
     * stack.
     */
    fun openSession(id: String) {
        val client = api
        if (client == null) {
            _state.update { it.copy(error = "not connected") }
            return
        }
        val current = _state.value.openSession?.let { open ->
            SessionFrame(open, readOnly = _state.value.openReadOnly)
        }
        val next = resolvedSession(
            id = id,
            fallback = _state.value.sessions.firstOrNull { it.id == id }
                ?: SessionUi(id = id, title = id),
        )
        val plan = SessionNav.planOpen(
            stack = _state.value.sessionStack,
            current = current,
            nextSession = next,
        )
        openSessionInternal(
            client = client,
            session = next,
            stack = plan.stack,
            readOnly = plan.readOnly,
        )
    }

    /**
     * Reopens the stacked parent (re-reading messages from the server) or returns
     * to the list if there is no parent.
     */
    fun back() {
        val popped = SessionNav.pop(_state.value.sessionStack)
        val frame = popped.frame
        if (frame == null) {
            backToList()
            return
        }
        val client = api
        if (client == null) {
            backToList()
            return
        }
        openSessionInternal(
            client = client,
            session = resolvedSession(frame.session.id, frame.session),
            stack = popped.stack,
            readOnly = frame.readOnly,
        )
    }

    /**
     * Opens [session] with [stack]; [readOnly] enables the view without the
     * composer.
     */
    private fun openSessionInternal(
        client: OpenCodeApi,
        session: SessionUi,
        stack: List<SessionFrame>,
        readOnly: Boolean,
    ) {
        val id = session.id
        olderCursor = null
        resetChatPublish()
        _state.update {
            it.copy(
                openSession = session,
                sessionStack = stack,
                openReadOnly = readOnly,
                messages = emptyList(),
                messagesLoading = true,
                hasMore = false,
                error = null,
                running = session.isActive,
                sessionOutcome = session.outcome,
                chat = ChatStream(),
                children = emptyList(),
                childrenLoading = false,
                approvals = emptyList(),
                approvalsLoading = false,
                approvalsError = null,
                forms = emptyList(),
                formsLoading = false,
                formsError = null,
            )
        }
        refreshApprovals()
        refreshForms()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.messages(id, limit = 100) }
            }
            result
                .onSuccess { page ->
                    if (_state.value.openSession?.id != id) return@onSuccess
                    olderCursor = page.previous
                    _state.update {
                        it.copy(
                            messages = page.messages.distinctBy { m -> m.id },
                            messagesLoading = false,
                            hasMore = page.previous != null,
                        )
                    }
                }
                .onFailure { error ->
                    if (_state.value.openSession?.id != id) return@onFailure
                    if (error is ApiException && error.code == 401) {
                        onUnauthorized()
                        return@onFailure
                    }
                    if (error is ApiException && error.code == 404) {
                        sessionGone(id)
                        return@onFailure
                    }
                    _state.update { it.copy(messagesLoading = false) }
                    reportFailure(error)
                }
        }
    }

    /**
     * The open session no longer exists on the server. If it is the parent session
     * (opened from the list), return to the list with "session no longer
     * available"; for a child session, automatically return to the parent.
     */
    private fun sessionGone(id: String) {
        if (_state.value.openSession?.id != id) return
        if (!_state.value.openReadOnly) {
            backToList()
            _state.update { it.copy(error = "session no longer available") }
            return
        }
        val popped = SessionNav.pop(_state.value.sessionStack)
        val frame = popped.frame
        val client = api
        if (frame != null && client != null) {
            openSessionInternal(
                client = client,
                session = resolvedSession(frame.session.id, frame.session),
                stack = popped.stack,
                readOnly = frame.readOnly,
            )
            _state.update {
                it.copy(error = "session not found · you returned to the parent session")
            }
        } else {
            backToList()
            _state.update { it.copy(error = "session no longer available") }
        }
    }

    /** Use the most recent row from the list when available, otherwise fall back. */
    private fun resolvedSession(id: String, fallback: SessionUi): SessionUi =
        _state.value.sessions.firstOrNull { it.id == id } ?: fallback

    /** Loads the previous page and prepends it (uses `cursor.previous`). */
    fun loadOlder() {
        val client = api ?: return
        flushChatNow()
        val session = _state.value.openSession ?: return
        val cursor = olderCursor ?: return
        if (_state.value.messagesLoading) return

        _state.update { it.copy(messagesLoading = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.messages(session.id, cursor = cursor) }
            }
            result
                .onSuccess { page ->
                    if (_state.value.openSession?.id != session.id) return@onSuccess
                    flushChatNow()
                    olderCursor = page.previous
                    _state.update { current ->
                        current.copy(
                            messages = (page.messages + current.messages)
                                .distinctBy { m -> m.id },
                            messagesLoading = false,
                            hasMore = page.previous != null,
                        )
                    }
                }
                .onFailure { error ->
                    if (_state.value.openSession?.id != session.id) return@onFailure
                    if (error is ApiException && error.code == 401) {
                        onUnauthorized()
                        return@onFailure
                    }
                    if (error is ApiException && error.code == 404) {
                        sessionGone(session.id)
                        return@onFailure
                    }
                    if (error is ApiException && error.code == 400) {
                        // Expired cursor: dedicated message and realignment.
                        _state.update {
                            it.copy(
                                messagesLoading = false,
                                error = describeConnectionError(error, ErrorContext.Pagination),
                            )
                        }
                        resyncOpenSession()
                        return@onFailure
                    }
                    _state.update { it.copy(messagesLoading = false) }
                    reportFailure(error)
                }
        }
    }

    fun backToList() {
        olderCursor = null
        resetChatPublish()
        flashJob?.cancel()
        flashJob = null
        flashedChildren.clear()
        _state.update {
            it.copy(
                openSession = null,
                sessionStack = SessionNav.cleared(),
                openReadOnly = false,
                messages = emptyList(),
                messagesLoading = false,
                hasMore = false,
                error = null,
                running = false,
                sessionOutcome = null,
                chat = ChatStream(),
                children = emptyList(),
                childrenLoading = false,
                approvals = emptyList(),
                approvalsLoading = false,
                approvalsError = null,
                forms = emptyList(),
                formsLoading = false,
                formsError = null,
            )
        }
    }

    // --- M4: approvazioni ---------------------------------------------------

    /** Alignment with `GET …/permission` for the open session. */
    fun refreshApprovals() {
        val client = api ?: return
        val sessionId = _state.value.openSession?.id ?: return
        _state.update { it.copy(approvalsLoading = true, approvalsError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.permissions(sessionId) }
            }
            result
                .onSuccess { list ->
                    if (_state.value.openSession?.id != sessionId) return@onSuccess
                    _state.update {
                        it.copy(
                            approvals = list,
                            approvalsLoading = false,
                            pendingApprovals = it.pendingApprovals.withSessionApprovals(
                                sessionId,
                                list.map { approval -> approval.id },
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    if (_state.value.openSession?.id != sessionId) return@onFailure
                    _state.update {
                        it.copy(
                            approvalsLoading = false,
                            approvalsError = describeApprovalError(error),
                        )
                    }
                    if (error is ApiException && error.code == 401) {
                        onUnauthorized()
                    }
                }
        }
    }

    /**
     * Response to a request in the open session. On success, the request is removed
     * from state; if it has expired, the dedicated message is shown and the GET is
     * retried (API-M4 contract).
     */
    fun replyApproval(id: String, decision: String, message: String? = null) {
        val client = api ?: return
        val sessionId = _state.value.openSession?.id ?: return
        if (decision != "once" && decision != "always" && decision != "reject") return
        // A new submission clears the previous error: the UI can show the action
        // spinner and retry even after the same error.
        _state.update { it.copy(approvalsError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.replyPermission(sessionId, id, decision, message) }
            }
            result
                .onSuccess {
                    ApprovalNotifier.cancelFor(getApplication<Application>(), id)
                    _state.update { current ->
                        current.copy(
                            approvals = current.approvals.filterNot { it.id == id },
                            approvalsError = null,
                            pendingApprovals = current.pendingApprovals.withoutApproval(id),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is ApprovalExpiredException) {
                        _state.update {
                            it.copy(
                                approvals = it.approvals.filterNot { a -> a.id == id },
                                error = "request no longer valid",
                                approvalsError = "request no longer valid",
                                pendingApprovals = it.pendingApprovals.withoutApproval(id),
                            )
                        }
                        refreshApprovals()
                    } else if (error is ApiException && error.code == 401) {
                        onUnauthorized()
                    } else {
                        _state.update {
                            it.copy(approvalsError = describeApprovalError(error))
                        }
                    }
                }
        }
    }

    /** Return to foreground: realign the approvals of the open session. */
    fun onAppForeground() {
        onProcessForeground()
    }

    // --- M9: server questions (form) --------------------------------------

    /** Alignment with `GET /api/form` for the open session. */
    fun refreshForms() {
        val client = api ?: return
        val sessionId = _state.value.openSession?.id ?: return
        _state.update { it.copy(formsLoading = true, formsError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.forms(sessionId) }
            }
            result
                .onSuccess { list ->
                    if (_state.value.openSession?.id != sessionId) return@onSuccess
                    _state.update {
                        it.copy(forms = list.distinctBy { form -> form.id }, formsLoading = false)
                    }
                }
                .onFailure { error ->
                    if (_state.value.openSession?.id != sessionId) return@onFailure
                    _state.update {
                        it.copy(
                            formsLoading = false,
                            formsError = describeFormError(error),
                        )
                    }
                    if (error is ApiException && error.code == 401) onUnauthorized()
                }
        }
    }

    /**
     * Response to the question in the open session. On 404 the question has
     * expired: it is removed from state, a short message is sent, and a resync is
     * performed.
     */
    fun replyForm(formId: String, answer: Map<String, FormValue>) {
        val client = api ?: return
        val sessionId = _state.value.openSession?.id ?: return
        _state.update { it.copy(formsError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.replyForm(sessionId, formId, answer) }
            }
            result
                .onSuccess {
                    _state.update { current ->
                        current.copy(forms = current.forms.filterNot { it.id == formId })
                    }
                }
                .onFailure { error ->
                    if (error is ApiException && error.code == 401) {
                        onUnauthorized()
                        return@onFailure
                    }
                    if (error is ApiException && error.code == 404) {
                        // The question no longer exists: without the form, the
                        // panel disappears, so the message goes into the general
                        // banner (formsError would have nowhere to display).
                        _state.update { current ->
                            current.copy(
                                forms = current.forms.filterNot { it.id == formId },
                                formsError = null,
                                error = "question expired",
                            )
                        }
                        refreshForms()
                        return@onFailure
                    }
                    _state.update { it.copy(formsError = describeFormError(error)) }
                }
        }
    }

    /** Cancel the request (`DELETE`); on 404 it was already gone, so resync. */
    fun cancelForm(formId: String) {
        val client = api ?: return
        val sessionId = _state.value.openSession?.id ?: return
        _state.update { it.copy(formsError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.cancelForm(sessionId, formId) }
            }
            result
                .onSuccess {
                    _state.update { current ->
                        current.copy(forms = current.forms.filterNot { it.id == formId })
                    }
                }
                .onFailure { error ->
                    if (error is ApiException && error.code == 401) {
                        onUnauthorized()
                        return@onFailure
                    }
                    if (error is ApiException && error.code == 404) {
                        _state.update { current ->
                            current.copy(forms = current.forms.filterNot { it.id == formId })
                        }
                        refreshForms()
                        return@onFailure
                    }
                    _state.update { it.copy(formsError = describeFormError(error)) }
                }
        }
    }

    private fun describeFormError(error: Throwable): String = when {
        error is ApiException && error.code == 401 -> "credentials rejected"
        error is ApiException && error.code == 404 -> "question expired"
        else -> describeConnectionError(error)
    }

    /**
     * M5 (Doze/foreground): if the SSE has shown no signs of life for 30 s or the
     * job is not active, force a reconnection and realign the open session.
     * `startStreamInternal` already has a guard on the job: never two streams. If
     * the network is absent, nothing is forced: pending attempts remain.
     */
    private fun onProcessForeground() {
        if (!_state.value.isConnected) return
        if (networkAbsent) {
            // If the callback was lost, the foreground is the recovery point: the
            // network is back → restart immediately.
            if (connectivity?.let { hasInternet(it) } == true) {
                onNetworkAvailable()
            } else {
                refreshApprovals()
                refreshForms()
                startApprovalService()
            }
            return
        }
        val now = System.currentTimeMillis()
        val alive = streamJob?.isActive == true
        val fresh = lastSseActivityAt > 0L && now - lastSseActivityAt <= SSE_STALE_MS
        if (!alive || !fresh) {
            if (alive) stopStreamInternal()
            startStreamInternal()
            resyncOpenSession()
        }
        refreshApprovals()
        refreshForms()
        startApprovalService()
    }

    private fun maybeOpenPendingSession() {
        val id = pendingSessionId ?: return
        if (!_state.value.isConnected) return
        pendingSessionId = null
        openSession(id)
    }

    private fun startApprovalService() {
        try {
            val context = getApplication<Application>()
            context.startForegroundService(Intent(context, ApprovalService::class.java))
        } catch (_: Exception) {
            // If the system denies startup (e.g., background limits), the UI
            // remains ok.
        }
    }

    private fun stopApprovalService() {
        try {
            val context = getApplication<Application>()
            context.stopService(Intent(context, ApprovalService::class.java))
        } catch (_: Exception) {
            // No services to stop.
        }
    }

    private fun describeApprovalError(error: Throwable): String = when {
        error is ApprovalExpiredException -> "request no longer valid"
        error is ApiException && error.code == 401 -> "credentials rejected"
        error is ApiException && error.code == 404 -> "session not found"
        else -> describeConnectionError(error)
    }

    // --- M3: catalogs and session actions ---------------------------------

    /**
     * Load agents/skill/MCP/models/location in parallel. Respect the 5-minute TTL
     * unless [force] is set. If a call fails, the previous catalog remains active
     * and the error is stored in `catalogError`.
     */
    fun loadCatalog(force: Boolean = false) {
        val client = api
        if (client == null) {
            _state.update { it.copy(catalogError = "not connected") }
            return
        }
        val now = System.currentTimeMillis()
        if (!force && _state.value.catalog.isFresh(now)) return
        if (_state.value.catalogLoading) return

        _state.update { it.copy(catalogLoading = true, catalogError = null) }

        viewModelScope.launch {
            val fetched = withContext(Dispatchers.IO) {
                coroutineScope {
                    val agents = async { runCatching { client.agents() } }
                    val skills = async { runCatching { client.skills() } }
                    val mcps = async { runCatching { client.mcps() } }
                    val models = async { runCatching { client.models() } }
                    val location = async { runCatching { client.location() } }
                    CatalogFetch(
                        agents = agents.await(),
                        skills = skills.await(),
                        mcps = mcps.await(),
                        models = models.await(),
                        location = location.await(),
                    )
                }
            }

            val old = _state.value.catalog
            val failures = listOf(
                fetched.agents,
                fetched.skills,
                fetched.mcps,
                fetched.models,
                fetched.location,
            ).filter { it.isFailure }
            val next = Catalog(
                agents = fetched.agents.getOrElse { old.agents },
                skills = fetched.skills.getOrElse { old.skills },
                mcps = fetched.mcps.getOrElse { old.mcps },
                models = fetched.models.getOrElse { old.models },
                location = fetched.location.getOrElse { old.location },
                loadedAt = if (failures.isEmpty()) now else old.loadedAt,
            )
            _state.update {
                it.copy(
                    catalog = next,
                    catalogLoading = false,
                    catalogError = failures.firstOrNull()?.exceptionOrNull()
                        ?.let(::describeConnectionError),
                )
            }

            val unauthorized = failures.any { failure ->
                (failure.exceptionOrNull() as? ApiException)?.code == 401
            }
            if (unauthorized) onUnauthorized()
        }
    }

    /**
     * Slash command catalog with a 5-minute TTL; [force] reloads manually. An error
     * does not clear the previous catalog.
     */
    fun loadCommands(force: Boolean = false) {
        val client = api
        if (client == null) {
            _state.update { it.copy(commandsError = "not connected") }
            return
        }
        val now = System.currentTimeMillis()
        if (!force && commandsLoadedAt > 0L && now - commandsLoadedAt < COMMANDS_TTL_MS) return
        if (_state.value.commandsLoading) return

        _state.update { it.copy(commandsLoading = true, commandsError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.commands() }
            }
            result
                .onSuccess { list ->
                    commandsLoadedAt = System.currentTimeMillis()
                    _state.update {
                        it.copy(commands = list, commandsLoading = false, commandsError = null)
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            commandsLoading = false,
                            commandsError = describeConnectionError(error),
                        )
                    }
                    if (error is ApiException && error.code == 401) onUnauthorized()
                }
        }
    }

    /** Switch agent on the open session; then reload the session from the server. */
    fun switchAgent(agentId: String) {
        val client = api
        val session = _state.value.openSession
        if (client == null) {
            _state.update { it.copy(error = "not connected") }
            return
        }
        if (session == null) {
            _state.update { it.copy(error = "open a session to change agent") }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SessionCommands.switchAgent(client, session.id, agentId) }
            }
            result
                .onSuccess { updated -> applyServerSession(updated) }
                .onFailure(::handleActionError)
        }
    }

    /**
     * Change the model on the open session; then reload the session from the
     * server.
     */
    fun switchModel(providerId: String, modelId: String, variant: String?) {
        val client = api
        val session = _state.value.openSession
        if (client == null) {
            _state.update { it.copy(error = "not connected") }
            return
        }
        if (session == null) {
            _state.update { it.copy(error = "open a session to change model") }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    SessionCommands.switchModel(
                        client,
                        session.id,
                        providerId,
                        modelId,
                        variant,
                    )
                }
            }
            result
                .onSuccess { updated -> applyServerSession(updated) }
                .onFailure(::handleActionError)
        }
    }

    /** Activates a skill in the open session without assuming local effects. */
    fun activateSkill(skillId: String) {
        val client = api
        if (client == null) {
            _state.update { it.copy(error = "not connected") }
            return
        }
        val session = _state.value.openSession
        if (session == null) {
            _state.update { it.copy(error = "open a session to activate a skill") }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.activateSkill(session.id, skillId) }
            }
            result.onFailure(::handleActionError)
        }
    }

    /**
     * Presentation helper (no network calls): opens a session that the drawer
     * recognizes as a row — for example, a background task — but which may not yet
     * be in the main list. If missing, it adds it to the top so the title and meta
     * remain correct; `refreshSessions` then realigns the list with the server.
     */
    fun openSessionRow(session: SessionUi) {
        _state.update { current ->
            current.copy(sessions = prependSession(current.sessions, session))
        }
        openSession(session.id)
    }

    /** Children of the open session (background tasks). */
    fun refreshChildren() {
        val client = api
        if (client == null) {
            _state.update { it.copy(error = "not connected") }
            return
        }
        val session = _state.value.openSession
        if (session == null) {
            _state.update { it.copy(children = emptyList(), childrenLoading = false) }
            return
        }
        _state.update { it.copy(childrenLoading = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.children(session.id) }
            }
            result
                .onSuccess { children ->
                    if (_state.value.openSession?.id != session.id) return@onSuccess
                    _state.update { it.copy(children = children, childrenLoading = false) }
                }
                .onFailure { error ->
                    if (_state.value.openSession?.id != session.id) return@onFailure
                    _state.update { it.copy(childrenLoading = false) }
                    handleActionError(error)
                }
        }
    }

    /**
     * Apply the session returned by `GET /api/session/{id}` to the row in the list
     * and the open one, preserving the local `isActive` state.
     */
    private fun applyServerSession(updated: SessionUi) {
        _state.update { current ->
            val open = current.openSession?.let { session ->
                if (session.id == updated.id) {
                    updated.copy(isActive = session.isActive)
                } else {
                    session
                }
            }
            val sessions = current.sessions.map { row ->
                if (row.id == updated.id) updated.copy(isActive = row.isActive) else row
            }
            current.copy(openSession = open, sessions = sessions)
        }
    }

    private fun handleActionError(error: Throwable) {
        if (error is ApiException && error.code == 401) {
            onUnauthorized()
        } else {
            reportFailure(error)
        }
    }

    /**
     * M5 F1 / M5.1: a failed REST request due to network/timeout proves the server
     * is currently unreachable. Only "network absent" suspends attempts; other
     * errors leave the SSE backoff intact.
     */
    private fun markOffline(reason: String) {
        lastOfflineReason = reason
        val absent = ReconnectPolicy.isNetworkAbsent(reason)
        if (absent) {
            networkAbsent = true
            retryState = RetryTransitions.offline(reason, retryState)
        }
        _state.update { current ->
            when {
                absent -> current.copy(
                    streaming = false,
                    streamState = StreamState.Reconnecting(
                        attempt = 0,
                        nextAttemptAt = 0L,
                        suspended = true,
                    ),
                    nextAttemptAt = 0L,
                    retrySuspended = true,
                    reconnectInSeconds = 0,
                    error = reason,
                )

                // An ongoing reconnection is already "not live": preserve the
                // backoff countdown without forcing a new one.
                current.streamState is StreamState.Reconnecting ->
                    current.copy(streaming = false, error = reason)

                else -> current.copy(
                    streaming = false,
                    streamState = StreamState.Idle,
                    nextAttemptAt = 0L,
                    retrySuspended = false,
                    reconnectInSeconds = 0,
                    error = reason,
                )
            }
        }
        appendLog(if (absent) "net" else "rest", reason)
    }

    /**
     * M5 F1: SSE has resumed speaking (open or event): the server is reachable, so
     * any reachability error is stale and must be removed. Errors from a single
     * action remain.
     */
    private fun markLive() {
        val stale = lastOfflineReason
        lastOfflineReason = null
        networkAbsent = false
        retryState = RetryTransitions.clear()
        _state.update { current ->
            current.copy(
                streaming = true,
                streamState = StreamState.Live,
                nextAttemptAt = 0L,
                retrySuspended = false,
                reconnectInSeconds = 0,
                error = if (stale != null && current.error == stale) null else current.error,
            )
        }
    }

    /**
     * Non-401 REST error: offline if it's a network error, otherwise a normal
     * message.
     */
    private fun reportFailure(error: Throwable) {
        val reason = describeConnectionError(error)
        if (isReachabilityFailure(error)) markOffline(reason) else {
            _state.update { current -> current.copy(error = reason) }
        }
    }

    /**
     * No network, DNS, timeout, TLS, or closed socket: conditions where the server
     * is unreachable. HTTP errors (ApiException) are not: the server responded, so
     * the SSE remains as is.
     */
    private fun isReachabilityFailure(error: Throwable): Boolean = when (error) {
        is SSLException,
        is SocketTimeoutException,
        is UnknownHostException,
        is ConnectException,
        is NoRouteToHostException,
        is SocketException,
        is EOFException -> true
        is java.io.IOException -> error.javaClass.name.endsWith(".UnknownServiceException")
        else -> false
    }

    // --- M2: send, interrupt --------------------------------------------

    /** Optimistic send: the user bubble appears immediately and remains "queued". */
    fun sendPrompt() {
        val client = api
        flushChatNow()
        val session = _state.value.openSession
        val text = _state.value.draft.trim()
        val attachment = preparedAttachment
        if (client == null || session == null || (!hasSendableText(text) && attachment == null)) return
        val sentAttachment = _state.value.attachment

        val localId = "local-${++optimisticSeq}"
        val now = System.currentTimeMillis()
        _state.update { current ->
            current.copy(
                draft = "",
                error = null,
                messages = current.messages + MessageUi(
                    id = localId,
                    role = Role.User,
                    text = text,
                    createdAt = now,
                    pending = true,
                    // Immediate local thumbnail: the server will return the base64
                    // `data` on resync, no need to wait for it.
                    files = listOfNotNull(
                        sentAttachment?.let { attachment ->
                            MessageFileUi(
                                name = attachment.name,
                                mime = attachment.mime,
                                uri = attachment.previewUri,
                            )
                        },
                    ),
                ),
            )
        }

        viewModelScope.launch {
            // M9: a recognized command starts from the dedicated endpoint; the rest
            // (including an unknown command) remains a normal prompt.
            val command = SlashCommands.parse(text, _state.value.commands)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (command != null) {
                        client.runCommand(
                            sessionId = session.id,
                            name = command.name,
                            text = command.text,
                            files = listOfNotNull(attachment),
                        )
                        null
                    } else {
                        client.prompt(
                            sessionId = session.id,
                            text = text,
                            delivery = "steer",
                            files = listOfNotNull(attachment),
                        )
                    }
                }
            }
            result
                .onSuccess { serverId ->
                    // The retained deltas may still contain the optimistic message:
                    // they are published before it is renamed.
                    flushChatNow()
                    // Only if it's still the same attachment: one selected in the
                    // meantime must not be lost.
                    if (preparedAttachment === attachment) preparedAttachment = null
                    _state.update { current ->
                        val serverMessageId = serverId?.takeIf { it.isNotBlank() }
                        val updated = if (serverMessageId == null) {
                            current.messages
                        } else {
                            current.messages.map { message ->
                                if (message.id == localId) {
                                    message.copy(
                                        id = serverMessageId,
                                        pending = !current.running,
                                    )
                                } else {
                                    message
                                }
                            }.distinctBy { it.id }
                        }
                        val sameAttachment = sentAttachment != null &&
                            current.attachment?.previewUri == sentAttachment.previewUri
                        current.copy(
                            messages = updated,
                            attachment = if (sameAttachment) null else current.attachment,
                            attachmentPreparing = if (sameAttachment) {
                                false
                            } else {
                                current.attachmentPreparing
                            },
                        )
                    }
                }
                .onFailure { error ->
                    flushChatNow()
                    _state.update { current ->
                        current.copy(
                            messages = current.messages.filterNot { it.id == localId },
                            draft = if (current.draft.isBlank()) text else current.draft,
                        )
                    }
                    if (error is ApiException && error.code == 401) {
                        // No automatic prompt resubmission: avoids duplication.
                        onUnauthorized()
                    } else {
                        reportFailure(error)
                    }
                }
        }
    }

    fun interrupt() {
        val client = api ?: return
        val session = _state.value.openSession ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.interrupt(session.id) }
            }
            result.onFailure { error ->
                if (error is ApiException && error.code == 401) {
                    onUnauthorized()
                } else {
                    reportFailure(error)
                }
            }
        }
    }

    // --- stream SSE e riconnessione -----------------------------------------

    /** Start (or restart) the SSE cycle with automatic reconnection. */
    fun startStream() {
        val api = api
        if (api == null) {
            _state.update { it.copy(error = "Connect first: need a successful /api/info") }
            return
        }
        startStreamInternal()
    }

    fun stopStream() {
        stopStreamInternal()
        appendLog("system", "stream stopped")
        _state.update { it.copy(streaming = false) }
    }

    private fun startStreamInternal() {
        val eventStream = stream ?: return
        if (streamJob?.isActive == true) return

        val generation = ++streamGeneration
        streamJob = viewModelScope.launch {
            var attempt = 0
            while (isActive && generation == streamGeneration) {
                // Network absent: no attempts and no backoff until it returns.
                if (networkAbsent || retryState.suspended) {
                    showSuspended()
                    while (networkAbsent && isActive && generation == streamGeneration) {
                        delay(500L)
                    }
                    if (!isActive || generation != streamGeneration) break
                    attempt = 0
                    continue
                }

                var failureCode: Int? = null
                var failureReason: String? = null

                // Handshake in progress: no countdown (never "retrying in 0s").
                retryState = RetryTransitions.attempting(attempt)
                _state.update {
                    it.copy(
                        streaming = false,
                        streamState = StreamState.Connecting(attempt),
                        nextAttemptAt = 0L,
                        retrySuspended = false,
                        reconnectInSeconds = 0,
                    )
                }

                eventStream.events().collect { event ->
                    when (event) {
                        SseEvent.Opened -> {
                            attempt = 0
                            lastSseActivityAt = System.currentTimeMillis()
                            markLive()
                            resyncOpenSession()
                            refreshApprovals()
                            refreshForms()
                        }

                        is SseEvent.Message -> {
                            lastSseActivityAt = System.currentTimeMillis()
                            // A test event proving that the SSE is alive even
                            // though the socket had been declared dead by a REST
                            // call.
                            if (_state.value.streamState !is StreamState.Live) markLive()
                            handleRawEvent(event.data)
                        }

                        is SseEvent.Failure -> {
                            failureCode = event.httpCode
                            failureReason = event.reason
                        }

                        SseEvent.Closed -> Unit
                    }
                }

                if (!isActive || generation != streamGeneration) break

                if (failureCode == 401) {
                    // Global policy: stop stream (generation already incremented),
                    // stop service, token retained, no retry.
                    onUnauthorized()
                    break
                }

                failureReason?.let { appendLog("sse", it) }
                _state.update { it.copy(streaming = false) }

                // The network dropped during the attempt: the callback has already
                // set the suspended state, so we return to the top without backoff.
                if (networkAbsent || retryState.suspended) continue

                attempt += 1
                val now = System.currentTimeMillis()
                val delayMs = Backoff.delayMs(attempt)
                val nextAttemptAt = now + delayMs
                retryState = RetryTransitions.scheduled(attempt, delayMs, now)
                _state.update {
                    it.copy(
                        streamState = StreamState.Reconnecting(attempt, nextAttemptAt),
                        nextAttemptAt = nextAttemptAt,
                        retrySuspended = false,
                        reconnectInSeconds = ReconnectPolicy.secondsUntil(nextAttemptAt, now),
                    )
                }
                while (isActive && generation == streamGeneration) {
                    if (networkAbsent || retryState.suspended) break
                    val left = nextAttemptAt - System.currentTimeMillis()
                    if (left <= 0L) break
                    delay(minOf(left, 500L))
                    val tick = System.currentTimeMillis()
                    // Never publish "retrying in 0s": go straight to the attempt
                    // (Connecting state) without a zero frame.
                    if (nextAttemptAt - tick <= 0L) break
                    val seconds = ReconnectPolicy.secondsUntil(nextAttemptAt, tick)
                    _state.update { current ->
                        val reconnecting = current.streamState
                        if (reconnecting is StreamState.Reconnecting &&
                            reconnecting.attempt == attempt
                        ) {
                            current.copy(
                                streamState = reconnecting.copy(nextAttemptAt = nextAttemptAt),
                                nextAttemptAt = nextAttemptAt,
                                reconnectInSeconds = seconds,
                            )
                        } else {
                            current
                        }
                    }
                }
            }
            if (generation == streamGeneration) {
                _state.update { it.copy(streaming = false) }
            }
        }
    }

    /** Visible state when the network is absent: attempts halted, no numbers. */
    private fun showSuspended() {
        _state.update {
            it.copy(
                streaming = false,
                streamState = StreamState.Reconnecting(
                    attempt = 0,
                    nextAttemptAt = 0L,
                    suspended = true,
                ),
                nextAttemptAt = 0L,
                retrySuspended = true,
                reconnectInSeconds = 0,
                error = ReconnectPolicy.NETWORK_ABSENT,
            )
        }
    }

    private fun stopStreamInternal() {
        flushChatNow()
        chatGate.reset()
        streamGeneration += 1
        streamJob?.cancel()
        streamJob = null
        retryState = RetryTransitions.clear()
        _state.update {
            it.copy(
                streaming = false,
                streamState = StreamState.Idle,
                nextAttemptAt = 0L,
                retrySuspended = false,
                reconnectInSeconds = 0,
            )
        }
    }

    private fun handleRawEvent(raw: String) {
        val incoming = EventParsing.parse(raw) ?: return
        val eventId = incoming.id
        if (eventId != null) {
            if (!seenEventIds.add(eventId)) return
            if (seenEventIds.size > MAX_SEEN_EVENT_IDS) {
                val iterator = seenEventIds.iterator()
                repeat(MAX_SEEN_EVENT_IDS / 4) {
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }
        }
        // Type only: never log payload, prompt, or header.
        appendLog("event", incoming.wireType)
        applyEvent(incoming.event)
    }

    private fun applyEvent(event: ServerEvent) {
        val openId = _state.value.openSession?.id

        when (event) {
            is ServerEvent.ExecutionStarted -> updateSession(event.sessionId) {
                it.copy(isActive = true, outcome = null, updatedAt = System.currentTimeMillis())
            }

            is ServerEvent.ExecutionFinished -> updateSession(event.sessionId) {
                it.copy(
                    isActive = false,
                    outcome = event.outcome,
                    updatedAt = System.currentTimeMillis(),
                )
            }

            is ServerEvent.UsageUpdated -> updateSession(event.sessionId) {
                it.copy(
                    costUsd = event.cost ?: it.costUsd,
                    tokens = event.tokens ?: it.tokens,
                )
            }

            else -> Unit
        }

        // M4: `permission.asked` is the primary source for chat state. The per-
        // session badge updates even for sessions that are not open.
        if (event is ServerEvent.PermissionAsked) {
            _state.update { current ->
                val ids = current.pendingApprovals[event.sessionId].orEmpty() +
                    event.approval.id
                current.copy(
                    approvals = if (openId == event.sessionId) {
                        current.approvals
                            .filterNot { it.id == event.approval.id } + event.approval
                    } else {
                        current.approvals
                    },
                    approvalsLoading = if (openId == event.sessionId) {
                        false
                    } else {
                        current.approvalsLoading
                    },
                    approvalsError = if (openId == event.sessionId) {
                        null
                    } else {
                        current.approvalsError
                    },
                    pendingApprovals = current.pendingApprovals
                        .withSessionApprovals(event.sessionId, ids),
                )
            }
        }
        if (event is ServerEvent.PermissionReplied) {
            val requestId = event.requestId
            if (requestId != null) {
                _state.update { current ->
                    current.copy(
                        approvals = current.approvals.filterNot { it.id == requestId },
                        pendingApprovals = current.pendingApprovals
                            .withoutApproval(requestId, event.sessionId),
                    )
                }
            }
        }
        // M9: The questions in the open session follow the events, as do the
        // approvals.
        if (event is ServerEvent.FormCreated) {
            if (openId == event.sessionId) {
                _state.update { current ->
                    current.copy(
                        forms = current.forms.filterNot { it.id == event.form.id } + event.form,
                        formsLoading = false,
                        formsError = null,
                    )
                }
                // Payload without fields (contract more concise than expected):
                // full details arrive via resync.
                if (event.form.fields.isEmpty()) refreshForms()
            }
        }
        if (event is ServerEvent.FormReplied) {
            _state.update { current ->
                current.copy(forms = current.forms.filterNot { it.id == event.formId })
            }
        }
        if (event is ServerEvent.FormCancelled) {
            _state.update { current ->
                current.copy(forms = current.forms.filterNot { it.id == event.formId })
            }
        }
        // The GET remains as the alignment when a turn/step begins.
        if ((event is ServerEvent.ExecutionStarted || event is ServerEvent.StepStarted) &&
            openId != null && event.sessionId == openId
        ) {
            refreshApprovals()
        }

        // Subagent activated: the mother is the open session, the child arrives in
        // the event. `session.updated` repeats the same child: it is filtered out.
        if (event is ServerEvent.ChildAgentStarted && openId != null &&
            event.sessionId == openId
        ) {
            if (flashedChildren.add(event.childId)) {
                if (flashedChildren.size > MAX_FLASHED_CHILDREN) {
                    val iterator = flashedChildren.iterator()
                    iterator.next()
                    iterator.remove()
                }
                showSubagentFlash(event.agent, event.title)
            }
        }

        if (openId == null || event.sessionId != openId) return

        // §4.4: the reducer always accumulates on a full basis (pending events
        // possibly not yet published), then the gate decides when the UI sees the
        // snapshot. Phase/end-of-turn events pass through immediately.
        val base = chatGate.pending
            ?.takeIf { it.sessionId == openId }
            ?: ChatSnapshot(
                sessionId = openId,
                messages = _state.value.messages,
                stream = _state.value.chat,
                running = _state.value.running,
                outcome = _state.value.sessionOutcome,
            )
        val reduced = ChatReducer.reduce(
            OpenChatState(
                sessionId = openId,
                messages = base.messages,
                stream = base.stream,
                running = base.running,
                outcome = base.outcome,
            ),
            event,
        )
        val snapshot = ChatSnapshot(
            sessionId = openId,
            messages = reduced.messages,
            stream = reduced.stream,
            running = reduced.running,
            outcome = reduced.outcome,
        )
        val publishNow = chatGate.offer(
            value = snapshot,
            immediate = event.publishedImmediately(),
            now = System.currentTimeMillis(),
        )
        if (publishNow) {
            publishChat(snapshot)
        } else {
            scheduleChatFlush()
        }

        // Messages are the truth: after the turn ends, re-read the session.
        if (event is ServerEvent.ExecutionFinished) {
            resyncOpenSession()
            refreshApprovals()
        }
    }

    private fun resetChatPublish() {
        chatFlushJob?.cancel()
        chatFlushJob = null
        chatGate.reset()
    }

    /** Bring the reduced snapshot into the UI (a single `state` emission). */
    private fun publishChat(snapshot: ChatSnapshot) {
        chatGate.dropPending()
        _state.update { current ->
            val session = current.openSession
            if (session?.id != snapshot.sessionId) {
                current
            } else {
                current.copy(
                    messages = snapshot.messages,
                    chat = snapshot.stream,
                    running = snapshot.running,
                    sessionOutcome = snapshot.outcome,
                    openSession = session.copy(outcome = snapshot.outcome ?: session.outcome),
                )
            }
        }
    }

    /** Publish the last accumulated snapshot when the window expires. */
    private fun scheduleChatFlush() {
        if (chatFlushJob?.isActive == true) return
        chatFlushJob = viewModelScope.launch {
            val wait = chatGate.dueIn(System.currentTimeMillis())
            if (wait > 0L) delay(wait)
            chatFlushJob = null
            chatGate.takePending()?.let { publishChat(it) }
        }
    }

    /**
     * Immediately publish what the gate holds: mandatory before any mutation of
     * `messages`/`chat` that does not go through the reducer, otherwise the pending
     * snapshot would overwrite it.
     */
    private fun flushChatNow() {
        chatFlushJob?.cancel()
        chatFlushJob = null
        chatGate.takePending()?.let { publishChat(it) }
    }

    /** Show the subagent for a couple of seconds, then it disappears on its own. */
    private fun showSubagentFlash(agent: String, title: String?) {
        val token = ++flashToken
        flashJob?.cancel()
        _state.update {
            it.copy(subagentFlash = SubagentFlashUi(agent = agent, title = title, token = token))
        }
        flashJob = viewModelScope.launch {
            delay(SUBAGENT_FLASH_MS)
            _state.update { current ->
                if (current.subagentFlash?.token == token) {
                    current.copy(subagentFlash = null)
                } else {
                    current
                }
            }
        }
    }

    private fun updateSession(id: String, transform: (SessionUi) -> SessionUi) {
        _state.update { current ->
            current.copy(
                sessions = current.sessions.map { if (it.id == id) transform(it) else it },
                openSession = current.openSession?.let {
                    if (it.id == id) transform(it) else it
                },
            )
        }
    }

    private fun resyncOpenSession() {
        val client = api ?: return
        val sessionId = _state.value.openSession?.id ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.messages(sessionId, limit = 100) }
            }
            result
                .onSuccess { page ->
                    if (_state.value.openSession?.id != sessionId) return@onSuccess
                    // The merge starts from the published state: the retained
                    // deltas must be made visible first, or they will be lost.
                    flushChatNow()
                    olderCursor = page.previous
                    _state.update { current ->
                        val merged = resyncMessages(page.messages, current.messages)
                        val serverIds = page.messages.mapTo(HashSet()) { it.id }
                        current.copy(
                            messages = merged,
                            chat = current.chat.copy(
                                liveTurn = current.chat.liveTurn
                                    ?.takeIf { it.id !in serverIds },
                            ),
                            messagesLoading = false,
                            hasMore = page.previous != null,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is ApiException && error.code == 401) {
                        onUnauthorized()
                    } else if (error is ApiException && error.code == 404) {
                        sessionGone(sessionId)
                    }
                }
        }
    }

    /**
     * Merges the server page (source of truth) with any older pages already loaded
     * and with unconfirmed optimistic pending changes.
     */
    private fun resyncMessages(
        server: List<MessageUi>,
        existing: List<MessageUi>,
    ): List<MessageUi> {
        val serverIds = server.mapTo(HashSet()) { it.id }
        val oldest = server.minOfOrNull { it.createdAt ?: Long.MAX_VALUE } ?: Long.MAX_VALUE
        val older = existing.filter {
            it.id !in serverIds && (it.createdAt ?: Long.MAX_VALUE) < oldest
        }
        return reconcileMessages(older + server, existing)
    }

    // --- internals ----------------------------------------------------------

    private suspend fun loadSessions(client: OpenCodeApi) {
        _state.update { it.copy(sessionsLoading = true, error = null) }
        val sessionsResult = withContext(Dispatchers.IO) {
            runCatching { client.sessions() }
        }
        val active = withContext(Dispatchers.IO) {
            runCatching { client.activeSession() }.getOrNull()
        }
        sessionsResult
            .onSuccess { page ->
                _state.update {
                    it.copy(
                        sessions = page.sessions.map { session ->
                            session.copy(isActive = session.id == active)
                        },
                        sessionsLoading = false,
                    )
                }
            }
            .onFailure { error ->
                if (error is ApiException && error.code == 401) {
                    onUnauthorized()
                    return@onFailure
                }
                _state.update { it.copy(sessionsLoading = false) }
                reportFailure(error)
            }
    }

    private fun setConnectionError(message: String) {
        stopStreamInternal()
        _state.update {
            it.copy(
                connection = Connection.Error(message),
                error = message,
                streaming = false,
            )
        }
    }

    /**
     * M5: global 401 policy. Stream stopped (generation incremented in
     * [stopStreamInternal]), service and notifications stopped, token **not**
     * cleared (required for precompiled setup), and no automatic retry.
     */
    private fun onUnauthorized() {
        val policy = AuthPolicy.onUnauthorized
        if (policy.stopStream) stopStreamInternal()
        if (policy.stopBackgroundService) stopApprovalService()
        if (policy.cancelNotifications) ApprovalNotifier.cancelAll(getApplication())
        if (policy.keepToken) tokenStore.markAuthRejected() else tokenStore.clear()
        api = null
        stream = null
        olderCursor = null
        lastOfflineReason = null
        networkAbsent = false
        retryState = RetryTransitions.clear()
        _state.update {
            it.copy(
                connection = Connection.Error(ConnectionErrors.CREDENTIALS_REJECTED),
                error = "${ConnectionErrors.CREDENTIALS_REJECTED} · redo the pairing",
                streaming = false,
                streamState = StreamState.Idle,
                nextAttemptAt = 0L,
                retrySuspended = false,
                reconnectInSeconds = 0,
            )
        }
    }

    /** Clear messages for the user; never headers, never credentials. */
    private fun describeConnectionError(
        error: Throwable,
        context: ErrorContext = ErrorContext.Generic,
    ): String = ConnectionErrors.describe(error, context)

    private fun appendLog(kind: String, payload: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val flat = payload.replace('\n', ' ').replace('\r', ' ')
        val snippet = if (flat.length > 200) flat.take(200) + "…" else flat
        val line = "$time  [$kind] $snippet"

        _state.update { current ->
            val next = current.events + line
            current.copy(events = if (next.size > MAX_EVENTS) next.takeLast(MAX_EVENTS) else next)
        }
    }

    private fun resetSeenEventIds() {
        seenEventIds.clear()
    }

    override fun onCleared() {
        stopStreamInternal()
        unregisterNetworkCallback()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        super.onCleared()
    }

    companion object {
        private const val MAX_EVENTS = 200
        private const val MAX_SEEN_EVENT_IDS = 500

        /** Command catalog TTL, like the other catalogs (5 minutes). */
        private const val COMMANDS_TTL_MS = 5 * 60_000L

        /** Beyond this SSE silence, the foreground forces a reconnect. */
        private const val SSE_STALE_MS = SseWatchdog.TIMEOUT_MS

        /**
         * §4.4: minimum interval between two live turn snapshots (80–120 ms). 100
         * ms = 10 publications/s instead of 30–60, without visible stutter.
         */
        private const val CHAT_PUBLISH_INTERVAL_MS = 100L

        /** How long the name of the just-activated subagent remains on screen. */
        private const val SUBAGENT_FLASH_MS = 3_800L

        /** Memory of already flashed children (bounded). */
        private const val MAX_FLASHED_CHILDREN = 200
    }
}
