package io.github.mirkotappini.opencoderemote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mirkotappini.opencoderemote.net.AgentUi
import io.github.mirkotappini.opencoderemote.net.McpUi
import io.github.mirkotappini.opencoderemote.net.ModelUi
import io.github.mirkotappini.opencoderemote.net.SessionUi
import io.github.mirkotappini.opencoderemote.net.SkillUi
import kotlinx.coroutines.delay
import java.util.Locale

// ---------------------------------------------------------------------------
// Drawer "sidebar OMO": agents, skills, MCP. The open session and its background
// tasks are context (header row + bottom sheet), not a second list of sessions.
// Data layer: UiState.catalog / children / openSession (ViewModel). This is
// presentation only: no direct network calls.
// ---------------------------------------------------------------------------

private enum class DrawerTab(val label: String) {
    Agents("Agents"),
    Skills("Skill"),
    Mcp("MCP"),
}

/** Ephemeral confirmation of an action (agent, model, skill). */
private data class DrawerNotice(val text: String, val success: Boolean = true)

/**
 * End-side drawer container. `ModalNavigationDrawer` operates in an RTL layout (see
 * `MainScreen`), so the sheet is mirrored to the right; the body reverts to LTR
 * immediately after, for text, icons, and alignments.
 */
@Composable
fun OmoDrawerSheet(
    state: UiState,
    drawerState: DrawerState,
    onClose: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onRefreshChildren: () -> Unit,
    onOpenSession: (SessionUi) -> Unit,
    onSwitchAgent: (String) -> Unit,
    onSwitchModel: (String, String, String?) -> Unit,
    onActivateSkill: (String) -> Unit,
    onOpenEventLog: () -> Unit,
    onDisconnect: () -> Unit,
    onDismissError: () -> Unit,
) {
    ModalDrawerSheet(
        drawerState = drawerState,
        modifier = Modifier.fillMaxHeight(),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            OmoDrawerBody(
                state = state,
                onClose = onClose,
                onRefreshCatalog = onRefreshCatalog,
                onRefreshChildren = onRefreshChildren,
                onOpenSession = onOpenSession,
                onSwitchAgent = onSwitchAgent,
                onSwitchModel = onSwitchModel,
                onActivateSkill = onActivateSkill,
                onOpenEventLog = onOpenEventLog,
                onDisconnect = onDisconnect,
                onDismissError = onDismissError,
            )
        }
    }
}

