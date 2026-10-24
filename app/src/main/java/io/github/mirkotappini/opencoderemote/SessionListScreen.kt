package io.github.mirkotappini.opencoderemote

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mirkotappini.opencoderemote.net.ReconnectPolicy
import io.github.mirkotappini.opencoderemote.net.SessionUi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onCreateSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenEventLog: () -> Unit,
    onOpenDrawer: () -> Unit,
    onDisconnect: () -> Unit,
    notificationPrompt: NotificationPromptUi? = null,
    onNotificationAction: () -> Unit = {},
    onDismissNotificationPrompt: () -> Unit = {},
    onRetryConnection: () -> Unit = onRefresh,
    onDismissError: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val connected = state.connection as? Connection.Connected
    val host = serverName(state.url)
    val refreshing = state.sessionsLoading && state.sessions.isNotEmpty()
    val reconnecting = state.streamState as? StreamState.Reconnecting
    val connecting = state.streamState is StreamState.Connecting
    val streamLive = state.streamState is StreamState.Live
    val suspended = state.retrySuspended || reconnecting?.suspended == true
    var serverSheetOpen by rememberSaveable { mutableStateOf(false) }
    val streamColor = when {
        reconnecting != null || connecting -> oc.warningInk
        streamLive -> oc.success
        else -> oc.mutedText
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
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = Layout.listMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp, bottom = 14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            ServerChip(
                                host = host,
                                version = connected?.version,
                                statusColor = streamColor,
                                pulsing = reconnecting != null || connecting,
                                onClick = { serverSheetOpen = true },
                            )
                        }
                        if (connected != null && !refreshing) {
                            IconButton(
                                onClick = onCreateSession,
                                enabled = !state.creatingSession,
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics {
                                        contentDescription = "New session"
                                    },
                            ) {
                                if (state.creatingSession) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = oc.accent,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = oc.accent,
                                    )
                                }
                            }
                        }
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

                    Spacer(Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Sessioni",
                            style = MaterialTheme.typography.headlineMedium,
                            color = oc.accent,
                        )
                        Spacer(Modifier.weight(1f))
                        if (state.sessions.isNotEmpty()) {
                            SessionCountPill(state.sessions.size)
                        }
                    }
                }
            }

            if (notificationPrompt != null) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    NotificationNoticeBar(
                        prompt = notificationPrompt,
                        onAction = onNotificationAction,
                        onDismiss = onDismissNotificationPrompt,
                        modifier = Modifier.widthIn(max = Layout.listMaxWidth),
                    )
                }
            }

            if (refreshing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = oc.interactive,
                    trackColor = scheme.surfaceContainerHigh,
                )
            }

            // M5 F1/F2: un solo avviso alla volta. Se lo stream non è live il
            // motivo (anche un errore REST) sta nella riga compatta di stato;
            // un errore mentre la SSE è viva usa il banner esistente.
            if (state.error != null && streamLive) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = Layout.listMaxWidth)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        ErrorBanner(
                            title = "Last operation failed",
                            message = state.error.orEmpty(),
                            onAction = onRetryConnection,
                            onDismiss = onDismissError,
                        )
                    }
                }
            } else if (!streamLive) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = Layout.listMaxWidth)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                    ) {
                        StreamNotice(
                            text = when {
                                // Rete assente: si aspetta, non si riprova (niente numeri).
                                suspended -> ReconnectPolicy.NETWORK_WAITING
                                reconnecting != null && state.error != null ->
                                    "${state.error} · riprovo tra " +
                                        "${state.reconnectInSeconds.coerceAtLeast(0)}s"
                                reconnecting != null ->
                                    ReconnectPolicy.bannerLabel(state.reconnectInSeconds, false)
                                connecting -> ReconnectPolicy.NEW_ATTEMPT
                                state.error != null -> state.error.orEmpty()
                                else -> "SSE stopped · data not up to date"
                            },
                            tone = when {
                                suspended -> oc.warningInk
                                state.error != null -> oc.error
                                reconnecting != null || connecting -> oc.warningInk
                                else -> oc.mutedText
                            },
                            pulsing = reconnecting != null || connecting,
                            onRetry = onRetryConnection,
                            onDismiss = if (state.error != null && !suspended) onDismissError else null,
                        )
                    }
                }
            }

            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = Layout.listMaxWidth)
                            .fillMaxHeight()
                            .fillMaxWidth(),
                    ) {
                        when {
                            state.sessionsLoading && state.sessions.isEmpty() -> SkeletonList()

                            state.sessions.isEmpty() -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    EmptyState(
                                        symbol = ">_",
                                        title = "No sessions",
                                        body = "Start a session with +, or refresh the list. " +
                                            "The server must be on and reachable via Tailscale.",
                                        action = {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Button(
                                                    onClick = onRefresh,
                                                    modifier = Modifier.heightIn(min = 48.dp),
                                                ) {
                                                    GlyphRefresh(size = 16.dp)
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("Aggiorna")
                                                }
                                                TextButton(
                                                    onClick = onOpenEventLog,
                                                    modifier = Modifier.heightIn(min = 48.dp),
                                                ) {
                                                    Text("Apri log SSE")
                                                }
                                            }
                                        },
                                    )
                                }
                            }

                            else -> {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    itemsIndexed(
                                        items = state.sessions,
                                        key = { _, session -> session.id },
                                    ) { index, session ->
                                        SessionRow(
                                            session = session,
                                            pendingApprovals = state.approvalCount(session.id),
                                            onClick = { onOpenSession(session.id) },
                                        )
                                        if (index < state.sessions.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(start = 20.dp),
                                                thickness = 1.dp,
                                                color = scheme.outlineVariant.copy(alpha = 0.65f),
                                            )
                                        }
                                    }
                                    item(key = "tail-spacer") {
                                        Spacer(Modifier.height(12.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            BottomBar(
                streamState = state.streamState,
                secondsLeft = state.reconnectInSeconds,
                suspended = state.retrySuspended,
                loading = state.sessionsLoading,
                onRefresh = onRefresh,
            )
        }

        if (serverSheetOpen) {
            ServerSheet(
                url = state.url,
                host = host,
                version = connected?.version,
                statusColor = streamColor,
                pulsing = reconnecting != null || connecting,
                onDismiss = { serverSheetOpen = false },
                onDisconnect = onDisconnect,
            )
        }
    }
}

