package io.github.mirkotappini.opencoderemote

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import io.github.mirkotappini.opencoderemote.net.AgentUi
import io.github.mirkotappini.opencoderemote.net.ApiException
import io.github.mirkotappini.opencoderemote.net.ApprovalExpiredException
import io.github.mirkotappini.opencoderemote.net.ApprovalUi
import io.github.mirkotappini.opencoderemote.net.AuthPolicy
import io.github.mirkotappini.opencoderemote.net.Backoff
import io.github.mirkotappini.opencoderemote.net.Catalog
import io.github.mirkotappini.opencoderemote.net.ChatReducer
import io.github.mirkotappini.opencoderemote.net.ChatStream
import io.github.mirkotappini.opencoderemote.net.ConnectionErrors
import io.github.mirkotappini.opencoderemote.net.ErrorContext
import io.github.mirkotappini.opencoderemote.net.EventParsing
import io.github.mirkotappini.opencoderemote.net.EventStream
import io.github.mirkotappini.opencoderemote.net.LiveMessage
import io.github.mirkotappini.opencoderemote.net.LocationUi
import io.github.mirkotappini.opencoderemote.net.McpUi
import io.github.mirkotappini.opencoderemote.net.MessageUi
import io.github.mirkotappini.opencoderemote.net.ModelUi
import io.github.mirkotappini.opencoderemote.net.OpenChatState
import io.github.mirkotappini.opencoderemote.net.OpenCodeApi
import io.github.mirkotappini.opencoderemote.net.ReconnectPolicy
import io.github.mirkotappini.opencoderemote.net.RetryState
import io.github.mirkotappini.opencoderemote.net.RetryTransitions
import io.github.mirkotappini.opencoderemote.net.Role
import io.github.mirkotappini.opencoderemote.net.ServerVersion
import io.github.mirkotappini.opencoderemote.net.SkillUi
import io.github.mirkotappini.opencoderemote.net.ServerEvent
import io.github.mirkotappini.opencoderemote.net.SessionCommands
import io.github.mirkotappini.opencoderemote.net.SessionUi
import io.github.mirkotappini.opencoderemote.net.SseEvent
import io.github.mirkotappini.opencoderemote.net.SseWatchdog
import io.github.mirkotappini.opencoderemote.net.VersionCheck
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

/** State of the REST connection to the OpenCode server. */
sealed interface Connection {
    data object Disconnected : Connection
    data object Connecting : Connection
    data class Connected(val version: String, val pid: Long) : Connection
    data class Error(val message: String) : Connection

    /**
     * M5: the server responds but is not 2.x. Blocking screen, no
     * further call besides an explicit "Riprova".
     */
    data class Incompatible(val found: String) : Connection
}

/** State of the live SSE channel (independent from the already-connected REST). */
sealed interface StreamState {
    data object Idle : StreamState
    data object Live : StreamState

    /**
     * SSE handshake in progress after a failure: no countdown, so the UI
     * does not show "riprovo tra 0s" while the socket awaits the response.
     */
    data class Connecting(val attempt: Int) : StreamState

    /**
     * Waiting for the next attempt. [nextAttemptAt] is the absolute instant:
     * the countdown is derived from it, never from a counter decremented by hand.
     * [suspended] = network absent, attempts paused until it returns.
     */
    data class Reconnecting(
        val attempt: Int,
        val nextAttemptAt: Long,
        val suspended: Boolean = false,
    ) : StreamState
}

/**
 * The single UI state.
 *
 * Credentials are persisted encrypted (`TokenStore`, Keystore) for the
 * foreground service; here only what is needed to draw the screen remains.
 */