@Composable
private fun OmoDrawerBody(
    state: UiState,
    onClose: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onRefreshChildren: () -> Unit,
    onOpenSession: (SessionUi) -> Unit,
    onSwitchAgent: (String) -> Unit,
    onSwitchModel: (String, String, String?) -> Unit,
    onActivateSkill: (String) -> Unit,
    onOpenEventLog: () -> Unit,
    onDisconnect: () -> Unit,
    onDismissError: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current

    // Tabs stay in place while the drawer is open (and across rotation).
    var tabName by rememberSaveable { mutableStateOf(DrawerTab.Agents.name) }
    val tab = runCatching { DrawerTab.valueOf(tabName) }.getOrDefault(DrawerTab.Agents)

    var notice by remember { mutableStateOf<DrawerNotice?>(null) }
    var pendingAgent by remember { mutableStateOf<String?>(null) }
    var pendingModel by remember { mutableStateOf<String?>(null) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showDisconnectConfirm by rememberSaveable { mutableStateOf(false) }
    var showTasks by rememberSaveable { mutableStateOf(false) }

    val session = state.openSession
    val canSwitch = session != null

    // The server is the source of truth: confirmation comes from the value re-read
    // after the switch.
    LaunchedEffect(session?.agent, pendingAgent) {
        val target = pendingAgent ?: return@LaunchedEffect
        if (session?.agent == target) {
            pendingAgent = null
            val name = state.catalog.agents.firstOrNull { it.id == target }?.name ?: target
            notice = DrawerNotice("Agent active · $name")
        }
    }
    LaunchedEffect(session?.modelId, pendingModel) {
        val target = pendingModel ?: return@LaunchedEffect
        if (session?.modelId == target) {
            pendingModel = null
            val name = state.catalog.models
                .firstOrNull { it.modelID == target }?.name
                ?: target
            notice = DrawerNotice("Model active · $name")
        }
    }
    LaunchedEffect(pendingAgent) {
        if (pendingAgent == null) return@LaunchedEffect
        delay(9_000)
        if (pendingAgent != null) {
            pendingAgent = null
            notice = DrawerNotice("No confirmation from server", success = false)
        }
    }
    LaunchedEffect(pendingModel) {
        if (pendingModel == null) return@LaunchedEffect
        delay(9_000)
        if (pendingModel != null) {
            pendingModel = null
            notice = DrawerNotice("No confirmation from server", success = false)
        }
    }
    LaunchedEffect(state.error) {
        if (state.error != null) {
            pendingAgent = null
            pendingModel = null
        }
    }
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        delay(2_600)
        notice = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DrawerHeader(
            state = state,
            onClose = onClose,
            onShowTasks = { showTasks = true },
        )

        HorizontalDivider(
            thickness = 1.dp,
            color = scheme.outlineVariant.copy(alpha = 0.6f),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 12.dp),
        ) {
            DrawerTabs(
                selected = tab,
                onSelect = { tabName = it.name },
            )
        }

        DrawerToolbar(
            loadedAt = state.catalog.loadedAt,
            refreshing = state.catalogLoading,
            onRefresh = onRefreshCatalog,
        )

        AnimatedVisibility(
            visible = notice != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val current = notice
            if (current != null) {
                val tone = if (current.success) oc.success else oc.warningInk
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalContentColor provides tone) {
                        GlyphCheck(size = 13.dp)
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = current.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = tone,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when (tab) {
                DrawerTab.Agents -> AgentsTab(
                    state = state,
                    pendingAgent = pendingAgent,
                    pendingModel = pendingModel != null,
                    onOpenModelPicker = { showModelPicker = true },
                    onSwitchAgent = { agentId ->
                        pendingAgent = agentId
                        onSwitchAgent(agentId)
                    },
                    onRefreshCatalog = onRefreshCatalog,
                    onDismissError = onDismissError,
                )

                DrawerTab.Skills -> SkillsTab(
                    state = state,
                    canActivate = canSwitch,
                    onActivateSkill = { skill ->
                        onActivateSkill(skill.id)
                        notice = DrawerNotice("Skill activated · ${skill.name}")
                    },
                    onRefreshCatalog = onRefreshCatalog,
                    onDismissError = onDismissError,
                )

                DrawerTab.Mcp -> McpTab(
                    state = state,
                    onRefreshCatalog = onRefreshCatalog,
                    onDismissError = onDismissError,
                )
            }
        }

        DrawerFooter(
            onOpenEventLog = onOpenEventLog,
            onDisconnect = { showDisconnectConfirm = true },
        )
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            icon = { GlyphWarning(size = 20.dp, color = oc.error) },
            title = { Text("Disconnect from server?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "The token saved on this device will be deleted " +
                            "together with the encrypted credentials: on next launch, access " +
                            "will no longer be automatic. You'll return to the sign-in screen " +
                            "with the fields still filled in.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    Text(
                        text = "The background connection and approval " +
                            "notifications stop.",
                        style = MaterialTheme.typography.labelSmall,
                        color = oc.mutedText,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisconnectConfirm = false
                        onDisconnect()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = oc.error),
                ) {
                    Text("Disconnect", style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDisconnectConfirm = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = oc.mutedText),
                ) {
                    Text("Cancel", style = MaterialTheme.typography.labelLarge)
                }
            },
            containerColor = scheme.surfaceContainerHigh,
            iconContentColor = oc.error,
            titleContentColor = scheme.onSurface,
            textContentColor = scheme.onSurfaceVariant,
        )
    }

    if (showTasks) {
        TasksSheet(
            state = state,
            onDismiss = { showTasks = false },
            onRefresh = onRefreshChildren,
            onDismissError = onDismissError,
            onOpenSession = { child ->
                showTasks = false
                onOpenSession(child)
            },
        )
    }

    if (showModelPicker) {
        ModelPickerDialog(
            models = state.catalog.models,
            current = session,
            loading = state.catalogLoading,
            onRefresh = onRefreshCatalog,
            onDismiss = { showModelPicker = false },
            onSelect = { model ->
                showModelPicker = false
                pendingModel = model.modelID
                onSwitchModel(model.providerID, model.id, null)
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Header, tab, toolbar, footer
// ---------------------------------------------------------------------------

@Composable
private fun DrawerHeader(
    state: UiState,
    onClose: () -> Unit,
    onShowTasks: () -> Unit,
) {
    val oc = OpenColors.current
    val scheme = MaterialTheme.colorScheme
    val connected = state.connection as? Connection.Connected
    val location = state.catalog.location
    val host = serverName(state.url)
    val project = location?.projectName?.takeIf { it.isNotBlank() }
        ?: location?.directory?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: host
    val directory = location?.directory?.takeIf { it.isNotBlank() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(oc.accent.copy(alpha = 0.07f), Color.Transparent),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    color = when (state.connection) {
                        is Connection.Connected -> oc.success
                        is Connection.Connecting -> oc.warningInk
                        is Connection.Error -> oc.error
                        is Connection.Incompatible -> oc.error
                        Connection.Disconnected -> oc.mutedText
                    },
                    size = 6.dp,
                    pulsing = state.connection is Connection.Connecting,
                )
                Spacer(Modifier.width(8.dp))
                Eyebrow(
                    text = buildString {
                        append(host)
                        connected?.version?.let { append(" · v$it") }
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Close the panel" },
                ) {
                    GlyphClose(size = 17.dp)
                }
            }

            Text(
                text = project,
                style = MaterialTheme.typography.titleLarge,
                color = oc.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = directory ?: "directory not available",
                    style = monoStyle(fontSize = 10.5.sp, lineHeight = 15.sp),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                ConnectionPill(
                    connection = state.connection,
                    streamState = state.streamState,
                    secondsLeft = state.reconnectInSeconds,
                    suspended = state.retrySuspended,
                )
            }

            Spacer(Modifier.height(10.dp))

            OpenSessionCard(
                state = state,
                onShowTasks = onShowTasks,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 10.dp),
            )
        }
    }
}

