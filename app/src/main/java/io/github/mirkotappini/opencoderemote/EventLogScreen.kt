package io.github.mirkotappini.opencoderemote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Log SSE di M0, raggiungibile dalla lista sessioni.
 * In M1 lo stream resta disponibile ma non pilota la timeline.
 */
@Composable
fun EventLogScreen(
    state: UiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val listState = rememberLazyListState()

    LaunchedEffect(state.events.size) {
        if (state.events.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= info.totalItemsCount - 3) {
            listState.animateScrollToItem(state.events.lastIndex)
        }
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
                        .widthIn(max = Layout.logMaxWidth)
                        .fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = "Back to session list" },
                        ) {
                            GlyphChevron(
                                direction = ChevronDirection.Left,
                                size = 20.dp,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SSE log",
                                style = MaterialTheme.typography.titleLarge,
                                color = oc.accent,
                                maxLines = 1,
                            )
                            Text(
                                text = "Event stream from server · M0",
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        StatusPill(
                            label = if (state.streaming) "live" else "stopped",
                            color = if (state.streaming) oc.success else oc.mutedText,
                            pulsing = state.streaming,
                        )
                    }
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = scheme.outlineVariant.copy(alpha = 0.7f),
                    )
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = Layout.logMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 14.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onStart,
                            enabled = !state.streaming,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                        ) {
                            Text("Start")
                        }
                        OutlinedButton(
                            onClick = onStop,
                            enabled = state.streaming,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                        ) {
                            Text("Stop")
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "events ${state.events.size}/200",
                            style = monoStyle(fontSize = 10.5.sp, lineHeight = 14.sp),
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }

                    state.error
                        ?.takeIf { !state.streaming }
                        ?.let { message ->
                            ErrorBanner(
                                title = "Stream unavailable",
                                message = message,
                                onAction = onStart,
                            )
                        }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = Layout.logMaxWidth)
                        .fillMaxSize(),
                    color = scheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, oc.border),
                ) {
                    if (state.events.isEmpty()) {
                        EmptyLog(streaming = state.streaming)
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                        ) {
                            items(state.events) { line ->
                                EventLogLine(line)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventLogLine(line: String) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val parts = splitEventLine(line)
    val kind = parts.kind.lowercase()
    val kindColor = when {
        kind.contains("error") || kind.contains("fail") -> oc.error
        kind.contains("system") -> oc.info
        kind.contains("tool") -> oc.warningInk
        kind.contains("session") -> oc.accent
        kind.contains("message") || kind.contains("part") -> oc.interactive
        else -> oc.mutedText
    }

    val text = buildAnnotatedString {
        if (parts.time.isNotBlank()) {
            withStyle(SpanStyle(color = oc.lineNumber)) {
                append(parts.time)
                append("  ")
            }
        }
        if (parts.kind.isNotBlank()) {
            withStyle(
                SpanStyle(
                    color = kindColor,
                    fontWeight = FontWeight.Medium,
                ),
            ) {
                append('[')
                append(parts.kind)
                append("] ")
            }
        }
        withStyle(SpanStyle(color = oc.text)) {
            append(parts.payload)
        }
    }

    SelectionContainer {
        Text(
            text = text,
            style = monoStyle(fontSize = 11.5.sp, lineHeight = 17.sp),
            modifier = Modifier.padding(vertical = 3.dp),
        )
    }
}

@Composable
private fun EmptyLog(streaming: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "›_",
            style = monoStyle(
                fontSize = 20.sp,
                lineHeight = 24.sp,
                weight = FontWeight.Medium,
            ),
            color = oc.accent,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = if (streaming) {
                "waiting for events…"
            } else {
                "no events · start the stream"
            },
            style = monoStyle(fontSize = 11.5.sp, lineHeight = 17.sp),
            color = scheme.onSurfaceVariant,
        )
    }
}
