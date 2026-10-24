package io.github.mirkotappini.opencoderemote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mirkotappini.opencoderemote.net.CommandUi
import io.github.mirkotappini.opencoderemote.net.FormFieldUi
import io.github.mirkotappini.opencoderemote.net.FormOptionUi
import io.github.mirkotappini.opencoderemote.net.FormUi
import io.github.mirkotappini.opencoderemote.net.FormValue
import java.util.Locale

// ---------------------------------------------------------------------------
// M9 — domande del server (form) e comandi slash.
//
// Solo presentazione: gli stati e le chiamate stanno in `AppViewModel`, qui si
// legge il contratto `FormUi` / `CommandUi` e si restituiscono risposte già
// tipizzate (`FormValue`). Nessun altro stato: quello che il server sa non si
// duplica.
//
// La domanda è un pannello **fisso sopra il composer** (non nella lista: non
// deve uscire dallo schermo mentre scorri il turno). I comandi sono un foglio
// che si apre dal draft, quindi la tastiera resta dov'è.
// ---------------------------------------------------------------------------

/**
 * Altezza massima dei campi prima di diventare scorrevoli: così il pannello non
 * mangia la chat né il composer, anche con un form da molti campi o in orizzontale.
 */
private fun fieldsMaxHeightDp(screenHeightDp: Int): Int =
    (screenHeightDp * 0.42f).coerceIn(140f, 380f).toInt()

private val FIELD_SHAPE = RoundedCornerShape(12.dp)
private val PANEL_SHAPE = RoundedCornerShape(16.dp)
private val ACTION_SHAPE = RoundedCornerShape(11.dp)
private val CHIP_SHAPE = RoundedCornerShape(10.dp)

// ---------------------------------------------------------------------------
// Tipi di campo
// ---------------------------------------------------------------------------

/**
 * Controllo da disegnare per un campo [`FormFieldUi.type`]. I nomi sono quelli
 * dell'API (`string`, `boolean`, `integer`, `number`, `multiselect`, `external`);
 * un tipo che non conosciamo non rompe il pannello, resta una riga non componibile.
 */
internal enum class FormControl { Text, Choice, Integer, Number, Checkbox, Chips, External, Unknown }

/**
 * Il tool "question" del server manda `string` **con** `options`: è una scelta
 * singola, non testo libero. Senza opzioni resta un campo di testo.
 */
internal fun controlOf(field: FormFieldUi): FormControl {
    val base = controlOf(field.type)
    return if (base == FormControl.Text && field.options.isNotEmpty()) {
        FormControl.Choice
    } else {
        base
    }
}

internal fun controlOf(type: String): FormControl = when (type.trim().lowercase(Locale.ROOT)) {
    "string", "text" -> FormControl.Text
    "integer", "int" -> FormControl.Integer
    "number", "float", "double" -> FormControl.Number
    "boolean", "bool" -> FormControl.Checkbox
    "multiselect", "multi-select" -> FormControl.Chips
    "external" -> FormControl.External
    else -> FormControl.Unknown
}

// ---------------------------------------------------------------------------
// Pannello della domanda
// ---------------------------------------------------------------------------

/**
 * Pannello fissato sopra il composer. Un controllo per campo (i campi `hidden`
 * e i tipi non previsti sono già scartati a monte, in `FormParsing`), "reply"
 * spento finché i campi obbligatori non sono compilati, "cancel" sempre
 * disponibile mentre non c'è un invio in corso.
 */