@Composable
private fun ConnectionPill(
    connection: Connection,
    streamState: StreamState,
    secondsLeft: Int,
    suspended: Boolean,
) {
    val oc = OpenColors.current
    // When REST is connected, the drawer shows the actual SSE channel state: same
    // wording as the list and chat, so it doesn't look "all good" while data is
    // stale or reconnecting.
    val stream = streamStatusUi(streamState, secondsLeft, suspended)
    val (color, label, pulsing) = when (connection) {
        is Connection.Connected -> Triple(
            when (stream.tone) {
                StreamTone.Live -> oc.success
                StreamTone.Warning -> oc.warningInk
                StreamTone.Muted -> oc.mutedText
            },
            stream.label,
            stream.pulsing,
        )
        is Connection.Connecting -> Triple(oc.warningInk, "connecting", true)
        is Connection.Error -> Triple(oc.error, "not connected", false)
        is Connection.Incompatible -> Triple(oc.error, "unsupported version", false)
        Connection.Disconnected -> Triple(oc.mutedText, "disconnected", false)
    }
    StatusPill(
        label = label,
        color = color,
        pulsing = pulsing,
    )
}

@Composable
private fun TasksBadge(
    running: Int,
    total: Int,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val oc = OpenColors.current
    val color = if (running > 0) oc.success else oc.mutedText
    val label = when {
        loading -> "task…"
        running > 0 -> "$running task"
        total > 0 -> "$total task"
        else -> "no tasks"
    }
    val description = when {
        loading -> "loading background tasks"
        running > 0 -> "$running tasks running: display the background tasks"
        else -> "no tasks running"
    }

    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(
                enabled = !loading,
                onClickLabel = "Show background tasks",
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .background(color.copy(alpha = 0.12f), CircleShape)
                .border(1.dp, color.copy(alpha = 0.26f), CircleShape)
                .padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(color = color, size = 5.dp, pulsing = running > 0)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DrawerTabs(
    selected: DrawerTab,
    onSelect: (DrawerTab) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = scheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.85f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            DrawerTab.entries.forEach { tab ->
                val active = tab == selected
                val background by animateColorAsState(
                    targetValue = if (active) oc.interactive.copy(alpha = 0.16f) else Color.Transparent,
                    label = "drawer-tab-bg",
                )
                val foreground by animateColorAsState(
                    targetValue = if (active) oc.interactive else oc.mutedText,
                    label = "drawer-tab-fg",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(background)
                        .selectable(
                            selected = active,
                            role = Role.Tab,
                            onClick = { onSelect(tab) },
                        )
                        .semantics { contentDescription = "Tab ${tab.label}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = foreground,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerToolbar(
    loadedAt: Long?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val oc = OpenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                refreshing -> "refreshing…"
                loadedAt == null -> "catalog not loaded"
                else -> "updated ${relativeAge(loadedAt)}"
            },
            style = monoStyle(fontSize = 10.sp, lineHeight = 13.sp),
            color = oc.mutedText,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 1.6.dp,
                color = oc.interactive,
            )
            Spacer(Modifier.width(6.dp))
        }
        IconButton(
            onClick = onRefresh,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "Refresh the panel data" },
        ) {
            GlyphRefresh(size = 17.dp)
        }
    }
}

