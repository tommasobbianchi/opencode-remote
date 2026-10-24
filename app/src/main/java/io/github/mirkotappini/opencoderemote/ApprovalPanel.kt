package io.github.mirkotappini.opencoderemote

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mirkotappini.opencoderemote.net.ApprovalUi
import kotlin.math.abs

/**
 * Pannello delle approvazioni in chat.
 *
 * Non è un dialog: vive sopra il composer e lascia leggibile la conversazione.
 * Con più richieste la prima è espansa, le altre restano in una pila compatta;
 * si cambia richiesta con swipe orizzontale, con le frecce o toccando una riga.
 *
 * "Consenti sempre" non è mai il default: apre una conferma che spiega che la
 * regola vale per sempre sul server e mostra esattamente cosa verrà salvato.
 */
@Composable
fun ApprovalPanel(
    approvals: List<ApprovalUi>,
    loading: Boolean,
    error: String?,
    onReply: (id: String, decision: String, message: String?) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (approvals.isEmpty()) return

    val oc = OpenColors.current
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf<ApprovalSending?>(null) }
    var confirmAlways by remember { mutableStateOf<ApprovalUi?>(null) }

    LaunchedEffect(approvals.map { it.id }) {
        if (selectedId == null || approvals.none { it.id == selectedId }) {
            selectedId = approvals.firstOrNull()?.id
        }
        val inFlight = sending ?: return@LaunchedEffect
        if (approvals.none { it.id == inFlight.requestId }) sending = null
    }
    LaunchedEffect(approvals.map { it.id }) {
        val dialogRequest = confirmAlways ?: return@LaunchedEffect
        if (approvals.none { it.id == dialogRequest.id }) confirmAlways = null
    }
    LaunchedEffect(error) {
        if (error != null) sending = null
    }

    val selectedIndex = approvals.indexOfFirst { it.id == selectedId }
        .let { if (it >= 0) it else 0 }
    val selected = approvals[selectedIndex]

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, oc.warningInk.copy(alpha = 0.34f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ApprovalPanelHeader(
                count = approvals.size,
                selectedIndex = selectedIndex,
                loading = loading,
                busy = sending != null,
                onSelect = { index -> if (sending == null) selectedId = approvals[index].id },
                onRefresh = onRefresh,
            )

            if (error != null) {
                ApprovalErrorNote(
                    message = error,
                    onRefresh = onRefresh,
                )
            }

            ExpandedApprovalCard(
                approval = selected,
                index = selectedIndex,
                total = approvals.size,
                sending = sending,
                onSelect = { index -> if (sending == null) selectedId = approvals[index].id },
                onOnce = {
                    sending = ApprovalSending(selected.id, DECISION_ONCE)
                    onReply(selected.id, DECISION_ONCE, null)
                },
                onReject = {
                    sending = ApprovalSending(selected.id, DECISION_REJECT)
                    onReply(selected.id, DECISION_REJECT, null)
                },
                onAlways = { confirmAlways = selected },
            )

            val collapsed = approvals
                .filterIndexed { index, _ -> index != selectedIndex }
                .take(3)
            if (collapsed.isNotEmpty()) {
                collapsed.forEach { approval ->
                    CollapsedApprovalRow(
                        approval = approval,
                        enabled = sending == null,
                        onClick = { selectedId = approval.id },
                    )
                }
                val hidden = approvals.size - 1 - collapsed.size
                if (hidden > 0) {
                    Text(
                        text = "+$hidden more requests · use the arrows to scroll",
                        style = MaterialTheme.typography.labelSmall,
                        color = oc.mutedText,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                }
            }
        }
    }

    val always = confirmAlways
    if (always != null) {
        AlwaysConfirmationDialog(
            approval = always,
            sending = sending,
            onDismiss = { confirmAlways = null },
            onConfirm = {
                confirmAlways = null
                sending = ApprovalSending(always.id, DECISION_ALWAYS)
                onReply(always.id, DECISION_ALWAYS, null)
            },
        )
    }
}

/** Avviso non allarmistico per una richiesta già risolta altrove (404). */
@Composable
fun ApprovalExpiredNotice(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val oc = OpenColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, oc.info.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            StatusDot(oc.info, size = 5.dp)
            Text(
                text = "Request no longer active: it was already resolved. " +
                    "The approvals list has been updated.",
                style = MaterialTheme.typography.bodySmall,
                color = oc.mutedText,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Text("Chiudi", style = MaterialTheme.typography.labelMedium, color = oc.interactive)
            }
        }
    }
}

