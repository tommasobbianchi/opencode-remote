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

/** Stato della connessione REST al server OpenCode. */
sealed interface Connection {
    data object Disconnected : Connection
    data object Connecting : Connection
    data class Connected(val version: String, val pid: Long) : Connection
    data class Error(val message: String) : Connection

    /**
     * M5: il server risponde ma non è la 2.x. Schermata bloccante, nessuna
     * chiamata ulteriore oltre a un eventuale "Riprova" esplicito.
     */
    data class Incompatible(val found: String) : Connection
}

/** Stato del canale SSE live (indipendente dal REST già connesso). */
sealed interface StreamState {
    data object Idle : StreamState
    data object Live : StreamState

    /**
     * Handshake SSE in corso dopo un fallimento: nessun countdown, così la UI
     * non mostra "riprovo tra 0s" mentre il socket aspetta la risposta.
     */
    data class Connecting(val attempt: Int) : StreamState

    /**
     * Attesa del prossimo tentativo. [nextAttemptAt] è l'istante assoluto:
     * il countdown si deriva da lì, mai da un contatore decrementato a mano.
     * [suspended] = rete assente, tentativi fermi finché non torna.
     */
    data class Reconnecting(
        val attempt: Int,
        val nextAttemptAt: Long,
        val suspended: Boolean = false,
    ) : StreamState
}

/**
 * Unico stato UI.
 *
 * Le credenziali sono persistite cifrate (`TokenStore`, Keystore) per il
 * foreground service; qui resta solo ciò che serve a disegnare la schermata.
 */
data class UiState(
    val url: String = "",
    val user: String = "opencode",
    val password: String = "",
    val connection: Connection = Connection.Disconnected,
    val sessions: List<SessionUi> = emptyList(),
    val sessionsLoading: Boolean = false,
    /** Creazione di una nuova sessione in corso: progresso e pulsante disabilitato. */
    val creatingSession: Boolean = false,
    val openSession: SessionUi? = null,
    /**
     * Pila padre → figlio: ogni frame è la sessione che si riapre con `back()`.
     * Vuota = la sessione corrente è stata aperta dalla lista.
     */
    val sessionStack: List<SessionFrame> = emptyList(),
    /** La sessione aperta è un task figlio: chat in sola lettura. */
    val openReadOnly: Boolean = false,
    val messages: List<MessageUi> = emptyList(),
    val messagesLoading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    // M0: log SSE disponibile come schermata secondaria.
    val streaming: Boolean = false,
    val events: List<String> = emptyList(),
    // M2: invio, streaming live e riconnessione.
    val draft: String = "",
    val running: Boolean = false,
    val sessionOutcome: String? = null,
    val chat: ChatStream = ChatStream(),
    val streamState: StreamState = StreamState.Idle,
    /** Secondi al prossimo tentativo, derivati da [nextAttemptAt] a ogni tick. */
    val reconnectInSeconds: Int = 0,
    /** Istante assoluto del prossimo tentativo SSE (0 = nessuno programmato). */
    val nextAttemptAt: Long = 0L,
    /** Rete assente: i tentativi sono sospesi, nessun numero nel banner. */
    val retrySuspended: Boolean = false,
    // M3: cataloghi del drawer (agenti, skill, MCP, modelli, location).
    val catalog: Catalog = Catalog(),
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val children: List<SessionUi> = emptyList(),
    val childrenLoading: Boolean = false,
    // M4: richieste di approvazione della sola sessione aperta.
    val approvals: List<ApprovalUi> = emptyList(),
    val approvalsLoading: Boolean = false,
    val approvalsError: String? = null,
    /**
     * Solo presentazione: requestID pendenti per sessione, per i badge di
     * lista e drawer. Non è una seconda fonte di verità: si allinea agli
     * eventi e alla GET della sessione aperta, senza chiamate di rete nuove.
     */
    val pendingApprovals: Map<String, Set<String>> = emptyMap(),
) {
    val isConnected: Boolean get() = connection is Connection.Connected

    /** C'è almeno un padre impilato: il back torna a lui invece che alla lista. */
    val canGoBackToParent: Boolean get() = SessionNav.canGoBackToParent(sessionStack)

    val liveMessage: LiveMessage? get() = chat.live

    /** Badge: richieste pendenti note per una sessione. */
    fun approvalCount(sessionId: String?): Int =
        sessionId?.let { pendingApprovals[it]?.size } ?: 0

    /** Badge del drawer: richieste pendenti su tutte le sessioni note. */
    val approvalCountTotal: Int get() = pendingApprovals.values.sumOf { it.size }

    /**
     * Helper di presentazione: i task figli con l'attività osservata sulle
     * sessioni. `GET /api/session?parentID=` non porta uno stato live, mentre
     * gli eventi SSE aggiornano le righe di `sessions` (i figli compaiono anche
     * nella lista top-level): qui le due viste vengono ricongiunte per il badge
     * dei task e il pallino "attiva" delle righe.
     */
    val childrenWithActivity: List<SessionUi>
        get() = children.map { child ->
            val running = child.isActive || sessions.any { it.id == child.id && it.isActive }
            if (running == child.isActive) child else child.copy(isActive = running)
        }

    /** Figli in esecuzione, per il badge del drawer. */
    val runningChildren: Int get() = childrenWithActivity.count { it.isActive }
}

