package io.github.mirkotappini.opencoderemote

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.mirkotappini.opencoderemote.net.AssistantTurnUi
import io.github.mirkotappini.opencoderemote.net.FormValue
import io.github.mirkotappini.opencoderemote.net.MessageUi
import io.github.mirkotappini.opencoderemote.net.PartPhase
import io.github.mirkotappini.opencoderemote.net.ReasoningPartUi
import io.github.mirkotappini.opencoderemote.net.ReconnectPolicy
import io.github.mirkotappini.opencoderemote.net.Role as MessageRole
import io.github.mirkotappini.opencoderemote.net.TextPartUi
import io.github.mirkotappini.opencoderemote.net.ToolPartUi
import io.github.mirkotappini.opencoderemote.net.TurnPartUi
import java.io.File
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
    onAttachFile: (Uri) -> Unit,
    onClearAttachment: () -> Unit,
    onDismissAttachmentError: () -> Unit,
    onOpenDrawer: () -> Unit,
    onReplyApproval: (id: String, decision: String, message: String?) -> Unit = { _, _, _ -> },
    onRefreshApprovals: () -> Unit = {},
    onReplyForm: (id: String, answer: Map<String, FormValue>) -> Unit = { _, _ -> },
    onCancelForm: (id: String) -> Unit = {},
    onRefreshForms: () -> Unit = {},
    onLoadCommands: (force: Boolean) -> Unit = {},
    onDismissError: () -> Unit = {},
    notificationPrompt: NotificationPromptUi? = null,
    onNotificationAction: () -> Unit = {},
    onDismissNotificationPrompt: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val scheme = MaterialTheme.colorScheme
    val session = state.openSession
    val listState = rememberLazyListState()

    var pendingAnchorId by remember(session?.id) { mutableStateOf<String?>(null) }

    /**
     * Follow "sticky" (§4.4): the list stays pinned to the bottom until the user
     * drags upward; returning to the bottom re-pins it.
     *
     * Previously, the "am I at the bottom?" check ran *after* content growth: if
     * one or more lines were added while content was settling, the comparison
     * failed and auto-scroll stayed off for the rest of the turn. Now the decision
     * depends only on user gestures.
     */
    var followBottom by remember(session?.id) { mutableStateOf(true) }

    // A single live turn (UI-M6 §1). If the resync has already brought the same id
    // into `messages`, the live turn is not drawn twice.
    val liveTurn = state.chat.liveTurn
        ?.takeIf { turn -> state.messages.none { it.id == turn.id } }
    // Consecutive assistant steps = a single bubble, as in the TUI. Grouping is
    // presentation-only: no new state in the reducer. History is memoized: for live
    // deltas, only the queued turn is re-rendered.
    val persistedRows = remember(state.messages) { buildChatRows(state.messages) }
    val rows = remember(persistedRows, liveTurn) {
        withLiveTurn(persistedRows, liveTurn)
    }
    val totalRows = rows.size + (if (state.hasMore) 1 else 0)
    var detailRequest by remember(session?.id) { mutableStateOf<DetailRequest?>(null) }

    // M8 — attachments. Photo Picker and OpenDocument do not require permissions;
    // the camera writes to a FileProvider URI in cacheDir, so the app requests
    // nothing at runtime and does not keep copies of the files.
    val context = LocalContext.current
    var attachSheetOpen by remember(session?.id) { mutableStateOf(false) }
    // URI of the photo in progress: survives activity recreation while the camera
    // app is in the foreground.
    var cameraTarget by rememberSaveable { mutableStateOf<Uri?>(null) }
    var viewerRequest by remember(session?.id) { mutableStateOf<ImageViewerRequest?>(null) }

    // M9 — the pending question is drawn above the composer, not in the list: so it
    // stays visible even if the turn is long. With an open question the composer is
    // disabled ("Stop" remains), because the turn is already waiting.
    val pendingForm = state.forms.firstOrNull()

    // M9 — command palette. Lives as long as the draft is a command being typed
    // (starts with "/" and has no spaces yet). "cancel" suppresses it until the
    // text changes: previously the draft text was compared, but the sheet's lambda
    // could belong to a previous composition, causing the comparison to fail (the
    // dropdown would reappear).
    var paletteSuppressed by remember(session?.id) { mutableStateOf(false) }
    val commandQuery = commandQueryOf(state.draft)
    val paletteOpen = commandQuery != null &&
        !paletteSuppressed &&
        pendingForm == null &&
        !state.openReadOnly

    // Resuming input reopens the palette.
    LaunchedEffect(state.draft) { paletteSuppressed = false }

    LaunchedEffect(paletteOpen) {
        // TTL and "only if needed" belong to the ViewModel: here the list is
        // requested every time the palette appears.
        if (paletteOpen) onLoadCommands(false)
    }

    val formSlot: (@Composable () -> Unit)? = if (pendingForm != null) {
        val form = pendingForm
        val panel: @Composable () -> Unit = {
            QuestionPanel(
                form = form,
                error = state.formsError,
                onReply = { answer -> onReplyForm(form.id, answer) },
                onCancel = { onCancelForm(form.id) },
                onRefresh = onRefreshForms,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        panel
    } else {
        null
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onAttachFile(uri) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val target = cameraTarget
        cameraTarget = null
        if (saved && target != null) onAttachFile(target)
    }

    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onAttachFile(uri) }

    // Auto-scroll is anchored to the turn content, never to the growth of a tool's
    // output (§4.4). The throttle is provided by conflate + delay. The signature
    // comes from the tail line (which already contains the live turn): text and
    // reasoning count, tool output does not.
    val scrollSnapshot = rememberUpdatedState(
        ScrollSnapshot(
            rows = totalRows,
            signal = rowScrollSignal(rows.lastOrNull()),
            loading = state.messagesLoading,
        ),
    )
    LaunchedEffect(session?.id) {
        snapshotFlow { scrollSnapshot.value }
            .distinctUntilChanged()
            .conflate()
            .collect { snapshot ->
                if (snapshot.loading || !followBottom) return@collect
                delay(AUTO_SCROLL_DELAY_MS)
                listState.scrollToBottom()
            }
    }

    // User drag releases the follow...
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followBottom = false
        }
    }
    // ...and the list that stops at the bottom re-anchors it.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling && listState.isAtBottom()) followBottom = true
            }
    }

    // Keyboard: as it opens, the IME shrinks the viewport (the Column is padded
    // with `safeDrawing`, which includes the IME) and the chat tail would end up
    // under the keyboard. If we are following the bottom, it realigns once the
    // layout settles; if the user has scrolled up to read, nothing moves and it
    // stays as it was when the keyboard closes.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (!followBottom) return@LaunchedEffect
        // No wait: the IME enters with animated steps, and each step realigns the
        // list, so the tail stays glued to the animation instead of sitting still
        // and jumping to the end (this was the "step" on open).
        listState.scrollToBottom()
    }

    // Bring to foreground: while the app was in standby, messages may have arrived
    // and scrolls performed with the view off are not applied. If we are following
    // the bottom, jump to where the chat actually is.
    var resumeTicks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTicks += 1 }
    LaunchedEffect(resumeTicks) {
        if (resumeTicks == 0 || !followBottom) return@LaunchedEffect
        delay(AUTO_SCROLL_DELAY_MS)
        listState.scrollToBottom()
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
                title = session?.title ?: "Session",
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
            // One caveat: if `error` is already present, the countdown is appended
            // to its message. If the network is absent, the dedicated text takes
            // precedence.
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
                                    "$message · retrying in " +
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
                                    title = "No messages",
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
                                                    "Back to parent"
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

                                // One row per turn: consecutive assistant steps are
                                // already merged into `rows`. The key remains
                                // `turn:<primoId>`, so the live → resync transition
                                // does not move the item.
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
                                            onOpenImage = { viewerRequest = it },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Subagent just activated: overlay in the top right, inside the
                    // chat content (doesn't shift anything and stays below sheets
                    // and palettes, which sit higher up).
                    SubagentFlashOverlay(
                        flash = state.subagentFlash,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 12.dp, end = 12.dp),
                    )
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
                    attachment = state.attachment,
                    attachmentPreparing = state.attachmentPreparing,
                    attachmentError = state.attachmentError,
                    onPickAttachment = { attachSheetOpen = true },
                    onClearAttachment = onClearAttachment,
                    onDismissAttachmentError = onDismissAttachmentError,
                    onSend = {
                        followBottom = true
                        onSend()
                    },
                    onInterrupt = onInterrupt,
                    formPanel = formSlot,
                    formPending = pendingForm != null,
                )
            }
        }

        // A single sheet for the entire chat: tool commands/outputs and long
        // reasoning pass through here (§2.5).
        detailRequest?.let { request ->
            TurnDetailSheet(
                request = request,
                onDismiss = { detailRequest = null },
            )
        }

        // Attachments sheet: close it before launching the picker, so the system
        // picker does not appear on top of the sheet.
        if (attachSheetOpen) {
            AttachSheet(
                onDismiss = { attachSheetOpen = false },
                onPick = { target ->
                    attachSheetOpen = false
                    when (target) {
                        AttachTarget.Gallery -> galleryLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )

                        AttachTarget.Camera -> {
                            val uri = cameraTargetUri(context)
                            if (uri != null) {
                                cameraTarget = uri
                                cameraLauncher.launch(uri)
                            }
                        }

                        AttachTarget.Document -> documentLauncher.launch(arrayOf("*/*"))
                    }
                },
            )
        }

        // Full-screen image: a single window for the entire chat, so it doesn't
        // disappear when the bubble leaves the viewport.
        viewerRequest?.let { request ->
            ImageViewer(
                request = request,
                onDismiss = { viewerRequest = null },
            )
        }

        // Command palette: opens from the draft and closes by selecting (the draft
        // becomes "/name ") or by pressing "cancel".
        if (paletteOpen) {
            CommandPalette(
                commands = state.commands,
                loading = state.commandsLoading,
                error = state.commandsError,
                query = commandQuery.orEmpty(),
                onPick = { name ->
                    paletteSuppressed = true
                    onDraftChange("/$name ")
                },
                onDismiss = { paletteSuppressed = true },
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
        state.running -> "running" to oc.interactive
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
            text = "read only",
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
    attachment: AttachmentUi?,
    attachmentPreparing: Boolean,
    attachmentError: String?,
    onPickAttachment: () -> Unit,
    onClearAttachment: () -> Unit,
    onDismissAttachmentError: () -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    formPanel: (@Composable () -> Unit)? = null,
    formPending: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    // With a pending question, the composer turns off: the turn is stalled awaiting
    // a response, and a queued prompt would cause confusion. "Ferma" remains.
    val writable = connected && !formPending
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
                Column(
                    modifier = Modifier
                        .widthIn(max = Layout.chatMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    // The server's question is here, pasted above the line
                    // attachment: same column, same breathing room, and it doesn't
                    // scroll away with the chat.
                    formPanel?.invoke()
                    // Attachment row (or status/error): above the field, within the
                    // same column so it doesn't shift relative to the composer.
                    AttachmentRow(
                        attachment = attachment,
                        preparing = attachmentPreparing,
                        error = attachmentError,
                        onClear = onClearAttachment,
                        onDismissError = onDismissAttachmentError,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                            // Paperclip inside the pill, bottom right: the text has
                            // its own space and never ends up underneath it.
                            Row(
                                modifier = Modifier.padding(
                                    start = 14.dp,
                                    end = 4.dp,
                                    top = 4.dp,
                                    bottom = 4.dp,
                                ),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                BasicTextField(
                                    value = draft,
                                    // Submit to empty field: ignore instead of
                                    // inserting a newline that bloats the pill.
                                    onValueChange = { next ->
                                        if (acceptDraftChange(draft, next)) {
                                            onDraftChange(next)
                                        }
                                    },
                                    enabled = writable,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = oc.text,
                                    ),
                                    cursorBrush = SolidColor(oc.interactive),
                                    maxLines = 5,
                                    keyboardOptions = KeyboardOptions(
                                        imeAction = ImeAction.Default,
                                    ),
                                    modifier = Modifier.weight(1f),
                                    decorationBox = { innerTextField ->
                                        Box(
                                            modifier = Modifier
                                                .padding(vertical = 8.dp)
                                                .heightIn(min = 24.dp),
                                        ) {
                                            if (draft.isEmpty()) {
                                                Text(
                                                    text = when {
                                                        !connected -> "Not connected"
                                                        formPending ->
                                                            "answer the question above"
                                                        else ->
                                                            "Write… or / for commands"
                                                    },
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = scheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            innerTextField()
                                        }
                                    },
                                )
                                AttachButton(
                                    enabled = writable && !attachmentPreparing,
                                    onClick = onPickAttachment,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }

                        if (running) {
                            StopButton(onClick = onInterrupt)
                        } else {
                            SendButton(
                                // The prompt also starts with just the attachment;
                                // without an attachment, at least one letter or
                                // digit is required.
                                enabled = writable &&
                                    (hasSendableText(draft) || attachment != null),
                                onClick = onSend,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Composer paperclip: sits **inside** the field pill, bottom right. Touch area 40
 * dp (the field does not grow: 40 dp is also the minimum height of the text line),
 * `mutedText`, disabled if there is no connection or while an attachment is being
 * prepared (M8 §4.1).
 */
@Composable
private fun AttachButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val oc = OpenColors.current
    val tint = if (enabled) oc.mutedText else oc.mutedText.copy(alpha = 0.4f)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = "Attach a file" },
        contentAlignment = Alignment.Center,
    ) {
        GlyphPaperclip(size = 20.dp, color = tint)
    }
}

/**
 * Replaces the composer for a child task: no prompt or send, the parent's `draft`
 * remains intact. "Stop" acts on this session.
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
                    Eyebrow(text = "read only", color = oc.mutedText)
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
            .semantics { contentDescription = "Send prompt" },
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
            .semantics { contentDescription = "Interrupt run" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlyphStop(size = 12.dp)
            Text(text = "Stop", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ---------------------------------------------------------------------------
// Assistant turn — UI-M6 §1/§2.
//
// A single entry point for the live turn and the persisted message: same bubble,
// same parts, same keys. On resync, the reducer replaces the turn with the same id,
// so the `turn:<id>` item does not jump and expanded parts remain expanded when
// their `key` matches.
// ---------------------------------------------------------------------------

@Composable
private fun AssistantTurn(
    turn: AssistantTurnUi,
    bubbleMax: Dp,
    onOpenDetail: (DetailRequest) -> Unit,
) {
    // With the turn concluded and no part, the bubble does not exist (§2.4).
    if (turn.parts.isEmpty() && !turn.running) return

    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val parts = turn.parts
    val anyActive = turn.running && parts.any {
        it.phase == PartPhase.Streaming || it.phase == PartPhase.Running
    }

    // One ticker per turn, active only while a part is in progress (§4.4).
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

    // One cursor per turn: the last text part still streaming. Blinking in steps 1
    // ↔ 0.35 over 900 ms: the glyph stays identical, so the text is not re-measured
    // on every frame (no relayout during streaming).
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
                            // Pause between two parts: no ellipsis, a single arc
                            // with a 400 ms grace period (§2.4).
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

/** Fallback §2.4: no active part, but the turn is still working. */
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
            .clearAndSetSemantics { contentDescription = "typing…" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WorkingIndicator(
            size = 12.dp,
            color = oc.success,
            description = "waiting",
        )
        Text(
            text = "typing…",
            style = MaterialTheme.typography.labelMedium,
            color = oc.mutedText,
        )
    }
}

/** §2.1 — collapsed reasoning by default, preview, and line limit. */
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

    // TUI: base color in streaming, warningInk when expanded, 60% if collapsed and
    // already concluded. In the light theme, 60% would fall below AA, so the ink
    // remains full.
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
                        "Hide reasoning"
                    } else {
                        "Show reasoning"
                    },
                ) { expanded = !expanded }
                .semantics {
                    stateDescription = if (expanded) "expanded" else "collapsed"
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
                text = "Reasoning",
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

        // In streaming, the body does not open automatically: a preview line
        // signals activity without causing the chat to jump on every token.
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
                        onClickLabel = "Show all reasoning",
                        onClick = {
                            onOpenDetail(
                                DetailRequest(
                                    title = "reasoning",
                                    sections = listOf(
                                        DetailSection("reasoning", trimmed),
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

/**
 * §2.2 — tool card, always open, no JSON dump. The command is not shown anywhere:
 * in the card it is noise next to the output, and the detail sheet is for reading
 * the full output.
 */
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
    val denied = !part.executed || part.phase == PartPhase.Denied
    // The card's only action counts the output lines: without output there is
    // nothing to open, so no line (the command no longer shows).
    val actionLabel = if (output.totalLines > 0) {
        "show all · " + lineLabel(output.totalLines)
    } else {
        null
    }

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
                        text = "output truncated",
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
                        text = "(no output)",
                        style = monoStyle(fontSize = 11.5.sp, lineHeight = 17.sp),
                        color = oc.lineNumber,
                    )
                }
            }

            // The action opens the full output: the "command" section no longer
            // exists in the sheet.
            if (actionLabel != null) {
                val name = part.name.ifBlank { "tool" }
                ShowAllRow(
                    label = actionLabel,
                    onClickLabel = "Show all output of $name",
                    onClick = {
                        onOpenDetail(
                            DetailRequest(
                                title = "output · $name",
                                sections = buildList {
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

/**
 * Card action row ("show all · N lines"): 48dp target, interactive text
 * (§2.2/§2.5).
 */
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
// Shared detail sheet (§2.5): tool command/output, reasoning.
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
    // The sheet does not exceed 80% of the screen; if the content is short, it
    // stays short (no empty space above the text).
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
                    text = request.title.uppercase(Locale.US),
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
                            onClickLabel = "Close",
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
                        onClickLabel = "Copy ${section.label}",
                        onClick = onCopy,
                    )
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (copied) "copied" else "copy",
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

/**
 * URI `FileProvider` for a new photo: the file is created in `cacheDir/camera` and
 * written by the system camera app, which receives write permission via the intent
 * (`TakePicture`). No runtime permissions, no copy to the gallery.
 *
 * Before the shot, photos older than one day are deleted: the cache folder does not
 * grow unbounded and the app retains nothing useful.
 */
private fun cameraTargetUri(context: Context): Uri? = runCatching {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
    runCatching {
        dir.listFiles()?.forEach { old ->
            if (old.isFile && old.lastModified() < cutoff) old.delete()
        }
    }
    val file = File.createTempFile("foto-", ".jpg", dir)
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}.getOrNull()

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
                    text = if (loading) "Loading…" else "Load older",
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
    onOpenImage: (ImageViewerRequest) -> Unit,
) {
    when (message.role) {
        MessageRole.User -> UserMessage(
            message = message,
            bubbleMax = bubbleMax,
            onOpenImage = onOpenImage,
        )

        MessageRole.Assistant -> AssistantTurn(
            turn = message.asTurn(running),
            bubbleMax = bubbleMax,
            onOpenDetail = onOpenDetail,
        )

        MessageRole.Meta -> MetaDivider(message.metaLabel ?: "event")
    }
}

/** Stable list key: assistants use `turn:<id>` as the live turn. */
private fun MessageUi.listItemKey(): String = when (role) {
    MessageRole.Assistant -> "turn:$id"
    else -> "message:$id"
}

// ---------------------------------------------------------------------------
// Presentation grouping of assistant steps.
//
// The server emits an `assistantMessageID` per step: reasoning + tool in one, final
// text in the next. In the TUI it is a single turn, so consecutive assistant steps
// are merged into a single bubble here. This is not part of the reducer: it is a
// projection of the list at render time.
// ---------------------------------------------------------------------------

/** Chat row: single message (user/meta) or merged turn. */
private sealed interface ChatRow {
    val key: String

    data class Single(val message: MessageUi) : ChatRow {
        override val key: String = message.listItemKey()
    }

    data class Turn(val turn: AssistantTurnUi) : ChatRow {
        override val key: String = "turn:${turn.id}"
    }
}

/** ID used as an anchor for "load previous": the first message in the row. */
private fun ChatRow.anchorId(): String = when (this) {
    is ChatRow.Single -> message.id
    is ChatRow.Turn -> turn.id
}

/**
 * Does the line contain [id]? A turn may have absorbed the previous message
 * (consecutive assistant steps): in that case, it anchors to the turn.
 */
private fun ChatRow.holdsMessage(id: String): Boolean = when (this) {
    is ChatRow.Single -> message.id == id
    is ChatRow.Turn ->
        turn.id == id || turn.parts.any { it.key.startsWith("$id|") }
}

/**
 * Merges consecutive assistant steps into a single [AssistantTurnUi], stopping at
 * every non-assistant message (user or divider). The turn id is that of the first
 * step: it is the `turn:<firstId>` key that resync finds identical.
 *
 * Part keys repeat across different messages (`reasoning:0`, `text:0`, …), so each
 * part is re-keyed with the id of its source message. The result is stable live →
 * materialized → resync, because the message id does not change in that transition.
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
        // Group without part and not in progress: no bubble (as §2.4).
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
        // The bubble's timestamp is that of the last step.
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
 * Adds the live turn to the queue. If the last line is already an assistant turn
 * (previous steps of the same execution, or two executions with no prompt in
 * between), the live turn is merged there, so the bubble remains one and the key
 * `turn:<primoId>` does not change. Otherwise, the live turn is a separate turn.
 */
private fun withLiveTurn(
    rows: List<ChatRow>,
    live: AssistantTurnUi?,
): List<ChatRow> {
    if (live == null) return rows
    if (live.parts.isEmpty() && !live.running) return rows
    // Defense: the live with the same id as a message is already represented.
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

/** Sort by `order` and re-key: the part key repeats across different messages. */
private fun rekeyParts(id: String, parts: List<TurnPartUi>): List<TurnPartUi> =
    parts.sortedBy { it.order }.map { it.withDisplayKey("$id|${it.key}") }

/** Stable re-key: source message id + part's already stable key. */
private fun TurnPartUi.withDisplayKey(key: String): TurnPartUi = when (this) {
    is ReasoningPartUi -> copy(key = key)
    is TextPartUi -> copy(key = key)
    is ToolPartUi -> copy(key = key)
}

/**
 * Persisted message → same type as the live turn (§1). `running` is true only if
 * the resync brought back still-active parts: a closed message never shows the
 * "writing…" fallback.
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
private fun UserMessage(
    message: MessageUi,
    bubbleMax: Dp,
    onOpenImage: (ImageViewerRequest) -> Unit,
) {
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
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // A message with only an attachment does not show the "…": the
                    // text is present only if there is something to read (M8 §4.2).
                    if (message.text.isNotBlank() || message.files.isEmpty()) {
                        SelectionContainer {
                            Text(
                                text = message.text.ifBlank { "…" },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    MessageAttachments(
                        messageId = message.id,
                        files = message.files,
                        pending = message.pending,
                        onOpenImage = onOpenImage,
                    )
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
                    text = "queued",
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
// Minimal Markdown for chat: headings, bold, italics, inline code and code blocks,
// blockquotes, links. No dependencies: only the styles the TUI displays are
// applied, without introducing a full parser.
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
 * Markdown memoized on (length, hash) of the text (§4.4): the cursor is not part of
 * the parsed text, so the blink does not recompute the markdown.
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
    // Read here: the blink only invalidates this text part, not the entire turn.
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

        // Text is still empty: the cursor is the sign of life.
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
// Measure and format the part (§2, §4.2): no measurements at runtime, truncate the
// strings before passing them to `Text`.
// ---------------------------------------------------------------------------

private const val REASONING_VISIBLE_LINES = 14
private const val REASONING_MAX_CHARS = 2_400
private const val OUTPUT_VISIBLE_LINES = 6

private data class LimitedText(
    val text: String,
    val totalLines: Int,
    val truncated: Boolean,
)

/** First `maxLines` lines (and at most 2400 characters) for the reasoning. */
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

/** Last 6 lines of the output: the bottom is where the errors are. */
private fun outputTail(output: String?): LimitedText {
    if (output.isNullOrBlank()) return LimitedText("", 0, false)
    val lines = output.trimEnd().lines()
    val hidden = lines.size > OUTPUT_VISIBLE_LINES
    val visible = if (hidden) lines.takeLast(OUTPUT_VISIBLE_LINES) else lines
    return LimitedText(visible.joinToString("\n"), lines.size, hidden)
}

private fun lineLabel(count: Int): String = if (count == 1) "1 line" else "$count lines"

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
        return String.format(Locale.US, "%.1f s", elapsedMs / 1000.0)
    }
    val totalSeconds = Math.round(elapsedMs / 1000.0)
    if (totalSeconds < 60L) return "$totalSeconds s"
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (seconds == 0L) "$minutes min" else "$minutes min $seconds s"
}

/** A tool's duration only appears from 1 s upward; there is none for "denied". */
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

private data class ToolStatusUi(
    val label: String,
    val color: Color,
    val pulsing: Boolean,
    val talkBack: String,
)

/** §2.2 — state typed from the phase, never derived from JSON. */
@Composable
private fun toolPhaseUi(part: ToolPartUi): ToolStatusUi {
    val oc = OpenColors.current
    if (!part.executed || part.phase == PartPhase.Denied) {
        return ToolStatusUi("denied", oc.error, pulsing = false, talkBack = "Tool denied")
    }
    return when (part.phase) {
        PartPhase.Streaming -> ToolStatusUi(
            label = "writing command…",
            color = oc.warningInk,
            pulsing = true,
            talkBack = "Tool running",
        )

        PartPhase.Running -> ToolStatusUi(
            label = "running",
            color = oc.warningInk,
            pulsing = true,
            talkBack = "Tool running",
        )

        PartPhase.Ok, PartPhase.Done -> ToolStatusUi(
            label = "ok",
            color = oc.success,
            pulsing = false,
            talkBack = "Tool completed",
        )

        PartPhase.Failed -> ToolStatusUi(
            label = "error",
            color = oc.error,
            pulsing = false,
            talkBack = "Tool failed",
        )

        PartPhase.Interrupted -> ToolStatusUi(
            label = "interrupted",
            color = oc.warningInk,
            pulsing = false,
            talkBack = "Tool interrupted",
        )

        PartPhase.Denied -> ToolStatusUi(
            label = "denied",
            color = oc.error,
            pulsing = false,
            talkBack = "Tool denied",
        )
    }
}

// ---------------------------------------------------------------------------
// Auto-scroll (§4.4): the signature changes with the turn's text and structure, not
// with the growth of a tool's output.
// ---------------------------------------------------------------------------

private const val AUTO_SCROLL_DELAY_MS = 150L

private data class ScrollSnapshot(
    val rows: Int,
    val signal: String,
    val loading: Boolean,
)

/**
 * Scroll signature for the trailing row. Includes text and reasoning growth but
 * **not** tool output growth: reading a long output must not pull the list down.
 *
 * The live turn is already in `rows` (merged in `withLiveTurn`), so this signature
 * covers both streaming and the materialized message after resync.
 */
private fun rowScrollSignal(row: ChatRow?): String = when (row) {
    is ChatRow.Single -> "m|${row.message.text.length}"
    is ChatRow.Turn -> partsScrollSignal(row.turn.running, row.turn.parts)
    null -> ""
}

/**
 * Bring the list to the bottom **for real**.
 *
 * `scrollToItem(last)` aligns the item's *top* with the viewport top: if the last
 * item is taller than the screen (a turn bubble with many tools), you end up
 * showing the start of the bubble with the tail below the edge. Here, after
 * alignment, we recover the measured offset so the last line of content touches the
 * bottom of the viewport.
 */
private suspend fun LazyListState.scrollToBottom() {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return
    val lastIndex = info.totalItemsCount - 1
    val visible = info.visibleItemsInfo.lastOrNull()
    if (visible == null || visible.index < lastIndex) {
        // Last item not drawn (list far from the bottom): move to the index, then
        // the measurement below closes the account.
        scrollToItem(lastIndex)
    }
    // A single `scrollBy` with the exact distance: `scrollToItem` + correction
    // triggers two layouts per frame and shows up as jitter during the keyboard
    // animation.
    val after = layoutInfo
    val last = after.visibleItemsInfo.lastOrNull() ?: return
    val end = after.viewportEndOffset - after.afterContentPadding
    val remaining = last.offset + last.size - end
    if (remaining > 0) scrollBy(remaining.toFloat())
}

/** "Are you at the bottom?" tight: the last item must be visible. */
private fun LazyListState.isAtBottom(): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return false
    return lastVisible >= info.totalItemsCount - 1
}