@Composable
private fun ApprovalPanelHeader(
    count: Int,
    selectedIndex: Int,
    loading: Boolean,
    busy: Boolean,
    onSelect: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    val oc = OpenColors.current
    val canGoBack = selectedIndex > 0
    val canGoForward = selectedIndex < count - 1

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphWarning(size = 16.dp, color = oc.warningInk)
        Spacer(Modifier.width(7.dp))
        Text(
            text = if (count == 1) "Approval requested" else "Approvals",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count > 1) {
            Spacer(Modifier.width(7.dp))
            Surface(
                color = oc.warningInk.copy(alpha = 0.12f),
                contentColor = oc.warningInk,
                shape = CircleShape,
                border = BorderStroke(1.dp, oc.warningInk.copy(alpha = 0.24f)),
                modifier = Modifier.semantics {
                    contentDescription =
                        "Richiesta ${selectedIndex + 1} di $count"
                },
            ) {
                Text(
                    text = "${selectedIndex + 1}/$count",
                    style = monoStyle(fontSize = 10.sp, lineHeight = 14.sp, weight = FontWeight.Medium),
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        if (count > 1) {
            IconButton(
                onClick = { onSelect(selectedIndex - 1) },
                enabled = canGoBack && !busy,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Richiesta precedente" },
            ) {
                GlyphChevron(direction = ChevronDirection.Left, size = 16.dp)
            }
            IconButton(
                onClick = { onSelect(selectedIndex + 1) },
                enabled = canGoForward && !busy,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Richiesta successiva" },
            ) {
                GlyphChevron(direction = ChevronDirection.Right, size = 16.dp)
            }
        }
        if (loading) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 1.8.dp,
                    color = oc.interactive,
                )
            }
        } else {
            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Refresh approval requests" },
            ) {
                GlyphRefresh(size = 16.dp)
            }
        }
    }
}