data class UiState(
    val url: String = "",
    val user: String = "opencode",
    val password: String = "",
    val connection: Connection = Connection.Disconnected,
    val sessions: List<SessionUi> = emptyList(),
    val sessionsLoading: Boolean = false,
    /** New session creation in progress: progress and button disabled. */
    val creatingSession: Boolean = false,
    val openSession: SessionUi? = null,
    /**
     * Parent → child stack: every frame is the session reopened by `back()`.
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
    // M2: sending, live streaming, and reconnection.
    val draft: String = "",
    val running: Boolean = false,
    val sessionOutcome: String? = null,
    val chat: ChatStream = ChatStream(),
    val streamState: StreamState = StreamState.Idle,
    /** Seconds to the next attempt, derived from [nextAttemptAt] on every tick. */
    val reconnectInSeconds: Int = 0,
    /** Absolute instant of the next SSE attempt (0 = none scheduled). */
    val nextAttemptAt: Long = 0L,
    /** Network absent: attempts are suspended, no numbers in the banner. */
    val retrySuspended: Boolean = false,
    // M3: drawer catalogs (agents, skills, MCP, models, location).
    val catalog: Catalog = Catalog(),
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val children: List<SessionUi> = emptyList(),
    val childrenLoading: Boolean = false,
    // M4: approval requests of the open session only.
    val approvals: List<ApprovalUi> = emptyList(),
    val approvalsLoading: Boolean = false,
    val approvalsError: String? = null,
    /**
     * Presentation only: pending requestIDs per session, for the badges
     * of the list and the drawer. Not a second source of truth: it aligns
     * with the events and the GET of the open session, with no new network calls.
     */
    val pendingApprovals: Map<String, Set<String>> = emptyMap(),
) {
    val isConnected: Boolean get() = connection is Connection.Connected

    /** At least one parent stacked: back returns to it instead of the list. */
    val canGoBackToParent: Boolean get() = SessionNav.canGoBackToParent(sessionStack)

    val liveMessage: LiveMessage? get() = chat.live

    /** Badge: known pending requests for one session. */
    fun approvalCount(sessionId: String?): Int =
        sessionId?.let { pendingApprovals[it]?.size } ?: 0

    /** Drawer badge: pending requests across all known sessions. */
    val approvalCountTotal: Int get() = pendingApprovals.values.sumOf { it.size }

    /**
     * Presentation helper: child tasks with the activity observed on the
     * sessions. `GET /api/session?parentID=` carries no live state, while
     * the SSE events update the `sessions` rows (children appear also
     * in the top-level list): here the two views are re-joined for the badge
     * of the tasks and the "attiva" dot of the rows.
     */
    val childrenWithActivity: List<SessionUi>
        get() = children.map { child ->
            val running = child.isActive || sessions.any { it.id == child.id && it.isActive }
            if (running == child.isActive) child else child.copy(isActive = running)
        }

    /** Children in execution, for the drawer badge. */
    val runningChildren: Int get() = childrenWithActivity.count { it.isActive }
}

/** Results of the five parallel calls of the M3 catalog. */
private data class CatalogFetch(
    val agents: Result<List<AgentUi>>,
    val skills: Result<List<SkillUi>>,
    val mcps: Result<List<McpUi>>,
    val models: Result<List<ModelUi>>,
    val location: Result<LocationUi?>,
)

// --- M4: per-session badges (presentation-only map) --------------------

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
 * Prefers [session] over the list without duplicates: same schema as the
 * open-from-notification (`openSessionRow`). A pure function for the JVM tests, since the
 * ViewModel cannot be instantiated without Android.
 */
internal fun prependSession(sessions: List<SessionUi>, session: SessionUi): List<SessionUi> =
    if (sessions.any { it.id == session.id }) sessions else listOf(session) + sessions


