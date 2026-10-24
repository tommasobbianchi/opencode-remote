package io.github.mirkotappini.opencoderemote

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mirkotappini.opencoderemote.net.AssistantTurnUi
import io.github.mirkotappini.opencoderemote.net.MessageUi
import io.github.mirkotappini.opencoderemote.net.PartPhase
import io.github.mirkotappini.opencoderemote.net.ReasoningPartUi
import io.github.mirkotappini.opencoderemote.net.ReconnectPolicy
import io.github.mirkotappini.opencoderemote.net.Role as MessageRole
import io.github.mirkotappini.opencoderemote.net.TextPartUi
import io.github.mirkotappini.opencoderemote.net.ToolPartUi
import io.github.mirkotappini.opencoderemote.net.TurnPartUi
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

private val UserBubbleShape = RoundedCornerShape(
    topStart = 18.dp,
    topEnd = 18.dp,
    bottomEnd = 4.dp,
    bottomStart = 18.dp,
)

private val AssistantBubbleShape = RoundedCornerShape(
    topStart = 6.dp,
    topEnd = 18.dp,
    bottomEnd = 18.dp,
    bottomStart = 18.dp,
)

@Composable
fun ChatScreen(
    state: UiState,
    onBack: () -> Unit,
    onLoadOlder: () -> Unit,
    onRetry: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onOpenDrawer: () -> Unit,
    onReplyApproval: (id: String, decision: String, message: String?) -> Unit = { _, _, _ -> },
    onRefreshApprovals: () -> Unit = {},
    onDismissError: () -> Unit = {},
    notificationPrompt: NotificationPromptUi? = null,
    onNotificationAction: () -> Unit = {},
    onDismissNotificationPrompt: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val scheme = MaterialTheme.colorScheme
    val session = state.openSession
    val listState = rememberLazyListState()

    var autoScrolled by remember(session?.id) { mutableStateOf(false) }
    var pendingAnchorId by remember(session?.id) { mutableStateOf<String?>(null) }

    // UN solo turno live (UI-M6 §1). Se il resync ha già portato in `messages`
    // lo stesso id, il turno live non viene disegnato due volte.
    val liveTurn = state.chat.liveTurn
        ?.takeIf { turn -> state.messages.none { it.id == turn.id } }
    // Step assistente consecutivi = una sola bolla, come nel TUI. Il
    // raggruppamento è solo di presentazione: nessun nuovo stato nel reducer.
    // La storia è memoizzata: ai delta live si rifonde solo il turno in coda.
    val persistedRows = remember(state.messages) { buildChatRows(state.messages) }
    val rows = remember(persistedRows, liveTurn) {
        withLiveTurn(persistedRows, liveTurn)
    }
    val totalRows = rows.size + (if (state.hasMore) 1 else 0)
    var detailRequest by remember(session?.id) { mutableStateOf<DetailRequest?>(null) }

    // Auto-scroll agganciato al contenuto del turno, mai alla crescita
    // dell'output di un tool (§4.4). Il throttle è dato da conflate + delay.
    val scrollSnapshot = rememberUpdatedState(
        ScrollSnapshot(
            rows = totalRows,
            signal = liveScrollSignal(liveTurn),
            loading = state.messagesLoading,
        ),
    )
    LaunchedEffect(session?.id) {
        snapshotFlow { scrollSnapshot.value }
            .distinctUntilChanged()
            .conflate()
            .collect { snapshot ->
                if (snapshot.loading) return@collect
                delay(150)
                val rowCount = scrollSnapshot.value.rows
                if (rowCount == 0) return@collect
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                val atBottom = info.totalItemsCount == 0 ||
                    lastVisible >= info.totalItemsCount - 2
                if (atBottom || !autoScrolled) {
                    listState.scrollToItem(rowCount - 1)
                    autoScrolled = true
                }
            }
    }

    LaunchedEffect(state.messages.size, state.messagesLoading) {
        val anchor = pendingAnchorId ?: return@LaunchedEffect
        if (state.messagesLoading) return@LaunchedEffect
        val index = rows.indexOfFirst { row -> row.holdsMessage(anchor) }
        if (index >= 0) {
            listState.scrollToItem(index + if (state.hasMore) 1 else 0)
        }
        pendingAnchorId = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            ChatHeader(
                title = session?.title ?: "Sessione",
                meta = listOfNotNull(
                    session?.agent?.takeIf { it.isNotBlank() },
                    session?.modelId?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                agent = session?.agent?.takeIf { it.isNotBlank() },
                readOnly = state.openReadOnly,
                canGoBackToParent = state.canGoBackToParent,
                state = state,
                pendingApprovals = state.approvalCount(session?.id),
                onBack = onBack,
                onOpenDrawer = onOpenDrawer,
            )

            if (notificationPrompt != null) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    NotificationNoticeBar(
                        prompt = notificationPrompt,
                        onAction = onNotificationAction,
                        onDismiss = onDismissNotificationPrompt,
                        modifier = Modifier.widthIn(max = Layout.chatMaxWidth),
                    )
                }
            }

            val reconnecting = state.streamState as? StreamState.Reconnecting
            val connecting = state.streamState is StreamState.Connecting
            val suspended = state.retrySuspended || reconnecting?.suspended == true
            // Un solo avviso: se c'è già `error`, il countdown viene aggiunto
            // al suo messaggio. Con la rete assente vince il testo dedicato.
            if ((reconnecting != null || connecting) && (state.error == null || suspended)) {
                ReconnectBanner(
                    text = when {
                        suspended -> ReconnectPolicy.NETWORK_WAITING
                        reconnecting != null ->
                            ReconnectPolicy.bannerLabel(state.reconnectInSeconds, false)
                        else -> ReconnectPolicy.NEW_ATTEMPT
                    },
                )
            } else if (state.streamState is StreamState.Idle && state.error == null) {
                StreamIdleBanner()
            }

            state.error?.takeUnless {
                suspended && it == ReconnectPolicy.NETWORK_ABSENT
            }?.let { message ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = Layout.chatMaxWidth)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        if (message.contains("no longer valid", ignoreCase = true)) {
                            ApprovalExpiredNotice(onDismiss = onDismissError)
                        } else {
                            ErrorBanner(
                                title = "Operation failed",
                                message = if (reconnecting != null && !suspended) {
                                    "$message · riprovo tra " +
                                        "${state.reconnectInSeconds.coerceAtLeast(0)}s"
                                } else {
                                    message
                                },
                                onAction = onRetry,
                                onDismiss = onDismissError,
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .widthIn(max = Layout.chatMaxWidth)
                        .fillMaxHeight()
                        .fillMaxWidth(),
                ) {
                    val bubbleMax = ((maxWidth - 32.dp) * 0.85f).coerceAtLeast(220.dp)
                    when {
                        state.messagesLoading && state.messages.isEmpty() -> {
                            ChatSkeleton(bubbleMax)
                        }

                        rows.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                EmptyState(
                                    symbol = "›_",
                                    title = "Nessun messaggio",
                                    body = if (state.openReadOnly) {
                                        "The child session has no messages yet."
                                    } else {
                                        "Write your first prompt below: the reply " +
                                            "will arrive in real time."
                                    },
                                    action = {
                                        TextButton(
                                            onClick = onBack,
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        ) {
                                            Text(
                                                if (state.canGoBackToParent) {
                                                    "Torna al padre"
                                                } else {
                                                    "Back to sessions"
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        }

                        else -> {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 12.dp,
                                    bottom = 20.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (state.hasMore) {
                                    item(key = "load-older") {
                                        LoadOlderControl(
                                            loading = state.messagesLoading,
                                            onClick = {
                                                val first = listState.firstVisibleItemIndex
                                                val rowIndex =
                                                    (first - (if (state.hasMore) 1 else 0))
                                                        .coerceAtLeast(0)
                                                pendingAnchorId = rows
                                                    .getOrNull(rowIndex)
                                                    ?.anchorId()
                                                onLoadOlder()
                                            },
                                        )
                                    }
                                }

                                // Una riga per turno: gli step assistente
                                // consecutivi sono già fusi in `rows`. La
                                // chiave resta `turn:<primoId>`, quindi il
                                // passaggio live → resync non muove l'item.
                                items(
                                    items = rows,
                                    key = { row -> row.key },
                                ) { row ->
                                    when (row) {
                                        is ChatRow.Turn -> AssistantTurn(
                                            turn = row.turn,
                                            bubbleMax = bubbleMax,
                                            onOpenDetail = { detailRequest = it },
                                        )

                                        is ChatRow.Single -> MessageRow(
                                            message = row.message,
                                            bubbleMax = bubbleMax,
                                            running = state.running,
                                            onOpenDetail = { detailRequest = it },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = state.approvals.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    ApprovalPanel(
                        approvals = state.approvals,
                        loading = state.approvalsLoading,
                        error = state.approvalsError,
                        onReply = onReplyApproval,
                        onRefresh = onRefreshApprovals,
                        modifier = Modifier.widthIn(max = Layout.chatMaxWidth),
                    )
                }
            }

            if (state.openReadOnly) {
                ReadOnlyFooter(
                    running = state.running,
                    canGoBackToParent = state.canGoBackToParent,
                    onInterrupt = onInterrupt,
                )
            } else {
                Composer(
                    draft = state.draft,
                    onDraftChange = onDraftChange,
                    connected = state.isConnected,
                    running = state.running,
                    onSend = onSend,
                    onInterrupt = onInterrupt,
                )
            }
        }

        // Un solo foglio per tutta la chat: comando/output dei tool e
        // ragionamento lungo passano da qui (§2.5).
        detailRequest?.let { request ->
            TurnDetailSheet(
                request = request,
                onDismiss = { detailRequest = null },
            )
        }
    }
}

@Composable
private fun ChatHeader(
    title: String,
    meta: String,
    agent: String?,
    readOnly: Boolean,
    canGoBackToParent: Boolean,
    state: UiState,
    pendingApprovals: Int,
    onBack: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val backLabel = if (canGoBackToParent) "Back to parent" else "Back to sessions"
    val reconnecting = state.streamState is StreamState.Reconnecting ||
        state.streamState is StreamState.Connecting
    val stream = streamStatusUi(state.streamState, state.reconnectInSeconds, state.retrySuspended)
    val status = when {
        state.running -> "in corso" to oc.interactive
        reconnecting -> stream.label to oc.warningInk
        state.streamState is StreamState.Idle -> stream.label to oc.mutedText
        state.sessionOutcome != null ->
            (outcomeLabel(state.sessionOutcome) ?: stream.label) to oc.mutedText
        else -> stream.label to oc.success
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = Layout.chatMaxWidth)
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = backLabel },
                ) {
                    GlyphChevron(
                        direction = ChevronDirection.Left,
                        size = 20.dp,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = scheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (readOnly) {
                        ReadOnlyHeaderLabel(agent = agent)
                    } else if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                StatusPill(
                    label = status.first,
                    color = status.second,
                    pulsing = state.running || reconnecting,
                )
                if (pendingApprovals > 0) {
                    Spacer(Modifier.width(6.dp))
                    ApprovalBadge(pendingApprovals)
                }
                Spacer(Modifier.width(2.dp))
                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = "Open the agents, skills and MCP panel"
                        },
                ) {
                    GlyphSidebar(size = 20.dp)
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = scheme.outlineVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ReadOnlyHeaderLabel(agent: String?) {
    val oc = OpenColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            color = oc.interactive.copy(alpha = 0.12f),
            contentColor = oc.interactive,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, oc.interactive.copy(alpha = 0.28f)),
        ) {
            Text(
                text = agent?.let { "subagent $it" } ?: "child session",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "sola lettura",
            style = MaterialTheme.typography.labelMedium,
            color = oc.mutedText,
            maxLines = 1,
        )
    }
}

@Composable
private fun StreamIdleBanner() {
    val oc = OpenColors.current
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = Layout.chatMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(oc.mutedText, size = 5.dp)
            Text(
                text = "SSE stopped · data not up to date",
                style = MaterialTheme.typography.labelMedium,
                color = oc.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReconnectBanner(text: String) {
    val oc = OpenColors.current
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = Layout.chatMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(oc.warningInk, size = 5.dp, pulsing = true)
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = oc.warningInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    connected: Boolean,
    running: Boolean,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    Surface(color = scheme.surfaceContainerLow) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
        ) {
            HorizontalDivider(
                thickness = 1.dp,
                color = oc.border.copy(alpha = 0.75f),
            )
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Row(
                    modifier = Modifier
                        .widthIn(max = Layout.chatMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        color = oc.formField,
                        border = BorderStroke(
                            1.dp,
                            if (connected) oc.border else oc.border.copy(alpha = 0.5f),
                        ),
                    ) {
                        BasicTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            enabled = connected,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = oc.text,
                            ),
                            cursorBrush = SolidColor(oc.interactive),
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                        .heightIn(min = 24.dp),
                                ) {
                                    if (draft.isEmpty()) {
                                        Text(
                                            text = if (connected) {
                                                "Scrivi un messaggio…"
                                            } else {
                                                "Non connesso"
                                            },
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = scheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                    }

                    if (running) {
                        StopButton(onClick = onInterrupt)
                    } else {
                        SendButton(
                            enabled = connected && draft.isNotBlank(),
                            onClick = onSend,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sostituisce il composer per un task figlio: niente prompt né invio, il
 * `draft` del padre resta intatto. "Ferma" agisce su questa sessione.
 */
@Composable
private fun ReadOnlyFooter(
    running: Boolean,
    canGoBackToParent: Boolean,
    onInterrupt: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    Surface(color = scheme.surfaceContainerLow) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
        ) {
            HorizontalDivider(
                thickness = 1.dp,
                color = oc.border.copy(alpha = 0.75f),
            )
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Row(
                    modifier = Modifier
                        .widthIn(max = Layout.chatMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Eyebrow(text = "sola lettura", color = oc.mutedText)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (canGoBackToParent) {
                            "back to parent to write"
                        } else {
                            "child session without composer"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (running) {
                        Spacer(Modifier.width(8.dp))
                        StopButton(onClick = onInterrupt)
                    }
                }
            }
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) scheme.primary else scheme.surfaceContainerHigh,
        contentColor = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = "Invia prompt" },
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GlyphArrowRight(size = 18.dp)
        }
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = scheme.tertiaryContainer,
        contentColor = scheme.onTertiaryContainer,
        border = BorderStroke(1.dp, scheme.tertiary.copy(alpha = 0.35f)),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = "Interrompi esecuzione" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlyphStop(size = 12.dp)
            Text(text = "Ferma", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ---------------------------------------------------------------------------
// Turno assistente — UI-M6 §1/§2.
//
// Un solo entry point per il turno live e per il messaggio persistito: stessa
// bolla, stesse part, stesse chiavi. Al resync il reducer sostituisce il turno
// con lo stesso id, quindi l'item `turn:<id>` non salta e le part espanse
// restano tali quando la loro `key` coincide.
// ---------------------------------------------------------------------------

@Composable
private fun AssistantTurn(
    turn: AssistantTurnUi,
    bubbleMax: Dp,
    onOpenDetail: (DetailRequest) -> Unit,
) {
    // Con turno concluso e nessuna part la bolla non esiste (§2.4).
    if (turn.parts.isEmpty() && !turn.running) return

    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val parts = turn.parts
    val anyActive = turn.running && parts.any {
        it.phase == PartPhase.Streaming || it.phase == PartPhase.Running
    }

    // Un solo ticker per turno, attivo solo con part in corso (§4.4).
    val now = produceState(
        initialValue = System.currentTimeMillis(),
        key1 = turn.id,
        key2 = anyActive,
    ) {
        while (anyActive) {
            value = System.currentTimeMillis()
            delay(100)
        }
    }

    // Un solo cursore per turno: l'ultima part text ancora in streaming.
    // Lampeggio a scatti 1 ↔ 0,35 su 900 ms: il glifo resta identico, quindi
    // il testo non viene rimisurato a ogni frame (niente relayout in streaming).
    val cursorKey = parts.lastOrNull {
        it is TextPartUi && it.phase == PartPhase.Streaming
    }?.key
    val cursorAlpha: State<Float>? = if (cursorKey != null) {
        if (ValueAnimator.areAnimatorsEnabled()) {
            produceState(initialValue = 1f, key1 = cursorKey) {
                while (true) {
                    delay(450)
                    value = if (value > 0.5f) 0.35f else 1f
                }
            }
        } else {
            remember { mutableStateOf(1f) }
        }
    } else {
        null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = bubbleMax),
                color = scheme.surfaceContainer,
                contentColor = scheme.onSurface,
                shape = AssistantBubbleShape,
                border = BorderStroke(
                    1.dp,
                    if (turn.running) {
                        oc.interactive.copy(alpha = 0.30f)
                    } else {
                        oc.border.copy(alpha = 0.55f)
                    },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (parts.isEmpty()) {
                        if (turn.running) ThinkingPlaceholder()
                    } else {
                        parts.forEachIndexed { index, part ->
                            key(part.key) {
                                when (part) {
                                    is ReasoningPartUi -> ReasoningBlock(
                                        part = part,
                                        now = now,
                                        onOpenDetail = onOpenDetail,
                                    )

                                    is ToolPartUi -> ToolCard(
                                        part = part,
                                        now = now,
                                        onOpenDetail = onOpenDetail,
                                    )

                                    is TextPartUi -> SelectionContainer {
                                        MarkdownBody(
                                            text = part.text,
                                            cursorAlpha = cursorAlpha
                                                ?.takeIf { part.key == cursorKey },
                                        )
                                    }
                                }
                            }
                            // Pausa fra due part: niente tre puntini, un solo
                            // arco con grazia di 400 ms (§2.4).
                            if (index == parts.lastIndex && turn.running && !anyActive) {
                                ThinkingPlaceholder()
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, start = 3.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            MessageTimestamp(turn.createdAt)
        }
    }
}

/** Fallback §2.4: nessuna part attiva, ma il turno sta ancora lavorando. */
@Composable
private fun ThinkingPlaceholder() {
    val oc = OpenColors.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(400)
        visible = true
    }
    if (!visible) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = "sta scrivendo…" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WorkingIndicator(
            size = 12.dp,
            color = oc.success,
            description = "attesa",
        )
        Text(
            text = "sta scrivendo…",
            style = MaterialTheme.typography.labelMedium,
            color = oc.mutedText,
        )
    }
}

/** §2.1 — ragionamento collassato di default, anteprima e limite righe. */
@Composable
private fun ReasoningBlock(
    part: ReasoningPartUi,
    now: State<Long>,
    onOpenDetail: (DetailRequest) -> Unit,
) {
    var expanded by rememberSaveable(part.key) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val streaming = part.phase == PartPhase.Streaming
    val trimmed = remember(part.text) { part.text.trim() }
    val lineCount = remember(trimmed) {
        if (trimmed.isEmpty()) 0 else trimmed.lines().size
    }
    val limited = remember(trimmed) { limitLines(trimmed, REASONING_VISIBLE_LINES) }
    val preview = remember(trimmed) {
        trimmed.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
    }
    val duration = durationLabel(
        startedAt = part.startedAt,
        endedAt = part.endedAt,
        now = now.value,
        minimumMs = 0L,
    )

    // TUI: colore base in streaming, warningInk quando espanso, 60% se
    // collassato e già concluso. Nel tema chiaro il 60% scenderebbe sotto AA,
    // quindi l'inchiostro resta pieno.
    val headerColor = when {
        streaming -> oc.text
        expanded -> oc.warningInk
        oc.dark -> oc.warningInk.copy(alpha = 0.6f)
        else -> oc.warningInk
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) {
                        "Nascondi ragionamento"
                    } else {
                        "Mostra ragionamento"
                    },
                ) { expanded = !expanded }
                .semantics {
                    stateDescription = if (expanded) "espanso" else "collassato"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides headerColor) {
                GlyphChevron(
                    direction = if (expanded) ChevronDirection.Down else ChevronDirection.Right,
                    size = 14.dp,
                )
            }
            Spacer(Modifier.width(6.dp))
            if (streaming) {
                StatusDot(oc.warningInk, size = 5.dp, pulsing = true)
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text = "Ragionamento",
                style = MaterialTheme.typography.labelMedium,
                color = headerColor,
            )
            Text(
                text = " · " + (duration ?: lineLabel(lineCount)),
                style = MaterialTheme.typography.labelMedium,
                color = oc.mutedText,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (expanded) "close" else "open",
                style = MaterialTheme.typography.labelSmall,
                color = oc.mutedText,
            )
        }

        // In streaming il corpo non si apre da solo: una riga di anteprima
        // dà il segno di vita senza far saltare la chat a ogni token.
        if (!expanded && streaming && preview.isNotBlank()) {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = oc.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = scheme.surfaceContainerLowest,
                    contentColor = oc.mutedText,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(
                        1.dp,
                        oc.warningInk.copy(alpha = if (streaming) 0.35f else 0.22f),
                    ),
                ) {
                    SelectionContainer {
                        Text(
                            text = limited.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = oc.mutedText,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                if (limited.truncated) {
                    ShowAllRow(
                        label = "show all · " + lineLabel(lineCount),
                        onClickLabel = "Mostra tutto il ragionamento",
                        onClick = {
                            onOpenDetail(
                                DetailRequest(
                                    title = "ragionamento",
                                    sections = listOf(
                                        DetailSection("ragionamento", trimmed),
                                    ),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

/** §2.2 — card del tool, sempre aperta, senza dump JSON. */
@Composable
private fun ToolCard(
    part: ToolPartUi,
    now: State<Long>,
    onOpenDetail: (DetailRequest) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val status = toolPhaseUi(part)
    val duration = toolDurationLabel(part, now.value)
    val output = remember(part.output) { outputTail(part.output) }
    val command = remember(part.command) { commandHead(part.command) }
    val denied = !part.executed || part.phase == PartPhase.Denied
    val showAllLines = if (output.totalLines > 0) output.totalLines else command.totalLines

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val nameMax = maxWidth * 0.4f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(scheme.surfaceContainerLow)
                .border(
                    width = 1.dp,
                    color = oc.border.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small,
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                StatusDot(
                    color = status.color,
                    size = 5.dp,
                    pulsing = status.pulsing,
                    modifier = Modifier.semantics {
                        contentDescription = status.talkBack
                    },
                )
                Text(
                    text = part.name.ifBlank { "tool" },
                    style = monoStyle(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        weight = FontWeight.Medium,
                    ),
                    color = oc.interactive,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = nameMax),
                )
                Text(
                    text = status.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = status.color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                part.exitCode?.takeIf { it != 0 }?.let { code ->
                    Text(
                        text = "exit $code",
                        style = monoStyle(fontSize = 10.sp, lineHeight = 13.sp),
                        color = oc.error,
                        maxLines = 1,
                    )
                }
                if (part.truncated) {
                    Text(
                        text = "output troncato",
                        style = MaterialTheme.typography.labelSmall,
                        color = oc.lineNumber,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                duration?.let {
                    Text(
                        text = it,
                        style = monoStyle(fontSize = 10.sp, lineHeight = 13.sp),
                        color = oc.lineNumber,
                        maxLines = 1,
                    )
                }
            }

            if (command.text.isNotBlank()) {
                SelectionContainer {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        if (isShellTool(part.name)) {
                            Text(
                                text = "$ ",
                                style = monoStyle(fontSize = 12.sp, lineHeight = 17.sp),
                                color = oc.lineNumber,
                            )
                        }
                        Text(
                            text = command.text,
                            style = monoStyle(fontSize = 12.sp, lineHeight = 17.sp),
                            color = oc.text,
                            maxLines = COMMAND_VISIBLE_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (denied) {
                Text(
                    text = "not executed",
                    style = MaterialTheme.typography.bodySmall,
                    color = oc.mutedText,
                )
            } else {
                if (output.text.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = output.text,
                            style = monoStyle(fontSize = 11.5.sp, lineHeight = 17.sp),
                            color = oc.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                        )
                    }
                } else if (isToolTerminal(part.phase)) {
                    Text(
                        text = "(nessun output)",
                        style = monoStyle(fontSize = 11.5.sp, lineHeight = 17.sp),
                        color = oc.lineNumber,
                    )
                }
                if (output.totalLines > 0 || command.truncated) {
                    ShowAllRow(
                        label = "show all · " + lineLabel(showAllLines),
                        onClickLabel = "Mostra tutto l'output di " +
                            part.name.ifBlank { "tool" },
                        onClick = {
                            onOpenDetail(
                                DetailRequest(
                                    title = "output · " + part.name.ifBlank { "tool" },
                                    sections = buildList {
                                        if (command.text.isNotBlank()) {
                                            add(
                                                DetailSection(
                                                    label = "command",
                                                    text = part.command.orEmpty(),
                                                ),
                                            )
                                        }
                                        part.output?.takeIf { it.isNotBlank() }?.let {
                                            add(DetailSection(label = "output", text = it))
                                        }
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

/** Riga "mostra tutto · N righe": target 48dp, testo interattivo (§2.2/§2.5). */
@Composable
private fun ShowAllRow(
    label: String,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    val oc = OpenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = onClickLabel,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = oc.interactive,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Foglio di dettaglio condiviso (§2.5): comando/output del tool, ragionamento.
// ---------------------------------------------------------------------------

private data class DetailSection(val label: String, val text: String)

private data class DetailRequest(val title: String, val sections: List<DetailSection>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TurnDetailSheet(
    request: DetailRequest,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Il foglio non supera l'80% dello schermo; se il contenuto è corto resta
    // corto (nessun vuoto sopra il testo).
    val bodyMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp - 56.dp
    var copiedLabel by remember(request) { mutableStateOf<String?>(null) }

    LaunchedEffect(copiedLabel) {
        if (copiedLabel != null) {
            delay(1_500)
            copiedLabel = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = request.title.uppercase(Locale.ITALIAN),
                    style = monoStyle(fontSize = 11.sp, lineHeight = 15.sp)
                        .copy(letterSpacing = 1.5.sp),
                    color = oc.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Chiudi",
                            onClick = onDismiss,
                        )
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "close",
                        style = MaterialTheme.typography.labelSmall,
                        color = oc.mutedText,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .heightIn(max = bodyMaxHeight.coerceAtLeast(120.dp))
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                request.sections.forEach { section ->
                    DetailSectionBlock(
                        section = section,
                        copied = copiedLabel == section.label,
                        onCopy = {
                            copyToClipboard(context, section.label, section.text)
                            copiedLabel = section.label
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailSectionBlock(
    section: DetailSection,
    copied: Boolean,
    onCopy: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow(
                text = section.label,
                color = oc.mutedText,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Copia ${section.label}",
                        onClick = onCopy,
                    )
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (copied) "copiato" else "copia",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copied) oc.success else oc.interactive,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceContainerLowest, MaterialTheme.shapes.small)
                .border(
                    width = 1.dp,
                    color = oc.border.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small,
                )
                .padding(10.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            SelectionContainer {
                Text(
                    text = section.text,
                    style = monoStyle(fontSize = 11.5.sp, lineHeight = 17.sp),
                    color = oc.text,
                    softWrap = false,
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
private fun LoadOlderControl(
    loading: Boolean,
    onClick: () -> Unit,
) {
    val oc = OpenColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onClick,
            enabled = !loading,
            shape = CircleShape,
            color = Color.Transparent,
            contentColor = oc.mutedText,
            border = BorderStroke(1.dp, oc.border),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = oc.interactive,
                    )
                } else {
                    GlyphChevron(
                        direction = ChevronDirection.Up,
                        size = 15.dp,
                    )
                }
                Text(
                    text = if (loading) "Carico…" else "Carica precedenti",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: MessageUi,
    bubbleMax: Dp,
    running: Boolean,
    onOpenDetail: (DetailRequest) -> Unit,
) {
    when (message.role) {
        MessageRole.User -> UserMessage(message, bubbleMax)
        MessageRole.Assistant -> AssistantTurn(
            turn = message.asTurn(running),
            bubbleMax = bubbleMax,
            onOpenDetail = onOpenDetail,
        )
        MessageRole.Meta -> MetaDivider(message.metaLabel ?: "evento")
    }
}

/** Chiave di lista stabile: gli assistenti usano `turn:<id>` come il turno live. */
private fun MessageUi.listItemKey(): String = when (role) {
    MessageRole.Assistant -> "turn:$id"
    else -> "message:$id"
}

// ---------------------------------------------------------------------------
// Raggruppamento di presentazione degli step assistente.
//
// Il server emette un `assistantMessageID` per step: reasoning + tool in uno,
// testo finale nel successivo. Nel TUI è un solo turno, quindi qui gli step
// assistente consecutivi vengono fusi in una sola bolla. Non è stato del
// reducer: è una proiezione della lista al momento del disegno.
// ---------------------------------------------------------------------------

/** Riga della chat: messaggio singolo (utente/meta) o turno fuso. */
private sealed interface ChatRow {
    val key: String

    data class Single(val message: MessageUi) : ChatRow {
        override val key: String = message.listItemKey()
    }

    data class Turn(val turn: AssistantTurnUi) : ChatRow {
        override val key: String = "turn:${turn.id}"
    }
}

/** Id usato come ancora per "carica precedenti": il primo messaggio della riga. */
private fun ChatRow.anchorId(): String = when (this) {
    is ChatRow.Single -> message.id
    is ChatRow.Turn -> turn.id
}

/**
 * La riga contiene [id]? Un turno può aver assorbito il messaggio ancora
 * precedente (step assistente consecutivi): in quel caso si ancora al turno.
 */
private fun ChatRow.holdsMessage(id: String): Boolean = when (this) {
    is ChatRow.Single -> message.id == id
    is ChatRow.Turn ->
        turn.id == id || turn.parts.any { it.key.startsWith("$id|") }
}

/**
 * Fonde gli step assistente consecutivi in un solo [AssistantTurnUi], fermandosi
 * a ogni messaggio non assistente (utente o divisore). L'id del turno è quello
 * del primo step: è la chiave `turn:<primoId>` che il resync ritrova identica.
 *
 * Le part key si ripetono fra messaggi diversi (`reasoning:0`, `text:0`, …),
 * quindi ogni part viene ri-chiavata con l'id del messaggio di origine. Il
 * risultato è stabile live → materializzato → resync, perché l'id del messaggio
 * non cambia in quel passaggio.
 */
private fun buildChatRows(
    messages: List<MessageUi>,
): List<ChatRow> {
    val rows = ArrayList<ChatRow>(messages.size + 1)

    var firstId: String? = null
    var merged = ArrayList<TurnPartUi>()
    var createdAt: Long? = null
    var lastRunning = false
    var hasActive = false

    fun flush() {
        val id = firstId
        // Gruppo senza part e non in corso: nessuna bolla (come §2.4).
        if (id != null && (merged.isNotEmpty() || lastRunning || hasActive)) {
            rows += ChatRow.Turn(
                AssistantTurnUi(
                    id = id,
                    parts = merged,
                    createdAt = createdAt,
                    running = lastRunning || hasActive,
                    revision = 0,
                ),
            )
        }
        firstId = null
        merged = ArrayList()
        createdAt = null
        lastRunning = false
        hasActive = false
    }

    fun append(
        id: String,
        sourceParts: List<TurnPartUi>,
        time: Long?,
        running: Boolean,
    ) {
        if (firstId == null) firstId = id
        merged.addAll(rekeyParts(id, sourceParts))
        if (sourceParts.any { it.phase.isLive() }) {
            hasActive = true
        }
        // Il timestamp della bolla è quello dell'ultimo step.
        if (time != null) createdAt = time
        lastRunning = running
    }

    messages.forEach { message ->
        when (message.role) {
            MessageRole.Assistant -> append(
                id = message.id,
                sourceParts = message.parts,
                time = message.createdAt,
                running = false,
            )

            else -> {
                flush()
                rows += ChatRow.Single(message)
            }
        }
    }
    flush()
    return rows
}

/**
 * Aggiunge il turno live in coda. Se l'ultima riga è già un turno assistente
 * (step precedenti della stessa esecuzione, o due esecuzioni senza prompt in
 * mezzo) il live viene fuso lì, così la bolla resta una e la chiave
 * `turn:<primoId>` non cambia. Altrimenti il live è un turno a sé.
 */
private fun withLiveTurn(
    rows: List<ChatRow>,
    live: AssistantTurnUi?,
): List<ChatRow> {
    if (live == null) return rows
    if (live.parts.isEmpty() && !live.running) return rows
    // Difesa: il live con lo stesso id di un messaggio è già rappresentato.
    if (rows.any { it is ChatRow.Turn && it.turn.id == live.id }) return rows

    val liveParts = rekeyParts(live.id, live.parts)
    val liveActive = liveParts.any { it.phase.isLive() }
    val last = rows.lastOrNull()
    if (last is ChatRow.Turn) {
        val merged = last.turn.copy(
            parts = last.turn.parts + liveParts,
            createdAt = live.createdAt ?: last.turn.createdAt,
            finish = live.finish ?: last.turn.finish,
            running = last.turn.running || live.running || liveActive,
            revision = live.revision,
        )
        return rows.dropLast(1) + ChatRow.Turn(merged)
    }
    return rows + ChatRow.Turn(
        AssistantTurnUi(
            id = live.id,
            parts = liveParts,
            createdAt = live.createdAt,
            finish = live.finish,
            running = live.running || liveActive,
            revision = live.revision,
        ),
    )
}

private fun PartPhase.isLive(): Boolean =
    this == PartPhase.Streaming || this == PartPhase.Running

/** Ordina per `order` e ri-chiava: la part key si ripete fra messaggi diversi. */
private fun rekeyParts(id: String, parts: List<TurnPartUi>): List<TurnPartUi> =
    parts.sortedBy { it.order }.map { it.withDisplayKey("$id|${it.key}") }

/** Re-key stabile: id del messaggio di origine + key già stabile della part. */
private fun TurnPartUi.withDisplayKey(key: String): TurnPartUi = when (this) {
    is ReasoningPartUi -> copy(key = key)
    is TextPartUi -> copy(key = key)
    is ToolPartUi -> copy(key = key)
}

/**
 * Messaggio persistito → stesso tipo del turno live (§1). `running` è vero solo
 * se il resync ha portato part ancora attive: un messaggio già chiuso non mostra
 * mai il fallback "sta scrivendo…".
 */
private fun MessageUi.asTurn(sessionRunning: Boolean): AssistantTurnUi {
    val active = parts.any {
        it.phase == PartPhase.Streaming || it.phase == PartPhase.Running
    }
    return AssistantTurnUi(
        id = id,
        parts = parts,
        createdAt = createdAt,
        finish = finish,
        running = sessionRunning && finish == null && active,
        revision = 0,
    )
}

@Composable
private fun UserMessage(message: MessageUi, bubbleMax: Dp) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = bubbleMax),
                color = scheme.primaryContainer,
                contentColor = scheme.onPrimaryContainer,
                shape = UserBubbleShape,
                border = BorderStroke(1.dp, oc.border),
            ) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    SelectionContainer {
                        Text(
                            text = message.text.ifBlank { "…" },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, end = 3.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (message.pending) {
                Text(
                    text = "in coda",
                    style = MaterialTheme.typography.labelSmall,
                    color = oc.info,
                )
                Spacer(Modifier.width(8.dp))
            }
            MessageTimestamp(message.createdAt)
        }
    }
}

@Composable
private fun MessageTimestamp(epochMs: Long?) {
    val text = messageTime(epochMs)
    if (text.isBlank()) return
    Text(
        text = text,
        style = monoStyle(fontSize = 10.sp, lineHeight = 13.sp),
        color = OpenColors.current.mutedText,
    )
}

@Composable
private fun MetaDivider(label: String) {
    val oc = OpenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = oc.border.copy(alpha = 0.5f),
        )
        Text(
            text = label.uppercase(java.util.Locale.ITALIAN),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
            color = oc.mutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 10.dp)
                .widthIn(max = 240.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = oc.border.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun ChatSkeleton(bubbleMax: Dp) {
    val alpha = rememberSkeletonAlpha()
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        repeat(4) { index ->
            val isUser = index % 2 == 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = bubbleMax)
                        .fillMaxWidth(if (isUser) 0.58f else 0.84f)
                        .clip(if (isUser) UserBubbleShape else AssistantBubbleShape)
                        .background(
                            if (isUser) {
                                scheme.primaryContainer.copy(alpha = alpha * 0.55f)
                            } else {
                                scheme.surfaceContainer.copy(alpha = alpha)
                            },
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SkeletonBar(widthFraction = 0.94f, height = 11.dp, alpha = alpha)
                    SkeletonBar(widthFraction = 0.62f, height = 11.dp, alpha = alpha)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Markdown minimale per la chat: titoli, strong, corsivo, codice inline e
// blocchi, citazioni, link. Nessuna dipendenza: si applicano solo gli stili
// che il TUI mostra, senza introdurre un parser completo.
// ---------------------------------------------------------------------------

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
}

private fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = source.replace("\r\n", "\n").lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        when {
            line.trimStart().startsWith("```") -> {
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.add(lines[i])
                    i++
                }
                if (i < lines.size) i++
                blocks.add(MdBlock.Code(code.joinToString("\n")))
            }

            line.isBlank() -> i++

            line.trim() == "---" || line.trim() == "***" || line.trim() == "___" -> {
                blocks.add(MdBlock.Rule)
                i++
            }

            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length
                if (level in 1..6 && line.getOrNull(level) == ' ') {
                    blocks.add(MdBlock.Heading(level, line.drop(level).trim()))
                } else {
                    blocks.add(MdBlock.Paragraph(line))
                }
                i++
            }

            line.trimStart().startsWith("> ") -> {
                blocks.add(MdBlock.Quote(line.trimStart().removePrefix("> ")))
                i++
            }

            else -> {
                blocks.add(MdBlock.Paragraph(line))
                i++
            }
        }
    }
    return blocks
}

/**
 * Markdown memoizzato su (lunghezza, hash) del testo (§4.4): il cursore non fa
 * parte del testo parsato, quindi il blink non ricalcola il markdown.
 */
private object MarkdownCache {
    private const val MAX_ENTRIES = 32
    private val entries = LinkedHashMap<String, List<MdBlock>>()

    fun blocks(source: String): List<MdBlock> {
        val key = source.length.toString() + ":" + source.hashCode()
        synchronized(entries) {
            entries[key]?.let { return it }
            val parsed = parseMarkdown(source.trim())
            if (entries.size >= MAX_ENTRIES) {
                entries.remove(entries.keys.first())
            }
            entries[key] = parsed
            return parsed
        }
    }
}

private fun withCursorSpan(
    text: AnnotatedString,
    oc: OpenPalette,
    alpha: Float,
): AnnotatedString = buildAnnotatedString {
    append(text)
    withStyle(SpanStyle(color = oc.interactive.copy(alpha = alpha))) {
        append("▌")
    }
}

@Composable
private fun MarkdownBody(
    text: String,
    cursorAlpha: State<Float>? = null,
) {
    val oc = OpenColors.current
    val blocks = remember(text) { MarkdownCache.blocks(text) }
    // Letto qui: il blink invalida solo questa part text, non tutto il turno.
    val alpha: Float? = cursorAlpha?.value
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { index, block ->
            val withCursor = alpha != null && index == blocks.lastIndex
            when (block) {
                is MdBlock.Heading -> {
                    val base = remember(block, oc) { inlineMarkdown(block.text, oc) }
                    val shown = if (withCursor) {
                        remember(base, alpha) { withCursorSpan(base, oc, alpha ?: 1f) }
                    } else {
                        base
                    }
                    Text(
                        text = shown,
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                        color = oc.markdownHeading,
                    )
                }

                is MdBlock.Code -> {
                    val base = remember(block) { AnnotatedString(block.text) }
                    val shown = if (withCursor) {
                        remember(base, alpha) { withCursorSpan(base, oc, alpha ?: 1f) }
                    } else {
                        base
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = oc.formField,
                        contentColor = oc.text,
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, oc.border.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            text = shown,
                            style = monoStyle(fontSize = 11.5.sp, lineHeight = 17.sp),
                            color = oc.text,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                is MdBlock.Quote -> {
                    val base = remember(block, oc) { inlineMarkdown(block.text, oc) }
                    val shown = if (withCursor) {
                        remember(base, alpha) { withCursorSpan(base, oc, alpha ?: 1f) }
                    } else {
                        base
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .heightIn(min = 18.dp)
                                .background(oc.markdownQuote.copy(alpha = 0.75f)),
                        )
                        Text(
                            text = shown,
                            style = MaterialTheme.typography.bodyLarge,
                            color = oc.markdownQuote,
                        )
                    }
                }

                is MdBlock.Rule -> HorizontalDivider(
                    thickness = 1.dp,
                    color = oc.markdownHr.copy(alpha = 0.45f),
                )

                is MdBlock.Paragraph -> {
                    val base = remember(block, oc) { inlineMarkdown(block.text, oc) }
                    val shown = if (withCursor) {
                        remember(base, alpha) { withCursorSpan(base, oc, alpha ?: 1f) }
                    } else {
                        base
                    }
                    Text(
                        text = shown,
                        style = MaterialTheme.typography.bodyLarge,
                        color = oc.text,
                    )
                }
            }
        }

        // Testo ancora vuoto: il cursore è comunque il segno di vita.
        if (blocks.isEmpty() && alpha != null) {
            Text(
                text = "▌",
                style = MaterialTheme.typography.bodyLarge,
                color = oc.interactive.copy(alpha = alpha),
            )
        }
    }
}

private fun inlineMarkdown(text: String, oc: OpenPalette): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        withStyle(
                            SpanStyle(
                                color = oc.markdownStrong,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        ) { append(text.substring(i + 2, end)) }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }

                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        withStyle(
                            SpanStyle(
                                color = oc.markdownCode,
                                background = if (oc.dark) oc.raised else oc.high,
                                fontFamily = MonoFamily,
                            ),
                        ) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }

                text[i] == '[' -> {
                    val close = text.indexOf("](", i + 1)
                    val end = if (close >= 0) text.indexOf(')', close + 2) else -1
                    if (close > i + 1 && end > close + 2) {
                        withStyle(
                            SpanStyle(
                                color = oc.markdownLinkText,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ) { append(text.substring(i + 1, close)) }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }

                text.startsWith("http://", i) || text.startsWith("https://", i) -> {
                    var end = i
                    while (end < text.length && !text[end].isWhitespace()) end++
                    withStyle(
                        SpanStyle(
                            color = oc.markdownLink,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) { append(text.substring(i, end)) }
                    i = end
                }

                text[i] == '*' && i + 1 < text.length && !text[i + 1].isWhitespace() -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1) {
                        withStyle(
                            SpanStyle(
                                color = oc.markdownQuote,
                                fontStyle = FontStyle.Italic,
                            ),
                        ) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }

                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }

// ---------------------------------------------------------------------------
// Misure e formattazione delle part (§2, §4.2): niente misure a runtime, si
// troncano le stringhe prima di passarle a `Text`.
// ---------------------------------------------------------------------------

private const val REASONING_VISIBLE_LINES = 14
private const val REASONING_MAX_CHARS = 2_400
private const val COMMAND_VISIBLE_LINES = 4
private const val OUTPUT_VISIBLE_LINES = 6
private const val LONG_COMMAND_CHARS = 200

private data class LimitedText(
    val text: String,
    val totalLines: Int,
    val truncated: Boolean,
)

/** Prime `maxLines` righe (e al massimo 2400 caratteri) per il ragionamento. */
private fun limitLines(text: String, maxLines: Int): LimitedText {
    if (text.isEmpty()) return LimitedText("", 0, false)
    val lines = text.lines()
    val kept = ArrayList<String>(minOf(lines.size, maxLines))
    var used = 0
    var cut = lines.size > maxLines
    for (index in 0 until minOf(lines.size, maxLines)) {
        val line = lines[index]
        val remaining = REASONING_MAX_CHARS - used
        if (remaining <= 0) {
            cut = true
            break
        }
        if (line.length > remaining) {
            kept.add(line.take(remaining))
            cut = true
            break
        }
        kept.add(line)
        used += line.length + 1
    }
    return LimitedText(kept.joinToString("\n"), lines.size, cut)
}

/** Ultime 6 righe dell'output: il fondo è dove stanno gli errori. */
private fun outputTail(output: String?): LimitedText {
    if (output.isNullOrBlank()) return LimitedText("", 0, false)
    val lines = output.trimEnd().lines()
    val hidden = lines.size > OUTPUT_VISIBLE_LINES
    val visible = if (hidden) lines.takeLast(OUTPUT_VISIBLE_LINES) else lines
    return LimitedText(visible.joinToString("\n"), lines.size, hidden)
}

private fun commandHead(command: String?): LimitedText {
    if (command.isNullOrBlank()) return LimitedText("", 0, false)
    val lines = command.trimEnd().lines()
    val hidden = lines.size > COMMAND_VISIBLE_LINES || command.length > LONG_COMMAND_CHARS
    val visible = if (lines.size > COMMAND_VISIBLE_LINES) {
        lines.take(COMMAND_VISIBLE_LINES)
    } else {
        lines
    }
    return LimitedText(visible.joinToString("\n"), lines.size, hidden)
}

private fun lineLabel(count: Int): String = if (count == 1) "1 riga" else "$count righe"

private fun durationLabel(
    startedAt: Long?,
    endedAt: Long?,
    now: Long,
    minimumMs: Long,
): String? {
    if (startedAt == null || startedAt <= 0L) return null
    val end = endedAt ?: now
    val elapsed = (end - startedAt).coerceAtLeast(0L)
    if (elapsed < minimumMs) return null
    return formatElapsed(elapsed)
}

/** "<10 s" → "1,3 s"; "<60 s" → "12 s"; oltre → "1 min 12 s". */
private fun formatElapsed(elapsedMs: Long): String {
    if (elapsedMs < 10_000L) {
        return String.format(Locale.ITALIAN, "%.1f s", elapsedMs / 1000.0)
    }
    val totalSeconds = Math.round(elapsedMs / 1000.0)
    if (totalSeconds < 60L) return "$totalSeconds s"
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (seconds == 0L) "$minutes min" else "$minutes min $seconds s"
}

/** La durata di un tool compare solo da 1 s in su; per "negato" non c'è. */
private fun toolDurationLabel(part: ToolPartUi, now: Long): String? {
    if (!part.executed || part.phase == PartPhase.Denied) return null
    if (isToolTerminal(part.phase) && part.endedAt == null) return null
    return durationLabel(
        startedAt = part.startedAt,
        endedAt = part.endedAt ?: now,
        now = now,
        minimumMs = 1_000L,
    )
}

private fun isToolTerminal(phase: PartPhase): Boolean = when (phase) {
    PartPhase.Ok, PartPhase.Failed, PartPhase.Denied, PartPhase.Interrupted -> true
    PartPhase.Streaming, PartPhase.Running, PartPhase.Done -> false
}

private val shellToolNames = setOf(
    "bash",
    "shell",
    "sh",
    "zsh",
    "exec",
    "run",
    "command",
    "terminal",
)

/**
 * Il data layer espone `command` con il primo campo utile (`command`, `path`,
 * `pattern`…). Il prefisso `$` spetta ai tool di shell: si ricava dal nome.
 */
private fun isShellTool(name: String): Boolean {
    val normalized = name.lowercase(Locale.ROOT)
    return normalized in shellToolNames || normalized.contains("bash")
}

private data class ToolStatusUi(
    val label: String,
    val color: Color,
    val pulsing: Boolean,
    val talkBack: String,
)

/** §2.2 — stato tipizzato dalla fase, mai ricavato dal JSON. */
@Composable
private fun toolPhaseUi(part: ToolPartUi): ToolStatusUi {
    val oc = OpenColors.current
    if (!part.executed || part.phase == PartPhase.Denied) {
        return ToolStatusUi("negato", oc.error, pulsing = false, talkBack = "Tool negato")
    }
    return when (part.phase) {
        PartPhase.Streaming -> ToolStatusUi(
            label = "writing command…",
            color = oc.warningInk,
            pulsing = true,
            talkBack = "Tool in corso",
        )

        PartPhase.Running -> ToolStatusUi(
            label = "in corso",
            color = oc.warningInk,
            pulsing = true,
            talkBack = "Tool in corso",
        )

        PartPhase.Ok, PartPhase.Done -> ToolStatusUi(
            label = "ok",
            color = oc.success,
            pulsing = false,
            talkBack = "Tool completato",
        )

        PartPhase.Failed -> ToolStatusUi(
            label = "error",
            color = oc.error,
            pulsing = false,
            talkBack = "Tool fallito",
        )

        PartPhase.Interrupted -> ToolStatusUi(
            label = "interrotto",
            color = oc.warningInk,
            pulsing = false,
            talkBack = "Tool interrotto",
        )

        PartPhase.Denied -> ToolStatusUi(
            label = "negato",
            color = oc.error,
            pulsing = false,
            talkBack = "Tool negato",
        )
    }
}

// ---------------------------------------------------------------------------
// Auto-scroll (§4.4): la firma cambia con testo e struttura del turno, non con
// la crescita dell'output di un tool.
// ---------------------------------------------------------------------------

private data class ScrollSnapshot(
    val rows: Int,
    val signal: String,
    val loading: Boolean,
)

private fun liveScrollSignal(turn: AssistantTurnUi?): String {
    if (turn == null) return ""
    return buildString {
        append(turn.id)
        append('|')
        append(turn.running)
        append('|')
        append(turn.parts.size)
        turn.parts.forEach { part: TurnPartUi ->
            when (part) {
                is TextPartUi -> {
                    append("|t")
                    append(part.text.length)
                }

                is ReasoningPartUi -> {
                    append("|r")
                    append(part.text.length)
                }

                is ToolPartUi -> {
                    append("|o")
                    append(part.phase.name)
                }
            }
        }
    }
}