/**
 * Pill dell'header: è il server corrente, ed è l'unico punto della lista da cui
 * si torna alla scelta del server. Bordo e chevron la rendono riconoscibile
 * come controllo; il tap apre il foglio con URL, versione e conseguenze.
 */
@Composable
private fun ServerChip(
    host: String,
    version: String?,
    statusColor: Color,
    pulsing: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val label = buildString {
        append(host)
        version?.let { append(" · v$it") }
    }
    val description = buildString {
        append("Server $host")
        version?.let { append(", version $it") }
        append(". Open the options to change server.")
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val containerColor by animateColorAsState(
        targetValue = if (pressed) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh,
        label = "server-chip-container",
    )
    val borderColor by animateColorAsState(
        targetValue = if (pressed) oc.interactive.copy(alpha = 0.75f) else scheme.outlineVariant,
        label = "server-chip-border",
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            },
        shape = CircleShape,
        color = containerColor,
        contentColor = scheme.secondary,
        border = BorderStroke(1.dp, borderColor),
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier.padding(start = 11.dp, end = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            StatusDot(statusColor, size = 5.dp, pulsing = pulsing)
            Text(
                text = label.uppercase(java.util.Locale.ITALIAN),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                color = scheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            CompositionLocalProvider(LocalContentColor provides oc.mutedText) {
                GlyphChevron(
                    direction = ChevronDirection.Down,
                    size = 12.dp,
                    strokeWidth = 1.6.dp,
                )
            }
        }
    }
}

/**
 * Foglio "Server": dati della connessione e uscita verso il setup. La
 * conseguenza (credenziali salvate cancellate) è scritta prima del tap sul
 * pulsante, così il cambio server non è una perdita silenziosa.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSheet(
    url: String,
    host: String,
    version: String?,
    statusColor: Color,
    pulsing: Boolean,
    onDismiss: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surfaceContainerLow,
        contentColor = scheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(statusColor, size = 5.dp, pulsing = pulsing)
                    Spacer(Modifier.width(8.dp))
                    Eyebrow("Server")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = host,
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = url,
                    style = monoStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = version?.let { "OpenCode v$it" }
                        ?: "OpenCode · version not declared",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(
                thickness = 1.dp,
                color = scheme.outlineVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(16.dp))

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Eyebrow("Change server")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Return to the sign-in screen, with the fields still filled in: " +
                        "you can reconnect to the same server or pick another.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = oc.error.copy(alpha = 0.10f),
                    contentColor = oc.error,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, oc.error.copy(alpha = 0.26f)),
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        GlyphWarning(
                            size = 16.dp,
                            color = oc.error,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Encrypted credentials will be deleted: on " +
                                    "next launch, access will no longer be automatic.",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurface,
                            )
                            Text(
                                text = "The background connection and approval " +
                                    "notifications stop.",
                                style = MaterialTheme.typography.labelSmall,
                                color = oc.mutedText,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = oc.mutedText),
                    ) {
                        Text("Annulla", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onDismiss()
                            onDisconnect()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text("Change server", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/**
 * Riga compatta di stato sotto l'header: compare anche con la lista in cache,
 * che è proprio il caso in cui serve sapere che i dati non sono aggiornati.
 * Il motivo viene da `error` (quando c'è) o dallo stato dello stream.
 */
@Composable
private fun StreamNotice(
    text: String,
    tone: Color,
    pulsing: Boolean,
    onRetry: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tone.copy(alpha = 0.10f),
        contentColor = tone,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, tone.copy(alpha = 0.26f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pulsing) {
                StatusDot(tone, size = 6.dp, pulsing = true)
            } else {
                GlyphWarning(size = 15.dp, color = tone)
            }
            Spacer(Modifier.width(9.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = tone,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 44.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = tone),
            ) {
                Text(text = "Riprova", style = MaterialTheme.typography.labelMedium)
            }
            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(44.dp)
                        .semantics { contentDescription = "Chiudi l'avviso" },
                ) {
                    GlyphClose(size = 14.dp)
                }
            }
        }
    }
}

