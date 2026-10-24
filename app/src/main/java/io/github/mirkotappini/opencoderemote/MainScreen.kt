package io.github.mirkotappini.opencoderemote

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Navigazione senza libreria: setup → (lista | chat), con il log SSE come
 * schermata secondaria e il drawer OMO disponibile da lista e chat.
 */
@Composable
fun MainScreen(viewModel: AppViewModel = viewModel()) {
    AppTheme {
        val state by viewModel.state.collectAsStateWithLifecycle()
        var showEventLog by rememberSaveable { mutableStateOf(false) }

        when {
            // M5: la versione del server è bloccante: niente setup, niente lista.
            state.connection is Connection.Incompatible -> VersionMismatchScreen(
                state = state,
                onRetry = viewModel::retryConnect,
                onDisconnect = viewModel::disconnect,
            )

            !state.isConnected -> SetupScreen(
                state = state,
                onUrlChange = viewModel::onUrlChange,
                onUserChange = viewModel::onUserChange,
                onPasswordChange = viewModel::onPasswordChange,
                onConnect = { viewModel.connect(state.url, state.user, state.password) },
            )

            showEventLog -> EventLogScreen(
                state = state,
                onStart = viewModel::startStream,
                onStop = viewModel::stopStream,
                onClose = { showEventLog = false },
            )

            else -> ConnectedSurface(
                state = state,
                viewModel = viewModel,
                onOpenEventLog = { showEventLog = true },
            )
        }
    }
}

/**
 * Superficie connessa: lista o chat avvolte dal drawer laterale destro.
 *
 * `ModalNavigationDrawer` in Material 3 àncora lo sheet a start; per averlo a
 * end il sottoalbero gira in layout RTL e ogni lato (drawer e contenuto) torna
 * subito in LTR. Il risultato è lo stesso drawer Material, specchiato a destra,
 * senza reimplementare gesture, scrim e predictive back.
 */