/** Esiti delle cinque chiamate parallele del catalogo M3. */
private data class CatalogFetch(
    val agents: Result<List<AgentUi>>,
    val skills: Result<List<SkillUi>>,
    val mcps: Result<List<McpUi>>,
    val models: Result<List<ModelUi>>,
    val location: Result<LocationUi?>,
)

// --- M4: badge per sessione (mappa di sola presentazione) --------------------

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
 * Antepone [session] alla lista senza duplicati: stesso schema dell'apertura
 * da notifica (`openSessionRow`). Funzione pura per i test JVM, perché il
 * ViewModel non è istanziabile senza Android.
 */
internal fun prependSession(sessions: List<SessionUi>, session: SessionUi): List<SessionUi> =
    if (sessions.any { it.id == session.id }) sessions else listOf(session) + sessions

/**
 * Fotografia della chat pronta per la UI: quello che il [PublishGate] accumula
 * e pubblica. Contiene già lo stato ridotto, non i singoli eventi.
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

    /** Credenziali cifrate: al primo avvio si riconnette senza chiedere nulla. */
    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            onProcessForeground()
        }
    }

    /** Client per REST; timeout normali. */
    private val restClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Client per SSE: nessun read timeout (lo stream resta aperto). */
    private val sseClient = restClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var api: OpenCodeApi? = null
    private var stream: EventStream? = null
    private var streamJob: Job? = null
    private var streamGeneration = 0

    /** Cursore per caricare messaggi più vecchi della sessione aperta. */
    private var olderCursor: String? = null

    /** Dedupe degli eventi SSE per `event.id` (bounded). */
    private val seenEventIds = LinkedHashSet<String>()

    private var optimisticSeq = 0

    /**
     * §4.4: i delta arrivano a raffica; lo stato ridotto è sempre completo ma
     * la UI riceve uno snapshot al massimo ogni [CHAT_PUBLISH_INTERVAL_MS],
     * con gli eventi di fase/fine turno immediati.
     */
    private val chatGate = PublishGate<ChatSnapshot>(CHAT_PUBLISH_INTERVAL_MS)
    private var chatFlushJob: Job? = null

    /** M5: ultimo segnale ricevuto sulla SSE (apertura o evento). */
    @Volatile
    private var lastSseActivityAt = 0L

    /**
     * M5 F1: ultimo motivo per cui una REST ha dichiarato il server
     * irraggiungibile. Quando la SSE torna a dare segni di vita l'errore
     * viene rimosso solo se è ancora quello (non un errore di un'azione).
     */
    @Volatile
    private var lastOfflineReason: String? = null

    /** M5.1: rete assente — i tentativi SSE restano fermi finché non torna. */
    @Volatile
    private var networkAbsent = false

    /** M5.1: stato puro del retry, condiviso con il ciclo SSE. */
    @Volatile
    private var retryState = RetryState()

    private val connectivity = application.getSystemService(Context.CONNECTIVITY_SERVICE)
        as? ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Sessione da aprire dopo il tap su una notifica, attesa la connessione. */
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
                // 401 già visto: il token resta per precompilare il setup, ma
                // nessun retry automatico finché l'utente non rifà il pairing.
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

    // --- M5.1: rete reale (ConnectivityManager) -----------------------------

    /**
     * Registra il callback sul default network. In modalità aereo il socket
     * SSE può restare appeso per minuti: questo callback è la sorgente
     * immediata con cui passiamo a "rete assente" e sospendiamo i tentativi.
     * Il foreground service ha già il proprio watcher SSE: qui non si duplica.
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

    /** Rete persa: chiusura immediata del socket appeso e retry sospeso. */
    private fun onNetworkLost() {
        viewModelScope.launch {
            if (!_state.value.isConnected || networkAbsent) return@launch
            networkAbsent = true
            // Il collect della SSE non vedrebbe la perdita finché il socket
            // resta muto (watchdog 30 s): si chiude subito.
            streamGeneration += 1
            streamJob?.cancel()
            streamJob = null
            markOffline(ReconnectPolicy.NETWORK_ABSENT)
        }
    }

    /**
     * Rete tornata: azzera il backoff e riparte con una sola riconnessione.
     * Le callback duplicate non producono un secondo restart.
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
            // Un solo giro: il job precedente è stato chiuso alla perdita.
            stopStreamInternal()
            startStreamInternal()
            if (_state.value.openSession != null) {
                resyncOpenSession()
                refreshApprovals()
            }
            appendLog("net", "rete disponibile · riconnessione")
        }
    }

    fun onUrlChange(value: String) = _state.update { it.copy(url = value) }

    fun onUserChange(value: String) = _state.update { it.copy(user = value) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value) }

    fun onDraftChange(value: String) = _state.update { it.copy(draft = value) }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Connessione + `GET /api/info` + `GET /api/session`, poi stream e lista. */
    fun connect(url: String, user: String, password: String) {
        connectInternal(url, user, password, persist = true)
    }

    /**
     * [persist] salva le credenziali cifrate dopo un `connect()` esplicito e
     * riuscito. All'avvio l'auto-connect usa `persist = false`.
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
            setConnectionError("URL non valido: è consentito solo https://")
            return
        }
        if (user.isBlank()) {
            setConnectionError("Utente mancante")
            return
        }
        if (password.isEmpty()) {
            setConnectionError("Password/token mancante")
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
                            // Versione bloccante: niente lista, niente stream,
                            // niente servizio. Solo la schermata di mismatch.
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
                            // Supported oppure Unknown (versione grezza mostrata).
                            if (persist) {
                                tokenStore.save(StoredCredentials(base, user, password))
                            }
                            api = client
                            stream = EventStream(sseClient, client.eventUrl(), client.authHeader())
                            resetChatPublish()
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

    /** "Riprova" dalla schermata di versione incompatibile (richiesta esplicita). */
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
            _state.update { it.copy(error = "non connesso") }
            return
        }
        viewModelScope.launch { loadSessions(client) }
    }

    /**
     * Crea una sessione con i default del server e la apre subito in chat,
     * anteponendola alla lista come l'apertura da notifica. In caso di errore
     * la lista resta invariata e il messaggio passa da `error`.
     */
    fun createSession() {
        val client = api
        if (client == null) {
            _state.update { it.copy(error = "non connesso") }
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
     * "Riprova" dalla lista: rilegge subito la lista e, se la SSE era ferma,
     * la riavvia (un socket appeso non produce eventi né failure). In
     * riconnessione il job esistente resta: il backoff non viene forzato.
     */
    fun retryConnection() {
        val client = api
        if (client == null) {
            _state.update { it.copy(error = "non connesso") }
            return
        }
        viewModelScope.launch { loadSessions(client) }
        when (_state.value.streamState) {
            StreamState.Idle, is StreamState.Connecting -> {
                if (streamJob?.isActive == true) stopStreamInternal()
                startStreamInternal()
            }
            // In attesa del backoff o con la rete assente il job esistente
            // resta: niente restart forzato.
            else -> Unit
        }
    }

    /**
     * Apre una sessione. Dalla lista riparte con la pila vuota; se una sessione
     * è già aperta, quella corrente viene impilata e la nuova è un task figlio
     * in sola lettura. Stesso id = refresh: niente duplicati in pila.
     */
    fun openSession(id: String) {
        val client = api
        if (client == null) {
            _state.update { it.copy(error = "non connesso") }
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
     * Riapre il padre impilato (rileggendo i messaggi dal server) oppure torna
     * alla lista se non c'è un padre.
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

    /** Apre [session] con [stack]; [readOnly] abilita la vista senza composer. */
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
     * La sessione aperta non esiste più sul server. Se è la sessione padre
     * (aperta dalla lista) si torna alla lista con "sessione non più
     * disponibile"; per una figlia resta il ritorno automatico al padre.
     */
    private fun sessionGone(id: String) {
        if (_state.value.openSession?.id != id) return
        if (!_state.value.openReadOnly) {
            backToList()
            _state.update { it.copy(error = "sessione non più disponibile") }
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
                it.copy(error = "sessione non trovata · sei tornato alla sessione padre")
            }
        } else {
            backToList()
            _state.update { it.copy(error = "sessione non più disponibile") }
        }
    }

    /** Usa la riga più recente della lista quando c'è, altrimenti il fallback. */
    private fun resolvedSession(id: String, fallback: SessionUi): SessionUi =
        _state.value.sessions.firstOrNull { it.id == id } ?: fallback

    /** Carica la pagina precedente e la antepone (usa `cursor.previous`). */
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
                        // Cursore scaduto: messaggio dedicato e riallineamento.
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

    // --- M4: approvazioni ---------------------------------------------------

    /** Allineamento con `GET …/permission` per la sessione aperta. */
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
     * Risposta a una richiesta della sessione aperta. Su successo la richiesta
     * sparisce dallo stato; se è scaduta si mostra il messaggio dedicato e si
     * rifà la GET (contratto API-M4).
     */
    fun replyApproval(id: String, decision: String, message: String? = null) {
        val client = api ?: return
        val sessionId = _state.value.openSession?.id ?: return
        if (decision != "once" && decision != "always" && decision != "reject") return
        // Un nuovo invio azzera l'errore precedente: la UI può mostrare lo
        // spinner dell'azione e ripartire anche dopo un errore uguale.
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
                                error = "richiesta non più valida",
                                approvalsError = "richiesta non più valida",
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

    /** Ritorno in foreground: riallinea le approvazioni della sessione aperta. */
    fun onAppForeground() {
        onProcessForeground()
    }

    /**
     * M5 (Doze/foreground): se la SSE non ha dato segni di vita da 30 s o il
     * job non è attivo, si forza una riconnessione e si riallinea la sessione
     * aperta. `startStreamInternal` ha già il guard sul job: mai due stream.
     * Con la rete assente non si forza nulla: restano i tentativi sospesi.
     */
    private fun onProcessForeground() {
        if (!_state.value.isConnected) return
        if (networkAbsent) {
            // Se il callback è andato perso, il foreground è il punto di
            // recupero: la rete c'è di nuovo → riparti subito.
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
            // Se il sistema nega l'avvio (es. limiti background) la UI resta ok.
        }
    }

    private fun stopApprovalService() {
        try {
            val context = getApplication<Application>()
            context.stopService(Intent(context, ApprovalService::class.java))
        } catch (_: Exception) {
            // Nessun servizio da fermare.
        }
    }

    private fun describeApprovalError(error: Throwable): String = when {
        error is ApprovalExpiredException -> "richiesta non più valida"
        error is ApiException && error.code == 401 -> "credenziali rifiutate"
        error is ApiException && error.code == 404 -> "sessione non trovata"
        else -> describeConnectionError(error)
    }

    // --- M3: cataloghi e azioni di sessione ---------------------------------

    /**
     * Carica in parallelo agenti/skill/MCP/modelli/location.
     * Rispetta il TTL di 5 minuti salvo [force]. Se una chiamata fallisce il
     * catalogo precedente resta in piedi e l'errore finisce in `catalogError`.
     */
    fun loadCatalog(force: Boolean = false) {
        val client = api
        if (client == null) {
            _state.update { it.copy(catalogError = "non connesso") }
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

    /** Cambia agente sulla sessione aperta; poi rilegge la sessione dal server. */
    fun switchAgent(agentId: String) {
        val client = api
        val session = _state.value.openSession
        if (client == null) {
            _state.update { it.copy(error = "non connesso") }
            return
        }
        if (session == null) {
            _state.update { it.copy(error = "apri una sessione per cambiare agente") }
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

    /** Cambia modello sulla sessione aperta; poi rilegge la sessione dal server. */
    fun switchModel(providerId: String, modelId: String, variant: String?) {
        val client = api
        val session = _state.value.openSession
        if (client == null) {
            _state.update { it.copy(error = "non connesso") }
            return
        }
        if (session == null) {
            _state.update { it.copy(error = "apri una sessione per cambiare modello") }
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

    /** Attiva una skill nella sessione aperta senza assumere effetti locali. */
    fun activateSkill(skillId: String) {
        val client = api
        if (client == null) {
            _state.update { it.copy(error = "non connesso") }
            return
        }
        val session = _state.value.openSession
        if (session == null) {
            _state.update { it.copy(error = "apri una sessione per attivare una skill") }
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
     * Helper di presentazione (nessuna chiamata di rete): apre una sessione che
     * il drawer conosce come riga — per esempio un task in background — ma che
     * può non essere ancora nella lista principale. Se manca, la aggiunge in
     * cima così titolo e meta restano corretti; `refreshSessions` riallinea poi
     * la lista con il server.
     */
    fun openSessionRow(session: SessionUi) {
        _state.update { current ->
            current.copy(sessions = prependSession(current.sessions, session))
        }
        openSession(session.id)
    }

    /** Figli della sessione aperta (task in background). */
    fun refreshChildren() {
        val client = api
        if (client == null) {
            _state.update { it.copy(error = "non connesso") }
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
     * Applica la sessione restituita da `GET /api/session/{id}` alla riga in
     * lista e a quella aperta, preservando lo stato `isActive` locale.
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
     * M5 F1 / M5.1: una REST fallita per rete/timeout prova che il server non
     * è raggiungibile adesso. Solo "rete assente" sospende i tentativi: gli
     * altri errori lasciano il backoff SSE intatto.
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

                // Una riconnessione in corso è già "non live": si conserva il
                // countdown del backoff, senza forzarne uno nuovo.
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
     * M5 F1: la SSE ha ripreso a parlare (open o evento): il server è
     * raggiungibile, quindi un eventuale errore di raggiungibilità è stantio
     * e va rimosso. Gli errori di una singola azione restano.
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

    /** Errore REST non-401: offline se è di rete, altrimenti messaggio normale. */
    private fun reportFailure(error: Throwable) {
        val reason = describeConnectionError(error)
        if (isReachabilityFailure(error)) markOffline(reason) else {
            _state.update { current -> current.copy(error = reason) }
        }
    }

    /**
     * Rete assente, DNS, timeout, TLS o socket chiuso: condizioni in cui il
     * server non è raggiungibile. Gli errori HTTP (ApiException) non lo sono:
     * il server ha risposto, quindi la SSE resta com'è.
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

    // --- M2: invio, interruzione --------------------------------------------

    /** Invio ottimistico: la bolla utente appare subito e resta "in coda". */
    fun sendPrompt() {
        val client = api
        flushChatNow()
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
                    // I delta trattenuti potrebbero contenere ancora il
                    // messaggio ottimistico: si pubblicano prima di rinominarlo.
                    flushChatNow()
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
                    flushChatNow()
                    _state.update { current ->
                        current.copy(
                            messages = current.messages.filterNot { it.id == localId },
                            draft = if (current.draft.isBlank()) text else current.draft,
                        )
                    }
                    if (error is ApiException && error.code == 401) {
                        // Nessun reinvio automatico del prompt: si evita il duplicato.
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

    /** Avvia (o riavvia) il ciclo SSE con riconnessione automatica. */
    fun startStream() {
        val api = api
        if (api == null) {
            _state.update { it.copy(error = "Connettiti prima: serve /api/info riuscito") }
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
                // Rete assente: nessun tentativo e nessun backoff finché non torna.
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

                // Handshake in corso: niente countdown (mai "riprovo tra 0s").
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
                            // Un evento prova che la SSE è viva anche se il
                            // socket era stato dato per morto da una REST.
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
                    // Politica globale: stop stream (la generazione è già
                    // incrementata), stop servizio, token conservato, no retry.
                    onUnauthorized()
                    break
                }

                failureReason?.let { appendLog("sse", it) }
                _state.update { it.copy(streaming = false) }

                // La rete è caduta durante il tentativo: il callback ha già
                // impostato lo stato sospeso, si torna in cima senza backoff.
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
                    // Mai pubblicare "riprovo tra 0s": si passa subito al
                    // tentativo (stato Connecting) senza un frame a zero.
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

    /** Stato visibile con la rete assente: tentativi fermi, nessun numero. */
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
        // Solo il tipo: mai payload, prompt o header nel log.
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

        // M4: `permission.asked` è la fonte primaria per lo stato della chat.
        // Il badge per sessione si aggiorna anche per le sessioni non aperte.
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
        // La GET resta come allineamento quando inizia un turno/step.
        if ((event is ServerEvent.ExecutionStarted || event is ServerEvent.StepStarted) &&
            openId != null && event.sessionId == openId
        ) {
            refreshApprovals()
        }

        if (openId == null || event.sessionId != openId) return

        // §4.4: il reducer accumula sempre su base completa (pending even-
        // tualmente non ancora pubblicato), poi il gate decide quando la UI
        // vede lo snapshot. Gli eventi di fase/fine turno passano subito.
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

        // I messaggi sono la verità: dopo la fine del turno rileggi la sessione.
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

    /** Porta lo snapshot ridotto nella UI (una sola emissione di `state`). */
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

    /** Pubblica l'ultimo snapshot accumulato allo scadere della finestra. */
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
     * Pubblica subito ciò che il gate trattiene: obbligatorio prima di ogni
     * mutazione di `messages`/`chat` che non passa dal reducer, altrimenti lo
     * snapshot in attesa tornerebbe sopra scrivendola.
     */
    private fun flushChatNow() {
        chatFlushJob?.cancel()
        chatFlushJob = null
        chatGate.takePending()?.let { publishChat(it) }
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
                    // Il merge parte dallo stato pubblicato: i delta trattenuti
                    // vanno resi visibili prima, o si perderebbero.
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
     * Unisce la pagina server (verità) con eventuali pagine più vecchie già
     * caricate e con i pending ottimistici non ancora confermati.
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
     * M5: politica globale del 401. Stream fermato (generazione incrementata
     * in [stopStreamInternal]), servizio e notifiche fermati, token **non**
     * cancellato (serve al setup precompilato) e nessun retry automatico.
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

    /** Messaggi chiari per l'utente; mai header, mai credenziali. */
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

        /** Oltre questo silenzio sulla SSE, al foreground si forza il reconnect. */
        private const val SSE_STALE_MS = SseWatchdog.TIMEOUT_MS

        /**
         * §4.4: finestra minima fra due snapshot del turno live (80–120 ms).
         * 100 ms = 10 pubblicazioni/s invece di 30–60, senza scatti visibili.
         */
        private const val CHAT_PUBLISH_INTERVAL_MS = 100L
    }
}
