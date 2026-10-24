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
import androidx.compose.ui.input.pointer.positionChange
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
                message = "Notifiche non attive: le approvazioni restano qui in chat. " +
                    "Puoi attivarle dalle impostazioni di Android.",
                actionLabel = "Impostazioni",
                opensSettings = true,
            )
        }
    }

    LaunchedEffect(Unit) {
        if (!NotificationOptInStore.shouldAsk(context)) return@LaunchedEffect
        notificationPrompt = NotificationPromptUi(
            message = "Per approvare anche da fuori dall'app, consenti le notifiche.",
            actionLabel = "Consenti",
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
            // A drawer chiuso il gesto Material sarebbe attivo su tutta la
            // superficie: qui apre solo la striscia di bordo (sotto). Da aperto
            // restano attivi swipe di chiusura, tap sullo scrim e back.
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
                        .drawerEdgeSwipe(
                            enabled = !drawerState.isOpen,
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
                            onOpenDrawer = openDrawer,
                            onReplyApproval = viewModel::replyApproval,
                            onRefreshApprovals = viewModel::refreshApprovals,
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
 * Swipe dal bordo destro verso sinistra per aprire il drawer.
 *
 * Solo gli ultimi 22 dp: abbastanza per il gesto, troppo poco per competere con
 * il back di sistema, che nella navigazione a gesti ha comunque la precedenza.
 * L'icona in top bar resta la via affidabile. Il rilevatore è sul contenitore
 * (non su una striscia sopra la UI) così tap e scroll dei figli non vengono
 * intercettati: si attiva solo per un trascinamento orizzontale che parte
 * dentro il bordo.
 */
private fun Modifier.drawerEdgeSwipe(
    enabled: Boolean,
    onOpen: () -> Unit,
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val edgeWidth = 22.dp.toPx()
        val trigger = 26.dp.toPx()
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        if (down.position.x < size.width - edgeWidth) return@awaitEachGesture

        var totalX = 0f
        var totalY = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.positionChange()
            totalX += delta.x
            totalY += delta.y
            if (totalX <= -trigger && abs(totalX) > abs(totalY) * 1.2f) {
                change.consume()
                onOpen()
                break
            }
        }
    }
}