@Composable
internal fun QuestionPanel(
    form: FormUi,
    error: String?,
    onReply: (Map<String, FormValue>) -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current

    val fields = remember(form.fields) { form.fields.filter { it.key.isNotBlank() } }
    val answers = remember(form.id) { mutableStateMapOf<String, FormValue>() }
    // Il testo grezzo serve solo a testo e numerici: un numero va conservato
    // così com'è digitato ("1," e "-" sono stati intermedi legittimi).
    val raw = remember(form.id) { mutableStateMapOf<String, String>() }
    var submitted by remember(form.id) { mutableStateOf(false) }

    // L'invio è "in corso" finché la domanda non sparisce; se arriva un errore
    // i pulsanti tornano attivi, così si può riprovare senza riaprire nulla.
    val busy = submitted && error == null
    val missing = fields.count { it.required && !hasAnswer(it, answers[it.key]) }
    val invalid = fields.any { valueProblem(it, answers[it.key], raw[it.key]) != null }
    val canReply = !busy && missing == 0 && !invalid

    fun payload(): Map<String, FormValue> = buildMap {
        fields.forEach { field ->
            val value = answers[field.key]?.let(::replyValue) ?: return@forEach
            put(field.key, value)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            // TalkBack annuncia il pannello una volta sola; i figli restano
            // navigabili uno per uno.
            .semantics { contentDescription = "opencode question" },
        shape = PANEL_SHAPE,
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, oc.interactive.copy(alpha = 0.34f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // L'eyebrow è solo l'etichetta visiva: la descrizione del pannello
            // la dice già a voce, non serve ripeterla.
            Eyebrow(
                text = "question",
                color = oc.interactive,
                modifier = Modifier.clearAndSetSemantics { },
            )

            if (form.title.isNotBlank()) {
                Text(
                    text = form.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = oc.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            form.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = oc.mutedText,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = fieldsMaxHeightDp(LocalConfiguration.current.screenHeightDp).dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                fields.forEach { field ->
                    key(field.key) {
                        FormFieldBlock(
                            field = field,
                            value = answers[field.key],
                            rawText = raw[field.key].orEmpty(),
                            problem = valueProblem(field, answers[field.key], raw[field.key]),
                            enabled = !busy,
                            onRawText = { text ->
                                val control = controlOf(field)
                                if (control == FormControl.Integer || control == FormControl.Number) {
                                    // Un input non numerico viene scartato: il campo
                                    // resta com'era invece di mostrare testo che poi
                                    // non si può inviare.
                                    if (sanitizeNumberInput(text, control == FormControl.Integer)) {
                                        raw[field.key] = text
                                        val parsed = numericValueOf(text)
                                        if (parsed == null) {
                                            answers.remove(field.key)
                                        } else {
                                            answers[field.key] = FormValue.Number(parsed)
                                        }
                                    }
                                } else {
                                    raw[field.key] = text
                                    answers[field.key] = FormValue.Text(text)
                                }
                            },
                            onValue = { value ->
                                if (value == null) answers.remove(field.key) else answers[field.key] = value
                            },
                        )
                    }
                }
            }

            if (error != null) {
                FormErrorNote(
                    message = formErrorLabel(error),
                    onRefresh = {
                        submitted = false
                        onRefresh()
                    },
                )
            }

            if (missing > 0) {
                Text(
                    text = if (missing == 1) {
                        "1 required answer missing"
                    } else {
                        "$missing required answers missing"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = oc.mutedText,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        submitted = true
                        onReply(payload())
                    },
                    enabled = canReply,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = ACTION_SHAPE,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = oc.interactive,
                        contentColor = oc.onInteractive,
                        disabledContainerColor = oc.interactive.copy(alpha = 0.4f),
                        disabledContentColor = oc.onInteractive.copy(alpha = 0.7f),
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    if (busy) {
                        FormSpinner(color = oc.onInteractive)
                    } else {
                        Text(text = "reply", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }

                OutlinedButton(
                    onClick = onCancel,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = ACTION_SHAPE,
                    border = BorderStroke(1.dp, oc.border),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = scheme.onSurface,
                        disabledContentColor = oc.mutedText.copy(alpha = 0.5f),
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                ) {
                    Text(text = "cancel", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun FormFieldBlock(
    field: FormFieldUi,
    value: FormValue?,
    rawText: String,
    problem: String?,
    enabled: Boolean,
    onRawText: (String) -> Unit,
    onValue: (FormValue?) -> Unit,
) {
    val oc = OpenColors.current
    val control = controlOf(field)
    val locked = !enabled

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FieldLabel(field = field, mono = field.title.isNullOrBlank())

        field.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = oc.mutedText,
            )
        }

        when (control) {
            FormControl.Text -> AnswerTextField(
                text = rawText,
                enabled = !locked,
                placeholder = "write your answer",
                keyboardType = KeyboardType.Text,
                singleLine = false,
                onText = onRawText,
            )

            FormControl.Choice -> ChoiceAnswer(
                options = field.options,
                selected = (value as? FormValue.Text)?.value,
                enabled = !locked,
                onSelect = { chosen -> onValue(FormValue.Text(chosen)) },
            )

            FormControl.Integer, FormControl.Number -> {
                AnswerTextField(
                    text = rawText,
                    enabled = !locked,
                    placeholder = if (control == FormControl.Integer) "whole number" else "number",
                    keyboardType = if (control == FormControl.Integer) {
                        KeyboardType.Number
                    } else {
                        KeyboardType.Decimal
                    },
                    singleLine = true,
                    mono = true,
                    onText = onRawText,
                )
                rangeHint(field)?.let { hint ->
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = oc.mutedText,
                    )
                }
            }

            FormControl.Checkbox -> CheckboxAnswer(
                checked = (value as? FormValue.Bool)?.value == true,
                enabled = !locked,
                onChecked = { onValue(FormValue.Bool(it)) },
            )

            FormControl.Chips -> MultiAnswer(
                options = field.options,
                selected = (value as? FormValue.Multi)?.values.orEmpty(),
                enabled = !locked,
                onToggle = { optionValue ->
                    val current = (value as? FormValue.Multi)?.values.orEmpty()
                    val next = if (optionValue in current) {
                        current - optionValue
                    } else {
                        current + optionValue
                    }
                    onValue(FormValue.Multi(next))
                },
            )

            FormControl.External -> ExternalAnswer(url = field.externalUrl)

            FormControl.Unknown -> Text(
                text = "field not supported by this version",
                style = MaterialTheme.typography.labelSmall,
                color = oc.mutedText,
            )
        }

        if (problem != null) {
            Text(
                text = problem,
                style = MaterialTheme.typography.labelSmall,
                color = oc.error,
            )
        }
    }
}

/**
 * Titolo del campo + "obbligatorio": una sola stringa così TalkBack lo legge
 * in un colpo invece di due nodi separati.
 */
@Composable
private fun FieldLabel(field: FormFieldUi, mono: Boolean) {
    val oc = OpenColors.current
    val title = field.title?.takeIf { it.isNotBlank() }
    val text = buildAnnotatedString {
        append(title ?: field.key)
        if (field.required) {
            withStyle(SpanStyle(color = oc.mutedText)) { append(" · required") }
        }
    }
    Text(
        text = text,
        style = if (mono) {
            monoStyle(fontSize = 13.sp, lineHeight = 18.sp, weight = FontWeight.Medium)
        } else {
            MaterialTheme.typography.labelMedium
        },
        color = oc.text,
    )
}

/** Campo del composer riusato per testo e numerici: stessa pillola del prompt. */
@Composable
private fun AnswerTextField(
    text: String,
    enabled: Boolean,
    placeholder: String,
    keyboardType: KeyboardType,
    singleLine: Boolean,
    onText: (String) -> Unit,
    mono: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = oc.formField,
        shape = FIELD_SHAPE,
        border = BorderStroke(
            1.dp,
            if (enabled) oc.border else oc.border.copy(alpha = 0.5f),
        ),
    ) {
        BasicTextField(
            value = text,
            onValueChange = onText,
            enabled = enabled,
            textStyle = if (mono) {
                monoStyle(fontSize = 14.sp, lineHeight = 20.sp)
                    .copy(color = oc.text)
            } else {
                MaterialTheme.typography.bodyLarge.copy(color = oc.text)
            },
            cursorBrush = SolidColor(oc.interactive),
            maxLines = if (singleLine) 1 else 4,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
            ),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .heightIn(min = 22.dp),
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = if (mono) {
                                monoStyle(fontSize = 14.sp, lineHeight = 20.sp)
                            } else {
                                MaterialTheme.typography.bodyLarge
                            },
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

/** `boolean` è un interruttore: spento è una risposta valida, non un campo vuoto. */
@Composable
private fun CheckboxAnswer(
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val oc = OpenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(CHIP_SHAPE)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onChecked,
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) oc.interactive else Color.Transparent)
                .border(
                    width = 1.5.dp,
                    color = if (checked) oc.interactive else oc.border,
                    shape = RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) GlyphCheck(size = 13.dp, color = oc.onInteractive)
        }
        Text(
            text = if (checked) "yes" else "no",
            style = MaterialTheme.typography.labelLarge,
            color = if (checked) oc.text else oc.mutedText,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** `multiselect`: chip su più righe, ognuna un interruttore con area da 48 dp. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiAnswer(
    options: List<FormOptionUi>,
    selected: List<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
) = ChoiceChips(
    options = options,
    selected = selected,
    enabled = enabled,
    multi = true,
    onChange = onToggle,
)

/** Scelta singola (`string` con `options`): stessa forma dei chip, ruolo radio. */
@Composable
private fun ChoiceAnswer(
    options: List<FormOptionUi>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) = ChoiceChips(
    options = options,
    selected = listOfNotNull(selected),
    enabled = enabled,
    multi = false,
    onChange = onSelect,
)


/**
 * Chip di scelta, condivisi fra multiselect e scelta singola. Area 48 dp,
 * `Role.Checkbox` o `Role.RadioButton` a seconda del caso: TalkBack annuncia
 * il comportamento giusto.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(
    options: List<FormOptionUi>,
    selected: List<String>,
    enabled: Boolean,
    multi: Boolean,
    onChange: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current

    if (options.isEmpty()) {
        Text(
            text = "no options available",
            style = MaterialTheme.typography.labelSmall,
            color = oc.mutedText,
        )
        return
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isOn = option.value in selected
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(CHIP_SHAPE)
                    .background(
                        if (isOn) {
                            oc.interactive.copy(alpha = 0.14f)
                        } else {
                            scheme.surfaceContainerHigh
                        },
                    )
                    .border(
                        width = 1.dp,
                        color = if (isOn) {
                            oc.interactive.copy(alpha = 0.5f)
                        } else {
                            oc.border.copy(alpha = 0.7f)
                        },
                        shape = CHIP_SHAPE,
                    )
                    .toggleable(
                        value = isOn,
                        enabled = enabled,
                        role = if (multi) Role.Checkbox else Role.RadioButton,
                        onValueChange = { onChange(option.value) },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isOn) oc.interactive else oc.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


/** `external`: si compila sul server, da qui si può solo leggere il link. */
@Composable
private fun ExternalAnswer(url: String?) {
    val oc = OpenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides oc.mutedText) {
            GlyphArrowRight(size = 14.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "fill in on the server",
                style = MaterialTheme.typography.labelSmall,
                color = oc.mutedText,
            )
            url?.takeIf { it.isNotBlank() }?.let { link ->
                Text(
                    text = link,
                    style = monoStyle(fontSize = 11.sp, lineHeight = 15.sp),
                    color = oc.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FormErrorNote(
    message: String,
    onRefresh: () -> Unit,
) {
    val oc = OpenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(oc.error.copy(alpha = 0.08f), RoundedCornerShape(9.dp))
            .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(oc.error, size = 5.dp)
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = oc.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onRefresh,
            modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 9.dp),
        ) {
            Text("refresh", style = MaterialTheme.typography.labelMedium, color = oc.interactive)
        }
    }
}

@Composable
private fun FormSpinner(color: Color) {
    CircularProgressIndicator(
        modifier = Modifier.size(15.dp),
        strokeWidth = 2.dp,
        color = color,
    )
}

// ---------------------------------------------------------------------------
// Palette dei comandi
// ---------------------------------------------------------------------------

/**
 * Testo dopo "/" quando il draft è un comando in scrittura: `null` se non lo è.
 * Basta un carattere di spazio per uscire dalla palette (il comando è scelto e
 * si stanno scrivendo gli argomenti).
 */
internal fun commandQueryOf(draft: String): String? {
    if (!draft.startsWith("/")) return null
    val query = draft.drop(1)
    if (query.any { it.isWhitespace() }) return null
    return query
}

/**
 * Foglio dei comandi del server: righe da 56 dp, nome in mono, descrizione
 * `mutedText`. Il filtro è sul testo dopo "/", senza distinzione di maiuscole.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommandPalette(
    commands: List<CommandUi>,
    loading: Boolean,
    error: String?,
    query: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val filtered = remember(commands, query) {
        if (query.isBlank()) {
            commands
        } else {
            commands.filter { it.name.contains(query, ignoreCase = true) }
        }
    }
    val listMax = fieldsMaxHeightDp(LocalConfiguration.current.screenHeightDp).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Con la tastiera aperta il foglio deve restare leggibile:
                // il draft resta il filtro, quindi si continua a scrivere.
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Eyebrow(
                    text = "commands",
                    color = oc.mutedText,
                    modifier = Modifier.weight(1f),
                )
                if (loading) {
                    WorkingIndicator(
                        size = 12.dp,
                        color = oc.mutedText,
                        description = "loading commands",
                    )
                    Spacer(Modifier.width(10.dp))
                }
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
                        text = "cancel",
                        style = MaterialTheme.typography.labelSmall,
                        color = oc.mutedText,
                    )
                }
            }

            when {
                filtered.isEmpty() && loading -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clearAndSetSemantics { contentDescription = "loading commands…" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WorkingIndicator(
                        size = 12.dp,
                        color = oc.success,
                        description = "waiting",
                    )
                    Text(
                        text = "loading commands…",
                        style = MaterialTheme.typography.labelMedium,
                        color = oc.mutedText,
                    )
                }

                filtered.isEmpty() -> Text(
                    text = when {
                        // Un guasto di rete non deve sembrare "nessun comando":
                        // il catalogo è vuoto perché la lettura non è riuscita.
                        error != null -> error
                        query.isBlank() -> "no commands"
                        else -> "no commands for \"$query\""
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = oc.mutedText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 14.dp),
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = listMax),
                ) {
                    items(items = filtered, key = { it.name }) { command ->
                        CommandRow(command = command, onClick = { onPick(command.name) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandRow(
    command: CommandUi,
    onClick: () -> Unit,
) {
    val oc = OpenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = "use command ${command.name}",
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "/${command.name}",
            style = monoStyle(fontSize = 13.sp, lineHeight = 18.sp, weight = FontWeight.Medium),
            color = oc.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 88.dp),
        )
        command.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = oc.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Regole di compilazione (pure, così restano leggibili e verificabili)
// ---------------------------------------------------------------------------

/**
 * Il campo obbligatorio ha una risposta? `boolean` e `external` non bloccano
 * mai: un interruttore spento è una risposta, e un campo esterno si compila sul
 * server — bloccare qui lascerebbe il pannello impossibile da chiudere.
 */
internal fun hasAnswer(field: FormFieldUi, value: FormValue?): Boolean =
    when (controlOf(field)) {
        FormControl.Text -> ((value as? FormValue.Text)?.value.isNullOrBlank()) != true
        FormControl.Choice -> ((value as? FormValue.Text)?.value.isNullOrBlank()) != true
        FormControl.Chips ->
            field.options.isEmpty() ||
                ((value as? FormValue.Multi)?.values.isNullOrEmpty()) != true
        FormControl.Integer, FormControl.Number -> value is FormValue.Number
        FormControl.Checkbox, FormControl.External, FormControl.Unknown -> true
    }

/**
 * Errore breve sotto il campo, solo per i numerici: testo non interpretabile o
 * fuori dall'intervallo `minimum`/`maximum`. Vuoto (o solo "-") non è un
 * errore, è un campo ancora da compilare.
 */
internal fun valueProblem(field: FormFieldUi, value: FormValue?, raw: String?): String? {
    val control = controlOf(field)
    if (control != FormControl.Integer && control != FormControl.Number) return null
    val text = raw.orEmpty().trim()
    if (text.isEmpty() || text == "-") return null
    val number = (value as? FormValue.Number)?.value ?: return "invalid number"
    field.minimum?.let { min ->
        if (number < min) return "minimum ${numberLabel(min)}"
    }
    field.maximum?.let { max ->
        if (number > max) return "maximum ${numberLabel(max)}"
    }
    return null
}

/** Un valore da inviare, oppure `null` se è vuoto (niente chiavi inutili). */
internal fun replyValue(value: FormValue): FormValue? = when (value) {
    is FormValue.Text -> value.takeIf { it.value.isNotBlank() }
    is FormValue.Multi -> value.takeIf { it.values.isNotEmpty() }
    else -> value
}

/** Il numero si invia solo se lo si è capito: "" e "-" non sono valori. */
internal fun numericValueOf(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }

/**
 * Filtro sull'input numerico: cifre, un segno meno iniziale, al massimo un
 * separatore decimale. Impedisce stati che poi non si possono inviare (per
 * `integer` il separatore non è ammesso).
 */
internal fun sanitizeNumberInput(text: String, integer: Boolean): Boolean {
    if (text.isEmpty()) return true
    val body = text.removePrefix("-")
    if (body.isEmpty()) return true
    if (integer) return body.all { it.isDigit() }
    val separators = body.count { it == '.' || it == ',' }
    return separators <= 1 && body.all { it.isDigit() || it == '.' || it == ',' }
}

/** "from 1 to 10", "maximum 4" — solo se il server ha dichiarato i limiti. */
internal fun rangeHint(field: FormFieldUi): String? {
    val min = field.minimum
    val max = field.maximum
    return when {
        min != null && max != null -> "from ${numberLabel(min)} to ${numberLabel(max)}"
        min != null -> "minimum ${numberLabel(min)}"
        max != null -> "maximum ${numberLabel(max)}"
        else -> null
    }
}

private fun numberLabel(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/**
 * Un 404 dal server significa che la domanda non è più in attesa: il pannello
 * sparisce al resync, intanto si dice cosa è successo senza mostrare il codice.
 */
internal fun formErrorLabel(message: String): String {
    val expired = listOf("404", "scadut", "non trovat", "not found", "expired")
        .any { message.contains(it, ignoreCase = true) }
    return if (expired) "question expired" else message
}
