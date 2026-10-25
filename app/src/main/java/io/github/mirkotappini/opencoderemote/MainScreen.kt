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
 * Navigation without library: setup → (list | chat), with the SSE log as a
 * secondary screen and the OMO drawer available from both list and chat.
 */
@Composable
fun MainScreen(viewModel: AppViewModel = viewModel()) {
    AppTheme {
        val state by viewModel.state.collectAsStateWithLifecycle()
        var showEventLog by rememberSaveable { mutableStateOf(false) }

        when {
            // M5: the server version is blocking: no setup, no list.
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
 * Connected surface: list or chat wrapped by the right-side drawer.
 *
 * `ModalNavigationDrawer` in Material 3 anchors the sheet to start; to place it at
 * end, the subtree is rendered in an RTL layout, and each side (drawer and content)
 * immediately reverts to LTR. The result is the same Material drawer, mirrored to
 * the right, without reimplementing gestures, scrim, and predictive back.
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

    // Non-modal reminder for POST_NOTIFICATIONS (Android 13+). The system
    // permission dialog opens only once, on the first successful connect.
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

    // Catalog TTL (5 minutes) and task: refresh when the panel opens.
    LaunchedEffect(drawerState.isOpen, state.openSession?.id) {
        if (drawerState.isOpen) {
            viewModel.loadCatalog()
            viewModel.refreshChildren()
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // When the drawer is closed, the Material gesture would also be active:
            // we disable it and let `drawerOpenSwipe` handle the swipe without
            // stealing taps and scrolls. When open, the close swipe, scrim tap, and
            // back remain active.
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
                            // With an open question, the gesture remains disabled:
                            // the response is the current task, and the panel must
                            // not end up under the drawer. The icon in the top bar
                            // remains the way to open it.
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
 * Swipe left across the entire surface to open the drawer.
 *
 * Previously, the gesture only worked within the last 22 dp from the right edge:
 * precise, but easy to miss on the first try, especially with a chat open. Now you
 * can start from any point in the list or chat; gesture recognition, not the
 * starting zone, keeps other gestures separate:
 *
 * - It waits for the touch slop: tap, click, and long press reach the children
 * without any event being consumed; - As soon as the movement is vertical (or
 * diagonal), it aborts immediately without consuming anything, so chat scrolling
 * continues to work; - It only opens with a dominant leftward horizontal
 * displacement (|dx| > |dy| * 1.4) of at least 48 dp total; it never triggers to
 * the right; - The drag must start immediately: a finger held beyond the long press
 * threshold and then dragged is a text selection, not a swipe; - Two fingers
 * (pinch, zoom) do not open anything.
 *
 * The detector sits on the container (not on a strip over the UI) and only consumes
 * the event that triggers the opening: at that point, the children have already
 * canceled their own gesture. The dp thresholds are density-independent; slop and
 * long press timeout come from system configuration, so they follow the device's
 * accessibility settings.
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
        // Uptime of the first movement beyond the slop: used to distinguish a swipe
        // from a drag initiated after a long press.
        var movedAt = -1L

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            // The finger that started the gesture must still be down.
            val change = event.changes.firstOrNull { it.id == down.id }
                ?: return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture
            // A second finger = pinch or zoom: it is not the open gesture.
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

            // Vertical or diagonal: it's a scroll (or a drag), so leave it all to
            // the children without consuming it.
            if (abs(dy) >= slop && abs(dy) > abs(dx) * SWIPE_DOMINANCE) {
                return@awaitEachGesture
            }

            // Dominant horizontal swipe to the left beyond threshold: this is the
            // gesture.
            if (dx <= -trigger && abs(dx) > abs(dy) * SWIPE_DOMINANCE) {
                change.consume()
                onOpen()
                return@awaitEachGesture
            }
        }
    }
}

/**
 * Minimum ratio between the two axes for the gesture to count as horizontal (or
 * vertical). Below this dominance, the movement is ambiguous and no decision is
 * made.
 */
private const val SWIPE_DOMINANCE = 1.4f