class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val tokenStore = TokenStore(application)

    /** Encrypted credentials: on the first launch it reconnects without asking anything. */
    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            onProcessForeground()
        }
    }

    /** Client for REST; ordinary timeouts. */
    private val restClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Client for SSE: no read timeout (the stream stays open). */
    private val sseClient = restClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var api: OpenCodeApi? = null
    private var stream: EventStream? = null
    private var streamJob: Job? = null
    private var streamGeneration = 0

    /** Cursor to load older messages of the open session. */
    private var olderCursor: String? = null

    /** Dedupes SSE events by `event.id` (bounded). */
    private val seenEventIds = LinkedHashSet<String>()

    private var optimisticSeq = 0

    /** M5: last signal received on the SSE (open or event). */
    @Volatile
    private var lastSseActivityAt = 0L

    /**
     * M5 F1: last reason a REST call declared the server
     * unreachable. When the SSE gives signs of life again, the error
     * is removed only if it is still that one (not an error of an action).
     */
    @Volatile
    private var lastOfflineReason: String? = null

    /** M5.1: network absent — SSE attempts stay paused until it returns. */
    @Volatile
    private var networkAbsent = false

    /** M5.1: pure state of the retry, shared with the SSE cycle. */
    @Volatile
    private var retryState = RetryState()

    private val connectivity = application.getSystemService(Context.CONNECTIVITY_SERVICE)
        as? ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Session to open after a tap on a notification, once the connection is up. */
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
                // 401 already seen: the token stays to prefill the setup, but
                // no automatic retry until the user does the pairing again.
                _state.update {
                    it.copy(
                        connection = Connection.Error(ConnectionErrors.CREDENTIALS_REJECTED),
                        error = "${ConnectionErrors.CREDENTIALS_REJECTED} · rifai il pairing",
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
     * Registers the callback on the default network. In airplane mode the
     * SSE socket can stay hung for minutes: this callback is the immediate
     * source by which we switch to "rete assente" and suspend the attempts.
     * The foreground service already has its own SSE watcher: do not duplicate it here.
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

    /** Network lost: immediate close of the hung socket and suspended retry. */
    private fun onNetworkLost() {
        viewModelScope.launch {
            if (!_state.value.isConnected || networkAbsent) return@launch
            networkAbsent = true
            // The SSE collect would not see the loss until the socket
            // stays silent (30 s watchdog): close it right away.
            streamGeneration += 1
            streamJob?.cancel()
            streamJob = null
            markOffline(ReconnectPolicy.NETWORK_ABSENT)
        }
    }

    /**
     * Network restored: the backoff is reset and one reconnection restarts.
     * Duplicate callbacks do not produce a second restart.
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
            // Only one round: the previous job was closed at the loss.
            stopStreamInternal()
            startStreamInternal()
            if (_state.value.openSession != null) {
                resyncOpenSession()
                refreshApprovals()
            }
            appendLog("net", "network available · reconnecting")
        }
    }

    fun onUrlChange(value: String) = _state.update { it.copy(url = value) }

    fun onUserChange(value: String) = _state.update { it.copy(user = value) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value) }

    fun onDraftChange(value: String) = _state.update { it.copy(draft = value) }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Connection + `GET /api/info` + `GET /api/session`, then stream and list. */
    fun connect(url: String, user: String, password: String) {
        connectInternal(url, user, password, persist = true)
    }

    /**
     * [persist] stores the encrypted credentials after an explicit and
     * successful `connect()`. At startup the auto-connect uses `persist = false`.
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
                            // Blocking version: no list, no stream,
                            // no service. Only the mismatch screen.
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
                                    error = null,
                                )
                            }
                            loadSessions(client)
                            loadCatalog(force = true)
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

    /** "Riprova" from the incompatible-version screen (explicit request). */
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
     * Creates a session with the server defaults and opens it right away in chat,
     * preferring it over the list like the open from notification. On error
     * the list stays unchanged and the message goes through `error`.
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
     * "Riprova" from the list: re-reads the list immediately and, if the SSE was stalled,
     * restarts it (a hung socket produces neither events nor failures). In
     * reconnection the existing job stays: the backoff is not forced.
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
            // Waiting for the backoff or with the network absent, the existing job
            // stays: no forced restart.
            else -> Unit
        }
    }

    /**
     * Opens a session. From the list it restarts with an empty stack; if a session
     * is already open, the current one is stacked and the new one is a child task
     * in read-only. Same id = refresh: no duplicates in the stack.
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
     * Reopens the stacked parent (re-reading the messages from the server), or
     * returns to the list if there is no parent.
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

    /** Opens [session] with [stack]; [readOnly] enables the view without composer. */
    private fun openSessionInternal(
        client: OpenCodeApi,
        session: SessionUi,
        stack: List<SessionFrame>,
        readOnly: Boolean,
    ) {
        val id = session.id
        olderCursor = null
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
            )
        }
        refreshApprovals()
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
     * (opened from the list) it returns to the list with "session no longer
     * available"; for a child session the automatic return to the parent
     * remains.
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

    /** Uses the most recent row of the list when available, otherwise the fallback. */
    private fun resolvedSession(id: String, fallback: SessionUi): SessionUi =
        _state.value.sessions.firstOrNull { it.id == id } ?: fallback

    /** Loads the previous page and prepends it (uses `cursor.previous`). */
    fun loadOlder() {
        val client = api ?: return
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
                        // Cursor expired: dedicated message and realignment.
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
            )
        }
    }

    // --- M4: approvals ---------------------------------------------------

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
     * Response to a request of the open session. On success the request
     * disappears from the state; if it is expired, the dedicated message is
     * shown and the GET is redone (API-M4 contract).
     */
    fun replyApproval(id: String, decision: String, message: String? = null) {
        val client = api ?: return
        val sessionId = _state.value.openSession?.id ?: return
        if (decision != "once" && decision != "always" && decision != "reject") return
        // A new send clears the previous error: the UI can show the
        // spinner of the action and start over even after the same error.
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

    /** Back in foreground: realigns the approvals of the open session. */
    fun onAppForeground() {
        onProcessForeground()
    }

    /**
     * M5 (Doze/foreground): if the SSE has not given signs of life for 30 s or the
     * job is not active, a reconnection is forced and the open
     * session is realigned. `startStreamInternal` already has the guard on the job: never two streams.
     * With the network absent nothing is forced: the attempts stay suspended.
     */
    private fun onProcessForeground() {
        if (!_state.value.isConnected) return
        if (networkAbsent) {
            // If the callback was lost, the foreground is the recovery
            // point: the network is back again → resume right away.
            if (connectivity?.let { hasInternet(it) } == true) {
                onNetworkAvailable()
            } else {
                refreshApprovals()
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
            // If the system denies the start (e.g. background limits) the UI stays fine.
        }
    }

    private fun stopApprovalService() {
        try {
            val context = getApplication<Application>()
            context.stopService(Intent(context, ApprovalService::class.java))
        } catch (_: Exception) {
            // No service to stop.
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
     * Loads agents/skills/MCP/models/location in parallel.
     * Respects the 5-minute TTL unless [force]. If a call fails, the
     * previous catalog stays standing and the error ends up in `catalogError`.
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

    /** Changes the agent on the open session; then re-reads the session from the server. */
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

    /** Changes the model on the open session; then re-reads the session from the server. */
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

    /** Activates a skill on the open session without assuming local effects. */
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
     * Presentation helper (no network call): opens a session that
     * the drawer knows as a row — for example a background task — but which
     * may not be in the main list yet. If it is missing, it is added at the
     * top so title and meta stay correct; `refreshSessions` realigns
     * the list with the server afterwards.
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
     * Applies the session returned by `GET /api/session/{id}` to the row
     * in the list and to the open one, preserving the local `isActive` state.
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
     * M5 F1 / M5.1: a REST failed for network/timeout proves the server is
     * not reachable right now. Only "rete assente" suspends the attempts:
     * the other errors leave the SSE backoff intact.
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

                // A reconnection in progress is already "not live": the
                // backoff countdown is kept, without forcing a new one.
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
     * M5 F1: the SSE spoke again (open or event): the server is
     * reachable, so any reachability error is stale
     * and must be removed. The errors of a single action remain.
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

    /** Non-401 REST error: offline if it is a network one, otherwise a regular message. */
    private fun reportFailure(error: Throwable) {
        val reason = describeConnectionError(error)
        if (isReachabilityFailure(error)) markOffline(reason) else {
            _state.update { current -> current.copy(error = reason) }
        }
    }

    /**
     * Network absent, DNS, timeout, TLS, or closed socket: conditions in which the
     * server is not reachable. HTTP errors (ApiException) are not:
     * the server has responded, so the SSE stays as it is.
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

    // --- M2: sending, interruption --------------------------------------------

    /** Optimistic sending: the user bubble appears immediately and stays "in coda". */
    fun sendPrompt() {
        val client = api
        val session = _state.value.openSession
        val text = _state.value.draft.trim()
        if (client == null || session == null || text.isEmpty()) return

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
                ),
            )
        }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.prompt(session.id, text, delivery = "steer") }
            }
            result
                .onSuccess { serverId ->
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
                        current.copy(messages = updated)
                    }
                }
                .onFailure { error ->
                    _state.update { current ->
                        current.copy(
                            messages = current.messages.filterNot { it.id == localId },
                            draft = if (current.draft.isBlank()) text else current.draft,
                        )
                    }
                    if (error is ApiException && error.code == 401) {
                        // No automatic re-send of the prompt: this avoids the duplicate.
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

    // --- SSE stream and reconnection -----------------------------------------

    /** Starts (or restarts) the SSE cycle with automatic reconnection. */
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
        appendLog("system", "stream fermato")
        _state.update { it.copy(streaming = false) }
    }

    private fun startStreamInternal() {
        val eventStream = stream ?: return
        if (streamJob?.isActive == true) return

        val generation = ++streamGeneration
        streamJob = viewModelScope.launch {
            var attempt = 0
            while (isActive && generation == streamGeneration) {
                // Network absent: no attempt and no backoff until it returns.
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

                // Handshake in progress: no countdown (never "riprovo tra 0s").
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
                        }

                        is SseEvent.Message -> {
                            lastSseActivityAt = System.currentTimeMillis()
                            // An event proves the SSE is alive even if the
                            // socket was given for dead by a REST call.
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
                    // Global policy: stop stream (the generation is already
                    // incremented), stop the service, token kept, no retry.
                    onUnauthorized()
                    break
                }

                failureReason?.let { appendLog("sse", it) }
                _state.update { it.copy(streaming = false) }

                // The network dropped during the attempt: the callback has already
                // set the suspended state, go back to the top without backoff.
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
                    // Never publish "riprovo tra 0s": go straight to the
                    // attempt (Connecting state) without a zero frame.
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

    /** Visible state with the network absent: attempts halted, no numbers. */
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
        // Only the type: never payload, prompt, or headers in the log.
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

        // M4: `permission.asked` is the primary source for the chat state.
        // The per-session badge is updated also for sessions that are not open.
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
        // The GET remains as an alignment when a turn/step begins.
        if ((event is ServerEvent.ExecutionStarted || event is ServerEvent.StepStarted) &&
            openId != null && event.sessionId == openId
        ) {
            refreshApprovals()
        }

        if (openId == null || event.sessionId != openId) return

        // TODO(performance §4.4): publish the turn snapshot at most
        // every 80–120 ms, with the terminal events immediate. Today every delta
        // publishes right away: the reducer accumulates, the UI will get the throttle
        // when the UI lane introduces `revision`/auto-scroll.
        _state.update { current ->
            val reduced = ChatReducer.reduce(
                OpenChatState(
                    sessionId = openId,
                    messages = current.messages,
                    stream = current.chat,
                    running = current.running,
                    outcome = current.sessionOutcome,
                ),
                event,
            )
            current.copy(
                messages = reduced.messages,
                chat = reduced.stream,
                running = reduced.running,
                sessionOutcome = reduced.outcome,
                openSession = current.openSession?.let { session ->
                    session.copy(
                        isActive = if (event is ServerEvent.ExecutionFinished) {
                            false
                        } else {
                            session.isActive
                        },
                        outcome = reduced.outcome ?: session.outcome,
                    )
                },
            )
        }

        // The messages are the truth: after the end of the turn, re-read the session.
        if (event is ServerEvent.ExecutionFinished) {
            resyncOpenSession()
            refreshApprovals()
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
     * Merges the server page (truth) with any older pages already
     * loaded and with the optimistic pending not yet confirmed.
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
     * M5: global policy of 401. Stream stopped (generation incremented
     * in [stopStreamInternal]), service and notifications stopped, token **not**
     * deleted (it serves the prefilled setup) and no automatic retry.
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
                error = "${ConnectionErrors.CREDENTIALS_REJECTED} · rifai il pairing",
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

        /** Beyond this silence on the SSE, in foreground the reconnect is forced. */
        private const val SSE_STALE_MS = SseWatchdog.TIMEOUT_MS
    }
}