@Composable
private fun ConnectedSurface(
    state: UiState,
    viewModel: AppViewModel,
    onOpenEventLog: () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    // Promemoria non modale per POST_NOTIFICATIONS (Android 13+). Il permesso
    // di sistema si apre una sola volta, al primo connect riuscito.
    val context = LocalContext.current
    var notificationPrompt by remember { mutableStateOf<NotificationPromptUi?>(null) }
    var autoLaunchNotification by remember { mutableStateOf(true) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        autoLaunchNotification = false
        notificationPrompt = if (granted) {
            null
        } else {
            NotificationPromptUi(
                message = "Notifications off: approvals stay here in chat. " +
                    "You can enable them from Android settings.",
                actionLabel = "Settings",
                opensSettings = true,
            )
        }
    }

    LaunchedEffect(Unit) {
        if (!NotificationOptInStore.shouldAsk(context)) return@LaunchedEffect
        notificationPrompt = NotificationPromptUi(
            message = "To approve even from outside the app, allow notifications.",
            actionLabel = "Allow",
        )
        NotificationOptInStore.markAsked(context)
        delay(900)
        if (autoLaunchNotification) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val onNotificationAction: () -> Unit = {
        autoLaunchNotification = false
        if (notificationPrompt?.opensSettings == true) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        } else {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val dismissNotificationPrompt: () -> Unit = {
        autoLaunchNotification = false
        notificationPrompt = null
    }

    // TTL del catalogo (5 minuti) e task: refresh quando il pannello si apre.
    LaunchedEffect(drawerState.isOpen, state.openSession?.id) {
        if (drawerState.isOpen) {
            viewModel.loadCatalog()
            viewModel.refreshChildren()
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // A drawer chiuso il gesto Material sarebbe attivo anche lui: lo
            // spegniamo e lasciamo fare a `drawerOpenSwipe`, che riconosce lo
            // swipe senza rubare tap e scroll. Da aperto restano attivi swipe
            // di chiusura, tap sullo scrim e back.
            gesturesEnabled = drawerState.targetValue == DrawerValue.Open,
            drawerContent = {
                OmoDrawerSheet(
                    state = state,
                    drawerState = drawerState,
                    onClose = closeDrawer,
                    onRefreshCatalog = { viewModel.loadCatalog(force = true) },
                    onRefreshChildren = viewModel::refreshChildren,
                    onOpenSession = { session ->
                        viewModel.openSessionRow(session)
                        closeDrawer()
                    },
                    onSwitchAgent = viewModel::switchAgent,
                    onSwitchModel = viewModel::switchModel,
                    onActivateSkill = viewModel::activateSkill,
                    onOpenEventLog = {
                        closeDrawer()
                        onOpenEventLog()
                    },
                    onDisconnect = {
                        closeDrawer()
                        viewModel.disconnect()
                    },
                    onDismissError = viewModel::clearError,
                )
            },
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawerOpenSwipe(
                            // Con una domanda aperta il gesto resta spento: la
                            // risposta è il compito del momento e il pannello
                            // non deve finire sotto il drawer. L'icona in top
                            // bar resta la via per aprire.
                            enabled = drawerState.targetValue == DrawerValue.Closed &&
                                !(state.openSession != null && state.forms.isNotEmpty()),
                            onOpen = openDrawer,
                        ),
                ) {
                    if (state.openSession != null) {
                        ChatScreen(
                            state = state,
                            onBack = viewModel::back,
                            onLoadOlder = viewModel::loadOlder,
                            onRetry = {
                                state.openSession?.id?.let(viewModel::openSession)
                            },
                            onDraftChange = viewModel::onDraftChange,
                            onSend = viewModel::sendPrompt,
                            onInterrupt = viewModel::interrupt,
                            onAttachFile = viewModel::attach,
                            onClearAttachment = viewModel::clearAttachment,
                            onDismissAttachmentError = viewModel::dismissAttachmentError,
                            onOpenDrawer = openDrawer,
                            onReplyApproval = viewModel::replyApproval,
                            onRefreshApprovals = viewModel::refreshApprovals,
                            onReplyForm = viewModel::replyForm,
                            onCancelForm = viewModel::cancelForm,
                            onRefreshForms = viewModel::refreshForms,
                            onLoadCommands = viewModel::loadCommands,
                            onDismissError = viewModel::clearError,
                            notificationPrompt = notificationPrompt,
                            onNotificationAction = onNotificationAction,
                            onDismissNotificationPrompt = dismissNotificationPrompt,
                        )
                    } else {
                        SessionListScreen(
                            state = state,
                            onRefresh = viewModel::refreshSessions,
                            onCreateSession = viewModel::createSession,
                            onOpenSession = viewModel::openSession,
                            onOpenEventLog = onOpenEventLog,
                            onOpenDrawer = openDrawer,
                            onDisconnect = viewModel::disconnect,
                            notificationPrompt = notificationPrompt,
                            onNotificationAction = onNotificationAction,
                            onDismissNotificationPrompt = dismissNotificationPrompt,
                            onRetryConnection = viewModel::retryConnection,
                            onDismissError = viewModel::clearError,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Swipe orizzontale verso sinistra, su tutta la superficie, per aprire il
 * drawer.
 *
 * Prima il gesto valeva solo negli ultimi 22 dp dal bordo destro: preciso, ma
 * facile da mancare al primo colpo, soprattutto a chat aperta. Ora si può
 * partire da qualsiasi punto della lista o della chat; a tenere separati gli
 * altri gesti è il riconoscimento, non la zona di partenza:
 *
 * - si resta in attesa del touch slop: tap, click e long press arrivano ai
 *   figli senza nessun evento consumato;
 * - appena il movimento è verticale (o diagonale) si abbandona subito e senza
 *   consumare nulla, quindi lo scroll della chat continua a funzionare;
 * - si apre solo con uno spostamento orizzontale dominante verso sinistra
 *   (|dx| > |dy| * 1.4) di almeno 48 dp complessivi; verso destra non scatta
 *   mai;
 * - il trascinamento deve iniziare subito: un dito fermo oltre il long press e
 *   poi trascinato è una selezione di testo, non uno swipe;
 * - due dita (pinch, zoom) non aprono niente.
 *
 * Il rilevatore sta sul contenitore (non su una striscia sopra la UI) e
 * consuma solo l'evento che fa scattare l'apertura: a quel punto i figli hanno
 * già annullato il proprio gesto. Le soglie in dp sono indipendenti dalla
 * densità; slop e timeout del long press vengono dalla configurazione di
 * sistema, quindi seguono le impostazioni di accessibilità del dispositivo.
 */
private fun Modifier.drawerOpenSwipe(
    enabled: Boolean,
    onOpen: () -> Unit,
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput

    val trigger = 48.dp.toPx()
    val slop = viewConfiguration.touchSlop
    val holdTimeout = viewConfiguration.longPressTimeoutMillis

    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        // Uptime del primo movimento oltre lo slop: serve a distinguere uno
        // swipe da un drag iniziato dopo una pressione prolungata.
        var movedAt = -1L

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            // Il dito che ha iniziato il gesto deve essere ancora giù.
            val change = event.changes.firstOrNull { it.id == down.id }
                ?: return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture
            // Un secondo dito = pinch o zoom: non è il gesto di apertura.
            if (event.changes.any { it.pressed && it.id != down.id }) {
                return@awaitEachGesture
            }

            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y

            if (movedAt < 0L) {
                if (abs(dx) < slop && abs(dy) < slop) continue
                movedAt = change.uptimeMillis
                if (movedAt - down.uptimeMillis > holdTimeout) {
                    return@awaitEachGesture
                }
            }

            // Verticale o diagonale: è uno scroll (o un drag), si lascia tutto
            // ai figli senza consumare.
            if (abs(dy) >= slop && abs(dy) > abs(dx) * SWIPE_DOMINANCE) {
                return@awaitEachGesture
            }

            // Orizzontale dominante verso sinistra oltre soglia: è il gesto.
            if (dx <= -trigger && abs(dx) > abs(dy) * SWIPE_DOMINANCE) {
                change.consume()
                onOpen()
                return@awaitEachGesture
            }
        }
    }
}

/**
 * Rapporto minimo fra i due assi perché il gesto conti come orizzontale (o
 * verticale). Sotto questa dominanza il movimento è ambiguo e non si decide.
 */
private const val SWIPE_DOMINANCE = 1.4f
