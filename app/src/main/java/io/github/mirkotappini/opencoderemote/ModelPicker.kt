package io.github.mirkotappini.opencoderemote

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
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mirkotappini.opencoderemote.net.ModelUi
import io.github.mirkotappini.opencoderemote.net.pickerKey
import io.github.mirkotappini.opencoderemote.net.referencesSession
import io.github.mirkotappini.opencoderemote.net.SessionUi
import java.util.Locale

/**
 * Picker del modello a schermo intero, con ricerca e gruppi per provider.
 * Funziona anche quando la sessione non ha ancora un modello (caso reale:
 * `GET /api/session/{id}` non espone `model` finché non c'è un cambio o un turno).
 */
@Composable
fun ModelPickerDialog(
    models: List<ModelUi>,
    current: SessionUi?,
    loading: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (ModelUi) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        ModelPickerContent(
            models = models,
            current = current,
            loading = loading,
            onRefresh = onRefresh,
            onDismiss = onDismiss,
            onSelect = onSelect,
        )
    }
}

@Composable
private fun ModelPickerContent(
    models: List<ModelUi>,
    current: SessionUi?,
    loading: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (ModelUi) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    var query by rememberSaveable { mutableStateOf("") }

    val normalized = query.trim()
    val groups = remember(models, normalized) {
        val filtered = if (normalized.isEmpty()) {
            models
        } else {
            models.filter { model ->
                model.displayName().contains(normalized, ignoreCase = true) ||
                    model.modelID.contains(normalized, ignoreCase = true) ||
                    model.providerID.contains(normalized, ignoreCase = true) ||
                    model.family?.contains(normalized, ignoreCase = true) == true
            }
        }
        filtered
            .groupBy { it.providerID.ifBlank { "altro" } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .mapValues { (_, list) -> list.sortedBy { it.displayName().lowercase(Locale.ROOT) } }
    }
    val resultCount = groups.values.sumOf { it.size }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = scheme.background,
        contentColor = scheme.onBackground,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 6.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Chiudi l'elenco dei modelli" },
                    ) {
                        GlyphChevron(direction = ChevronDirection.Left, size = 20.dp)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Modello",
                            style = MaterialTheme.typography.titleLarge,
                            color = oc.accent,
                            maxLines = 1,
                        )
                        Text(
                            text = if (current != null) {
                                "Sessione: ${current.title}"
                            } else {
                                "Nessuna sessione aperta"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = if (normalized.isEmpty()) {
                            "${models.size} modelli"
                        } else {
                            "$resultCount/${models.size}"
                        },
                        style = monoStyle(fontSize = 10.5.sp, lineHeight = 14.sp),
                        color = oc.mutedText,
                        maxLines = 1,
                    )
                    IconButton(
                        onClick = onRefresh,
                        enabled = !loading,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Aggiorna l'elenco dei modelli" },
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 1.7.dp,
                                color = oc.mutedText,
                            )
                        } else {
                            GlyphRefresh(size = 17.dp)
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    ModelSearchField(
                        query = query,
                        onQueryChange = { query = it },
                    )
                }

                HorizontalDivider(
                    thickness = 1.dp,
                    color = scheme.outlineVariant.copy(alpha = 0.7f),
                )

                when {
                    loading && models.isEmpty() -> PickerSkeleton()
                    models.isEmpty() -> PickerBlank(
                        title = "Nessun modello",
                        body = "Il catalogo dei modelli non è ancora arrivato dal server.",
                        actionLabel = "Riprova",
                        onAction = onRefresh,
                    )

                    resultCount == 0 -> PickerBlank(
                        title = "Nessun risultato",
                        body = "Nessun modello corrisponde a «${normalized}».",
                        actionLabel = "Cancella la ricerca",
                        onAction = { query = "" },
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        groups.forEach { (provider, providerModels) ->
                            item(key = "provider-$provider") {
                                ProviderHeader(
                                    provider = provider,
                                    count = providerModels.size,
                                )
                            }
                            items(
                                items = providerModels,
                                key = { "model-${it.pickerKey()}" },
                            ) { model ->
                                ModelRow(
                                    model = model,
                                    selected = model.referencesSession(current?.modelId, current?.modelProvider),
                                    onClick = { onSelect(model) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderHeader(provider: String, count: Int) {
    val oc = OpenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(text = provider, color = oc.mutedText)
        Spacer(Modifier.width(8.dp))
        Text(
            text = count.toString(),
            style = monoStyle(fontSize = 10.sp, lineHeight = 13.sp),
            color = oc.lineNumber,
            maxLines = 1,
        )
        Spacer(Modifier.width(12.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun ModelRow(
    model: ModelUi,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) oc.interactive.copy(alpha = 0.07f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(
                onClickLabel = "Usa il modello ${model.displayName()}",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.displayName(),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) {
                    Spacer(Modifier.width(8.dp))
                    StatusPill(label = "in uso", color = oc.success)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${model.providerID.ifBlank { "provider" }} · ${model.modelID}",
                style = monoStyle(fontSize = 10.sp, lineHeight = 13.sp),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (selected) {
            GlyphCheck(size = 15.dp, color = oc.success)
        }
    }
}

@Composable
private fun ModelSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = scheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.9f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides oc.mutedText,
            ) {
                GlyphSearch(size = 16.dp)
            }
            Spacer(Modifier.width(9.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(oc.interactive),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = "Cerca per nome o provider",
                                style = MaterialTheme.typography.bodyMedium,
                                color = oc.mutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Cancella la ricerca" },
                ) {
                    GlyphClose(size = 14.dp)
                }
            }
        }
    }
}

@Composable
private fun PickerSkeleton() {
    val alpha = rememberSkeletonAlpha()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        repeat(6) { index ->
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                SkeletonBar(
                    widthFraction = 0.4f + (index % 3) * 0.08f,
                    height = 13.dp,
                    alpha = alpha,
                )
                SkeletonBar(
                    widthFraction = 0.72f - (index % 2) * 0.1f,
                    height = 9.dp,
                    alpha = alpha,
                )
            }
        }
    }
}

@Composable
private fun PickerBlank(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .border(1.dp, scheme.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "≡",
                    style = monoStyle(fontSize = 18.sp, lineHeight = 22.sp),
                    color = oc.mutedText,
                )
            }
            Spacer(Modifier.height(14.dp))
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
            Spacer(Modifier.height(6.dp))
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

private fun ModelUi.displayName(): String =
    name?.takeIf { it.isNotBlank() } ?: id