@Composable
private fun SessionCountPill(count: Int) {
    val description = if (count == 1) "1 session" else "$count sessions"
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Text(
            text = count.toString(),
            style = monoStyle(fontSize = 11.sp, lineHeight = 15.sp, weight = FontWeight.Medium),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SessionRow(
    session: SessionUi,
    pendingApprovals: Int,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val badge = sessionBadge(session)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = "Open session ${session.title}",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = sessionMeta(session),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(horizontalAlignment = Alignment.End) {
            if (pendingApprovals > 0) {
                ApprovalBadge(pendingApprovals)
                Spacer(Modifier.height(5.dp))
            }
            Text(
                text = relativeAge(session.updatedAt),
                style = monoStyle(
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    weight = FontWeight.Medium,
                ),
                color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
            )
            if (badge != null) {
                Spacer(Modifier.height(6.dp))
                StatusPill(
                    label = badge.label,
                    color = badge.color,
                    pulsing = badge.pulsing,
                )
            }
        }
    }
}

private data class BadgeSpec(
    val label: String,
    val color: Color,
    val pulsing: Boolean,
)

@Composable
private fun sessionBadge(session: SessionUi): BadgeSpec? {
    val oc = OpenColors.current
    if (session.isActive) return BadgeSpec("in corso", oc.success, pulsing = true)
    return when (session.outcome?.lowercase()) {
        "failed", "failure", "error" -> BadgeSpec("error", oc.error, pulsing = false)
        "interrupted", "aborted" -> BadgeSpec("interrotta", oc.warningInk, pulsing = false)
        else -> outcomeLabel(session.outcome)?.let {
            BadgeSpec(it, oc.mutedText, pulsing = false)
        }
    }
}

@Composable
private fun SkeletonList() {
    val alpha = rememberSkeletonAlpha()
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(7) { index ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                SkeletonBar(
                    widthFraction = 0.48f + (index % 3) * 0.09f,
                    height = 15.dp,
                    alpha = alpha,
                )
                SkeletonBar(
                    widthFraction = 0.30f + (index % 2) * 0.08f,
                    height = 10.dp,
                    alpha = alpha,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 20.dp),
                thickness = 1.dp,
                color = scheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun BottomBar(
    streamState: StreamState,
    secondsLeft: Int,
    suspended: Boolean,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val stream = streamStatusUi(streamState, secondsLeft, suspended)
    val streamColor = when (stream.tone) {
        StreamTone.Live -> oc.success
        StreamTone.Warning -> oc.warningInk
        StreamTone.Muted -> oc.mutedText
    }
    Surface(color = scheme.surfaceContainerLow) {
        Column {
            HorizontalDivider(
                thickness = 1.dp,
                color = scheme.outlineVariant.copy(alpha = 0.8f),
            )
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .widthIn(max = Layout.listMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(
                        label = stream.label,
                        color = streamColor,
                        pulsing = stream.pulsing,
                    )

                    Spacer(Modifier.weight(1f))

                    FilledTonalButton(
                        onClick = onRefresh,
                        enabled = !loading,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                                color = scheme.onSecondaryContainer,
                            )
                        } else {
                            GlyphRefresh(size = 16.dp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Aggiorna",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}