@Composable
private fun ExpandedApprovalCard(
    approval: ApprovalUi,
    index: Int,
    total: Int,
    sending: ApprovalSending?,
    onSelect: (Int) -> Unit,
    onOnce: () -> Unit,
    onReject: () -> Unit,
    onAlways: () -> Unit,
) {
    val oc = OpenColors.current
    val scheme = MaterialTheme.colorScheme
    val busy = sending != null
    val currentSending = sending?.takeIf { it.requestId == approval.id }
    val firstResource = approval.resources.firstOrNull()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(approval.id, total) {
                var travelled = 0f
                val threshold = 64.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        travelled += dragAmount
                        if (abs(travelled) >= threshold) {
                            val next = if (travelled < 0f) {
                                (index + 1).coerceAtMost(total - 1)
                            } else {
                                (index - 1).coerceAtLeast(0)
                            }
                            travelled = 0f
                            if (next != index) onSelect(next)
                        }
                    },
                )
            }
            .semantics {
                contentDescription = buildString {
                    append("Richiesta ${index + 1} di $total: ")
                    append(approval.action)
                    firstResource?.let { append(" $it") }
                }
            },
        color = scheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, oc.border.copy(alpha = 0.7f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = approval.action,
                    style = monoStyle(fontSize = 14.sp, lineHeight = 20.sp, weight = FontWeight.Medium),
                    color = oc.interactive,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (firstResource != null) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = firstResource,
                        style = monoStyle(fontSize = 13.sp, lineHeight = 20.sp),
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val extraResources = (approval.resources.size - 1).coerceAtLeast(0)
            if (extraResources > 0 || approval.save.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (extraResources > 0) {
                        ApprovalChip(
                            text = "+$extraResources risorse",
                            color = oc.info,
                        )
                    }
                    if (approval.save.isNotEmpty()) {
                        ApprovalChip(
                            text = "save +${approval.save.size}",
                            color = oc.warningInk,
                        )
                    }
                }
            }

            val origin = approval.sourceLabel?.let { "source $it · " } ?: "server source · "
            Text(
                text = origin + shortApprovalId(approval.id),
                style = monoStyle(fontSize = 10.5.sp, lineHeight = 14.sp),
                color = oc.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            approval.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = oc.mutedText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onOnce,
                    enabled = !busy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = oc.interactive,
                        contentColor = oc.onInteractive,
                        disabledContainerColor = oc.interactive.copy(alpha = 0.4f),
                        disabledContentColor = oc.onInteractive.copy(alpha = 0.7f),
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    if (currentSending?.decision == DECISION_ONCE) {
                        ActionSpinner(color = oc.onInteractive)
                    } else {
                        Text(
                            text = "Allow once",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    }
                }

                OutlinedButton(
                    onClick = onReject,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, oc.border),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = scheme.onSurface,
                        disabledContentColor = oc.mutedText.copy(alpha = 0.5f),
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                ) {
                    if (currentSending?.decision == DECISION_REJECT) {
                        ActionSpinner(color = scheme.onSurface)
                    } else {
                        Text("Nega", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "applies forever on the server",
                    style = MaterialTheme.typography.labelSmall,
                    color = oc.mutedText,
                )
                Spacer(Modifier.width(6.dp))
                TextButton(
                    onClick = onAlways,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = oc.warningInk,
                        disabledContentColor = oc.warningInk.copy(alpha = 0.4f),
                    ),
                ) {
                    if (currentSending?.decision == DECISION_ALWAYS) {
                        ActionSpinner(color = oc.warningInk)
                    } else {
                        Text("Always allow", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsedApprovalRow(
    approval: ApprovalUi,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val oc = OpenColors.current
    val firstResource = approval.resources.firstOrNull()
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, oc.border.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = approval.action,
                style = monoStyle(fontSize = 12.sp, lineHeight = 16.sp, weight = FontWeight.Medium),
                color = oc.interactive,
                maxLines = 1,
            )
            if (firstResource != null) {
                Text(
                    text = firstResource,
                    style = monoStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
                    color = oc.mutedText,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            GlyphChevron(direction = ChevronDirection.Right, size = 14.dp)
        }
    }
}

@Composable
private fun ApprovalErrorNote(
    message: String,
    onRefresh: () -> Unit,
) {
    val oc = OpenColors.current
    val expired = message.contains("no longer valid", ignoreCase = true)
    val tone = if (expired) oc.info else oc.warningInk
    val text = if (expired) {
        "Request no longer active: it was already resolved. The list has been updated."
    } else {
        "Could not send the reply. Check the connection and retry."
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tone.copy(alpha = 0.08f), RoundedCornerShape(9.dp))
            .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(tone, size = 5.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tone,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onRefresh,
            modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 9.dp),
        ) {
            Text("Aggiorna", style = MaterialTheme.typography.labelMedium, color = oc.interactive)
        }
    }
}

@Composable
private fun AlwaysConfirmationDialog(
    approval: ApprovalUi,
    sending: ApprovalSending?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val oc = OpenColors.current
    val scheme = MaterialTheme.colorScheme
    val busy = sending != null

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { GlyphWarning(size = 20.dp, color = oc.warningInk) },
        title = {
            Text(
                text = "Always allow?",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "This choice applies forever on the OpenCode server, not just " +
                        "for this request. Future requests with the same rule " +
                        "will no longer be asked.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    text = "It will be saved on the server:",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurface,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = oc.formField,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, oc.border),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (approval.save.isEmpty()) {
                            Text(
                                text = "The server did not provide the rule details.",
                                style = MaterialTheme.typography.bodySmall,
                                color = oc.mutedText,
                            )
                        } else {
                            approval.save.forEach { rule ->
                                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Text(
                                        text = "·",
                                        style = monoStyle(fontSize = 12.sp, lineHeight = 17.sp),
                                        color = oc.warningInk,
                                    )
                                    Text(
                                        text = rule,
                                        style = monoStyle(fontSize = 12.sp, lineHeight = 17.sp),
                                        color = scheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    text = "You can revoke the rule from the server anytime.",
                    style = MaterialTheme.typography.labelSmall,
                    color = oc.mutedText,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = oc.warningInk,
                    disabledContentColor = oc.warningInk.copy(alpha = 0.4f),
                ),
            ) {
                if (sending?.decision == DECISION_ALWAYS) {
                    ActionSpinner(color = oc.warningInk)
                } else {
                    Text("Always allow", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = oc.mutedText),
            ) {
                Text("Annulla", style = MaterialTheme.typography.labelLarge)
            }
        },
        containerColor = scheme.surfaceContainerHigh,
        iconContentColor = oc.warningInk,
        titleContentColor = scheme.onSurface,
        textContentColor = scheme.onSurfaceVariant,
    )
}

@Composable
private fun ApprovalChip(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = CircleShape,
        border = BorderStroke(1.dp, color.copy(alpha = 0.22f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun ActionSpinner(color: Color) {
    CircularProgressIndicator(
        modifier = Modifier.size(15.dp),
        strokeWidth = 2.dp,
        color = color,
    )
}

private data class ApprovalSending(
    val requestId: String,
    val decision: String,
)

private fun shortApprovalId(id: String): String =
    if (id.length <= 10) id else id.take(9) + "…"

private const val DECISION_ONCE = "once"
private const val DECISION_ALWAYS = "always"
private const val DECISION_REJECT = "reject"