@Composable
private fun DrawerFooter(
    onOpenEventLog: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = 1.dp,
            color = scheme.outlineVariant.copy(alpha = 0.7f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onOpenEventLog,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(
                    text = "SSE log",
                    style = MaterialTheme.typography.labelMedium,
                    color = oc.info,
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onDisconnect,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(
                    text = "Disconnect",
                    style = MaterialTheme.typography.labelMedium,
                    color = oc.error,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tab Sessioni
// ---------------------------------------------------------------------------

@Composable
private fun OpenSessionCard(
    state: UiState,
    onShowTasks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val session = state.openSession

    if (session == null) {
        Text(
            text = "no open session",
            style = MaterialTheme.typography.bodySmall,
            color = oc.mutedText,
            maxLines = 1,
            modifier = modifier.padding(horizontal = 2.dp, vertical = 8.dp),
        )
        return
    }

    val status = sessionStatusSpec(session)
    val approvals = state.approvalCount(session.id)

    Surface(
        modifier = modifier,
        color = scheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, oc.border.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (approvals > 0) {
                        Spacer(Modifier.width(6.dp))
                        ApprovalBadge(approvals)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status.pulsing) {
                        WorkingIndicator(
                            size = 12.dp,
                            description = "session running",
                        )
                    } else {
                        StatusDot(
                            color = status.color,
                            size = 5.dp,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = status.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = status.color,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = oc.lineNumber,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = drawerSessionMeta(session),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            TasksBadge(
                running = state.runningChildren,
                total = state.children.size,
                loading = state.childrenLoading,
                onClick = onShowTasks,
            )
        }
    }
}

/** Compact session state: live (pulsing dot) or outcome. */
private data class SessionStatusSpec(
    val label: String,
    val color: Color,
    val pulsing: Boolean = false,
)

@Composable
private fun sessionStatusSpec(session: SessionUi): SessionStatusSpec {
    val oc = OpenColors.current
    if (session.isActive) return SessionStatusSpec("active", oc.success, pulsing = true)
    return when (session.outcome?.lowercase(Locale.ROOT)) {
        "succeeded", "success", "done" -> SessionStatusSpec("ok", oc.success)
        "failed", "failure", "error" -> SessionStatusSpec("error", oc.error)
        "interrupted", "aborted" -> SessionStatusSpec("interrupted", oc.warningInk)
        "running", "active" -> SessionStatusSpec("active", oc.success, pulsing = true)
        null, "" -> SessionStatusSpec("inactive", oc.mutedText)
        else -> SessionStatusSpec(
            label = session.outcome.orEmpty().ifBlank { "inactive" },
            color = oc.mutedText,
        )
    }
}

// ---------------------------------------------------------------------------
// Background task: bottom sheet opened from the session badge
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TasksSheet(
    state: UiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    onOpenSession: (SessionUi) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val children = state.childrenWithActivity
    val running = children.count { it.isActive }

    // Tasks can change while the drawer is open: the sheet re-reads its children on
    // open, without waiting for a manual refresh.
    LaunchedEffect(Unit) { onRefresh() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surfaceContainerLow,
        contentColor = scheme.onSurface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Background tasks",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = tasksSummary(running, children.size),
                        style = monoStyle(fontSize = 10.5.sp, lineHeight = 14.sp),
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                SmallRefresh(
                    description = "Refresh background tasks",
                    loading = state.childrenLoading,
                    onClick = onRefresh,
                )
            }

            if (state.error != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                ) {
                    ErrorBanner(
                        title = "Tasks not updated",
                        message = state.error,
                        onAction = onDismissError,
                        actionLabel = "Close",
                    )
                }
            }

            when {
                state.childrenLoading && children.isEmpty() -> {
                    InlineSkeletonRows(rows = 3, topPadding = 10.dp)
                }

                children.isEmpty() -> {
                    Text(
                        text = "No background tasks. Tasks are the child sessions " +
                            "of the open session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        items(
                            items = children,
                            key = { "task-${it.id}" },
                        ) { child ->
                            TaskRow(
                                session = child,
                                pendingApprovals = state.approvalCount(child.id),
                                onClick = { onOpenSession(child) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun tasksSummary(running: Int, total: Int): String {
    val count = when (total) {
        0 -> "no children"
        1 -> "1 child"
        else -> "$total children"
    }
    return if (running > 0) {
        "$running running · $count"
    } else {
        "$count · none running"
    }
}

/** Compact chip with the agent that is working (or "default"). */
@Composable
private fun AgentChip(label: String, muted: Boolean = false) {
    val oc = OpenColors.current
    val color = if (muted) oc.mutedText else oc.interactive
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f)),
    ) {
        Text(
            text = label,
            style = monoStyle(fontSize = 10.sp, lineHeight = 13.sp),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** State of a child task: live in progress, or concluded outcome. */
private data class TaskStateSpec(
    val label: String,
    val color: Color,
    val pulsing: Boolean = false,
)

@Composable
private fun taskStateSpec(session: SessionUi): TaskStateSpec {
    val oc = OpenColors.current
    if (session.isActive) return TaskStateSpec("running", oc.success, pulsing = true)
    return when (session.outcome?.lowercase(Locale.ROOT)) {
        "succeeded", "success", "done" -> TaskStateSpec("finished · ok", oc.success)
        "failed", "failure", "error" -> TaskStateSpec("finished · error", oc.error)
        "interrupted", "aborted" -> TaskStateSpec("interrupted", oc.warningInk)
        "running", "active" -> TaskStateSpec("running", oc.success, pulsing = true)
        null, "" -> TaskStateSpec("waiting", oc.mutedText)
        else -> TaskStateSpec(
            label = "finished · ${session.outcome.orEmpty()}",
            color = oc.mutedText,
        )
    }
}

@Composable
private fun TaskRow(
    session: SessionUi,
    pendingApprovals: Int,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val status = taskStateSpec(session)
    val agent = session.agent?.takeIf { it.isNotBlank() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = "Open task ${session.title}",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (pendingApprovals > 0) {
                    Spacer(Modifier.width(6.dp))
                    ApprovalBadge(pendingApprovals)
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentChip(
                    label = agent ?: "default",
                    muted = agent == null,
                )
                session.modelId?.takeIf { it.isNotBlank() }?.let { model ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = model,
                        style = monoStyle(fontSize = 10.sp, lineHeight = 13.sp),
                        color = oc.lineNumber,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (status.pulsing) {
                WorkingIndicator(
                    size = 12.dp,
                    description = "task running",
                )
            } else {
                StatusDot(color = status.color, size = 5.dp)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = status.label,
                style = MaterialTheme.typography.labelSmall,
                color = status.color,
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Agents tab (with model switch)
// ---------------------------------------------------------------------------

@Composable
private fun AgentsTab(
    state: UiState,
    pendingAgent: String?,
    pendingModel: Boolean,
    onOpenModelPicker: () -> Unit,
    onSwitchAgent: (String) -> Unit,
    onRefreshCatalog: () -> Unit,
    onDismissError: () -> Unit,
) {
    val catalog = state.catalog
    val session = state.openSession
    val canSwitch = session != null
    val loading = state.catalogLoading && catalog.agents.isEmpty()
    val empty = catalog.agents.isEmpty()
    // Agent → active children mapping: the animated indicator shows who is actually
    // working (the primary agent decides the delegation).
    val workingByAgent = state.childrenWithActivity
        .filter { it.isActive }
        .mapNotNull { it.agent?.takeIf { id -> id.isNotBlank() } }
        .groupingBy { it }
        .eachCount()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 10.dp),
    ) {
        item(key = "banners") {
            DrawerBanners(state, onRefreshCatalog = onRefreshCatalog, onDismissError = onDismissError)
        }
        if (!canSwitch) {
            item(key = "hint") {
                DrawerHint("Open a session to change agent or model: the change applies only to the open session.")
            }
        } else {
            item(key = "agent-context") {
                SessionAgentRow(state)
            }
        }
        item(key = "model-card") {
            ModelSwitchCard(
                state = state,
                pending = pendingModel,
                onClick = onOpenModelPicker,
            )
        }
        when {
            loading -> item(key = "skeleton") {
                DrawerSkeleton(rows = 5)
            }

            empty && catalog.loadedAt == null -> item(key = "empty") {
                DrawerEmpty(
                    title = "Catalog not loaded",
                    body = "Agents, skills, MCP and models come from the server. Retry.",
                    actionLabel = "Retry",
                    onAction = onRefreshCatalog,
                )
            }

            empty -> item(key = "empty") {
                DrawerEmpty(
                    title = "No agents",
                    body = "The server returned no usable agents.",
                    actionLabel = "Refresh",
                    onAction = onRefreshCatalog,
                )
            }

            else -> {
                item(key = "agents-header") {
                    DrawerSectionHeader(
                        title = "Agents",
                        count = catalog.agentsForDrawer.size,
                    )
                }
                items(
                    items = catalog.agentsForDrawer,
                    key = { "agent-${it.id}" },
                ) { agent ->
                    AgentRow(
                        agent = agent,
                        current = session?.agent,
                        pending = pendingAgent == agent.id,
                        enabled = canSwitch,
                        working = workingByAgent[agent.id] ?: 0,
                        onClick = { onSwitchAgent(agent.id) },
                    )
                }

                if (catalog.subagents.isNotEmpty()) {
                    item(key = "subagents-header") {
                        Column {
                            DrawerSectionHeader(
                                title = "Subagent",
                                count = catalog.subagents.size,
                            )
                            Text(
                                text = "invoked automatically by the primary agent",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = 18.dp,
                                    end = 18.dp,
                                    bottom = 2.dp,
                                ),
                            )
                        }
                    }
                    items(
                        items = catalog.subagents,
                        key = { "subagent-${it.id}" },
                    ) { agent ->
                        AgentRow(
                            agent = agent,
                            current = null,
                            pending = false,
                            enabled = false,
                            readOnly = true,
                            working = workingByAgent[agent.id] ?: 0,
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
}

/**
 * Agent context in the Agents tab: it's the header row and clarifies who is driving
 * the session. The green checkmark (active) only appears for a primary agent; an
 * externally configured subagent remains declared, without a checkmark.
 */
@Composable
private fun SessionAgentRow(state: UiState) {
    val session = state.openSession ?: return
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val agentId = session.agent
    val primary = state.catalog.agentsForDrawer.firstOrNull { it.id == agentId }
    val subagent = state.catalog.subagents.firstOrNull { it.id == agentId }
    val name = primary?.name
        ?: subagent?.name
        ?: agentId?.takeIf { it.isNotBlank() }
        ?: "default"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(text = "Agent", color = oc.mutedText)
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (primary != null) {
            Spacer(Modifier.width(4.dp))
            GlyphCheck(size = 13.dp, color = oc.success)
        }
        if (subagent != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "subagent",
                style = MaterialTheme.typography.labelSmall,
                color = oc.mutedText,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ModelSwitchCard(
    state: UiState,
    pending: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val session = state.openSession
    val enabled = session != null
    val current = state.catalog.models.firstOrNull { model ->
        model.modelID == session?.modelId &&
            (session.modelProvider.isNullOrBlank() || model.providerID == session.modelProvider)
    }
    val modelLabel = current?.name ?: session?.modelId ?: "default"
    val reference = modelRefLabel(session?.modelProvider, session?.modelId)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(
                    enabled = enabled,
                    onClickLabel = "Change the session model",
                    role = Role.Button,
                    onClick = onClick,
                ),
            color = scheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(
                1.dp,
                if (enabled) oc.border.copy(alpha = 0.9f) else scheme.outlineVariant.copy(alpha = 0.5f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow(text = "Model")
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (enabled) "tap to change" else "no open session",
                            style = MaterialTheme.typography.labelSmall,
                            color = oc.mutedText,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = modelLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append(session?.agent ?: "default")
                            append(" · ")
                            append(reference ?: "default model")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                if (pending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 1.8.dp,
                        color = oc.interactive,
                    )
                } else if (enabled) {
                    GlyphChevron(
                        direction = ChevronDirection.Right,
                        size = 15.dp,
                        modifier = Modifier.alpha(0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentRow(
    agent: AgentUi,
    current: String?,
    pending: Boolean,
    enabled: Boolean,
    readOnly: Boolean = false,
    working: Int = 0,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val active = !readOnly && agent.id == current
    val clickable = enabled && !readOnly && !active && !pending

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(
                when {
                    readOnly -> 0.75f
                    enabled -> 1f
                    else -> 0.55f
                },
            )
            .clickable(
                enabled = clickable,
                onClickLabel = "Change agent in ${agent.name}",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = agent.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (clickable) oc.interactive else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (working > 0) {
                    Spacer(Modifier.width(6.dp))
                    WorkingIndicator(
                        size = 12.dp,
                        description = if (working > 1) {
                            "$working tasks running"
                        } else {
                            "task running"
                        },
                    )
                    if (working > 1) {
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = working.toString(),
                            style = monoStyle(fontSize = 10.sp, lineHeight = 13.sp),
                            color = oc.success,
                            maxLines = 1,
                        )
                    }
                }
            }
            agent.description?.let { description ->
                Spacer(Modifier.height(1.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = agentModelLabel(agent),
                style = monoStyle(fontSize = 10.sp, lineHeight = 13.sp),
                color = oc.lineNumber,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        when {
            readOnly -> Unit

            pending -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 1.8.dp,
                color = oc.interactive,
            )

            active -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.semantics { contentDescription = "Agent active" },
            ) {
                GlyphCheck(size = 14.dp, color = oc.success)
                Text(
                    text = "active",
                    style = MaterialTheme.typography.labelSmall,
                    color = oc.success,
                    maxLines = 1,
                )
            }

            else -> Unit
        }
    }
}

private fun agentModelLabel(agent: AgentUi): String =
    modelRefLabel(agent.modelProvider, agent.modelId) ?: "default model"

// ---------------------------------------------------------------------------
// Tab Skill
// ---------------------------------------------------------------------------

@Composable
private fun SkillsTab(
    state: UiState,
    canActivate: Boolean,
    onActivateSkill: (SkillUi) -> Unit,
    onRefreshCatalog: () -> Unit,
    onDismissError: () -> Unit,
) {
    val catalog = state.catalog
    val oc = OpenColors.current
    val scheme = MaterialTheme.colorScheme
    val loading = state.catalogLoading && catalog.skills.isEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 10.dp),
    ) {
        item(key = "banners") {
            DrawerBanners(state, onRefreshCatalog = onRefreshCatalog, onDismissError = onDismissError)
        }
        if (!canActivate) {
            item(key = "hint") {
                DrawerHint("Open a session to activate a skill.")
            }
        }
        when {
            loading -> item(key = "skeleton") {
                DrawerSkeleton(rows = 5)
            }

            catalog.skills.isEmpty() && catalog.loadedAt == null -> item(key = "empty") {
                DrawerEmpty(
                    title = "Skills not loaded",
                    body = "The skills list comes from the server. Retry.",
                    actionLabel = "Retry",
                    onAction = onRefreshCatalog,
                )
            }

            catalog.skills.isEmpty() -> item(key = "empty") {
                DrawerEmpty(
                    title = "No skills",
                    body = "The server returned no skills.",
                    actionLabel = "Refresh",
                    onAction = onRefreshCatalog,
                )
            }

            else -> {
                item(key = "skills-header") {
                    DrawerSectionHeader(title = "Skill", count = catalog.skills.size)
                }
                items(
                    items = catalog.skills,
                    key = { "skill-${it.id}" },
                ) { skill ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (canActivate) 1f else 0.55f)
                            .clickable(
                                enabled = canActivate,
                                onClickLabel = "Activate skill ${skill.name} in the open session",
                                role = Role.Button,
                                onClick = { onActivateSkill(skill) },
                            )
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = skill.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (canActivate) oc.interactive else scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            skill.description?.let { description ->
                                Spacer(Modifier.height(1.dp))
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (canActivate) {
                            Spacer(Modifier.width(8.dp))
                            GlyphChevron(
                                direction = ChevronDirection.Right,
                                size = 13.dp,
                                modifier = Modifier.alpha(0.6f),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// MCP tab (read-only)
// ---------------------------------------------------------------------------

@Composable
private fun McpTab(
    state: UiState,
    onRefreshCatalog: () -> Unit,
    onDismissError: () -> Unit,
) {
    val catalog = state.catalog
    val scheme = MaterialTheme.colorScheme
    val loading = state.catalogLoading && catalog.mcps.isEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 10.dp),
    ) {
        item(key = "banners") {
            DrawerBanners(state, onRefreshCatalog = onRefreshCatalog, onDismissError = onDismissError)
        }
        when {
            loading -> item(key = "skeleton") {
                DrawerSkeleton(rows = 3)
            }

            catalog.mcps.isEmpty() && catalog.loadedAt == null -> item(key = "empty") {
                DrawerEmpty(
                    title = "MCP servers not loaded",
                    body = "MCP server status comes from the server. Retry.",
                    actionLabel = "Retry",
                    onAction = onRefreshCatalog,
                )
            }

            catalog.mcps.isEmpty() -> item(key = "empty") {
                DrawerEmpty(
                    title = "No MCP servers",
                    body = "No MCP servers configured on the server.",
                    actionLabel = "Refresh",
                    onAction = onRefreshCatalog,
                )
            }

            else -> {
                item(key = "mcp-header") {
                    DrawerSectionHeader(title = "MCP servers", count = catalog.mcps.size)
                }
                item(key = "mcp-note") {
                    Text(
                        text = "Read-only status, exactly as reported by the server.",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                    )
                }
                items(
                    items = catalog.mcps,
                    key = { "mcp-${it.name}" },
                ) { mcp ->
                    McpRow(mcp)
                }
            }
        }
    }
}

@Composable
private fun McpRow(mcp: McpUi) {
    val scheme = MaterialTheme.colorScheme
    val spec = mcpStatusSpec(mcp.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = spec.color, size = 7.dp, pulsing = spec.pulsing)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mcp.name,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = spec.label,
                style = MaterialTheme.typography.bodySmall,
                color = spec.color,
                maxLines = 1,
            )
        }
    }
}

private data class McpStatusSpec(
    val label: String,
    val color: Color,
    val pulsing: Boolean = false,
)

@Composable
private fun mcpStatusSpec(status: String): McpStatusSpec {
    val oc = OpenColors.current
    val key = status.lowercase(Locale.ROOT)
    return when {
        key.contains("connect") -> McpStatusSpec("connected", oc.success)
        key.contains("fail") || key.contains("error") -> McpStatusSpec("error", oc.error)
        key.contains("auth") -> McpStatusSpec("requires authentication", oc.warningInk)
        key.contains("disabled") -> McpStatusSpec("disabled", oc.mutedText)
        key.isBlank() || key == "unknown" -> McpStatusSpec("unknown status", oc.warningInk)
        else -> McpStatusSpec(status, oc.warningInk)
    }
}

// ---------------------------------------------------------------------------
// Shared states: banner, hint, empty, skeleton
// ---------------------------------------------------------------------------

@Composable
private fun DrawerBanners(
    state: UiState,
    onRefreshCatalog: () -> Unit,
    onDismissError: () -> Unit,
) {
    val catalogError = state.catalogError
    if (catalogError != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
        ) {
            ErrorBanner(
                title = "Catalog not updated",
                message = catalogError,
                onAction = onRefreshCatalog,
                actionLabel = "Retry",
            )
        }
    }
    val actionError = state.error
    if (actionError != null && actionError != catalogError) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
        ) {
            ErrorBanner(
                title = "Operation failed",
                message = actionError,
                onAction = onDismissError,
                actionLabel = "Close",
            )
        }
    }
}

@Composable
private fun DrawerHint(text: String) {
    val oc = OpenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = oc.info, size = 5.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = oc.info,
            maxLines = 3,
        )
    }
}

@Composable
private fun DrawerEmpty(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = ">_",
            style = monoStyle(fontSize = 16.sp, lineHeight = 20.sp, weight = androidx.compose.ui.text.font.FontWeight.Medium),
            color = oc.mutedText,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onAction,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = oc.interactive,
                )
            }
        }
    }
}

@Composable
private fun DrawerSkeleton(rows: Int) {
    InlineSkeletonRows(rows = rows, topPadding = 10.dp)
}

@Composable
private fun InlineSkeletonRows(rows: Int, topPadding: androidx.compose.ui.unit.Dp = 6.dp) {
    val alpha = rememberSkeletonAlpha()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = topPadding, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(rows) { index ->
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                SkeletonBar(
                    widthFraction = 0.58f - (index % 3) * 0.07f,
                    height = 12.dp,
                    alpha = alpha,
                )
                SkeletonBar(
                    widthFraction = 0.88f - (index % 2) * 0.12f,
                    height = 9.dp,
                    alpha = alpha,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pezzi riusabili
// ---------------------------------------------------------------------------

@Composable
private fun DrawerSectionHeader(
    title: String,
    count: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val oc = OpenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(text = title, color = oc.mutedText)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = count.toString(),
                style = monoStyle(fontSize = 10.sp, lineHeight = 13.sp, weight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = oc.lineNumber,
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun SmallRefresh(
    description: String,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val oc = OpenColors.current
    IconButton(
        onClick = onClick,
        enabled = !loading,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description },
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.6.dp,
                color = oc.mutedText,
            )
        } else {
            GlyphRefresh(size = 16.dp)
        }
    }
}
