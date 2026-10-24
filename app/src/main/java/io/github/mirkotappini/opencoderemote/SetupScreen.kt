package io.github.mirkotappini.opencoderemote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mirkotappini.opencoderemote.net.ServerVersion

@Composable
fun SetupScreen(
    state: UiState,
    onUrlChange: (String) -> Unit,
    onUserChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    val connecting = state.connection is Connection.Connecting
    val error = (state.connection as? Connection.Error)?.message ?: state.error
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        TechnicalBackdrop(Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = Layout.setupMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Eyebrow("OpenCode Remote")
                    Spacer(Modifier.weight(1f))
                    ServerNamePill(serverName(state.url))
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "Le tue sessioni,\nqui.",
                    style = MaterialTheme.typography.displaySmall,
                    color = scheme.onBackground,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Sessioni e conversazioni dal tuo server OpenCode (2.x). " +
                        "Serve il servizio esposto in HTTPS — per esempio con Tailscale serve — " +
                        "e la rete privata Tailscale attiva su questo telefono.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(28.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = scheme.surfaceContainer,
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, oc.border),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Eyebrow("Accesso al server")
                            Spacer(Modifier.weight(1f))
                            StatusDot(
                                color = when {
                                    connecting -> oc.warningInk
                                    error != null -> oc.error
                                    else -> scheme.outline
                                },
                                size = 5.dp,
                                pulsing = connecting,
                            )
                        }

                        OutlinedTextField(
                            value = state.url,
                            onValueChange = onUrlChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !connecting,
                            singleLine = true,
                            label = { Text("Indirizzo del server") },
                            placeholder = { Text("https://macchina.tailxxxx.ts.net") },
                            supportingText = { Text("Solo HTTPS · rete privata Tailscale") },
                            shape = MaterialTheme.shapes.small,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next,
                            ),
                            colors = fieldColors(),
                        )

                        OutlinedTextField(
                            value = state.user,
                            onValueChange = onUserChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !connecting,
                            singleLine = true,
                            label = { Text("Utente") },
                            supportingText = { Text("Utente del servizio OpenCode (di solito opencode)") },
                            shape = MaterialTheme.shapes.small,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            colors = fieldColors(),
                        )

                        OutlinedTextField(
                            value = state.password,
                            onValueChange = onPasswordChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !connecting,
                            singleLine = true,
                            label = { Text("Password o token") },
                            shape = MaterialTheme.shapes.small,
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { passwordVisible = !passwordVisible },
                                    modifier = Modifier.semantics {
                                        contentDescription = if (passwordVisible) {
                                            "Nascondi password"
                                        } else {
                                            "Mostra password"
                                        }
                                    },
                                ) {
                                    GlyphEye(visible = passwordVisible, size = 20.dp)
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    if (!connecting) onConnect()
                                },
                            ),
                            colors = fieldColors(),
                        )

                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                onConnect()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp),
                            enabled = !connecting,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            if (connecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = scheme.onPrimary,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Connessione…",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            } else {
                                Text(
                                    text = "Connetti",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Spacer(Modifier.width(8.dp))
                                GlyphArrowRight(size = 17.dp)
                            }
                        }
                    }
                }

                if (error != null) {
                    Spacer(Modifier.height(14.dp))
                    ErrorBanner(
                        title = "Connessione non riuscita",
                        message = error,
                        onAction = onConnect,
                    )
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphLock(
                        size = 14.dp,
                        modifier = Modifier.alpha(0.65f),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "Credenziali cifrate sul dispositivo con il Keystore. " +
                            "«Disconnetti» le cancella.",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "versione " + BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.labelSmall,
                    color = oc.mutedText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/**
 * M5: il server risponde ma non è la 2.x. Schermata bloccante volutamente
 * scarna: solo "Riprova" (nuovo /api/info) e "Disconnetti". Riusa i componenti
 * del setup, nessuna grafica nuova.
 */
@Composable
fun VersionMismatchScreen(
    state: UiState,
    onRetry: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val found = (state.connection as? Connection.Incompatible)?.found ?: "?"
    val scheme = MaterialTheme.colorScheme
    val oc = OpenColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        TechnicalBackdrop(Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = Layout.setupMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Eyebrow("OpenCode Remote")
                    Spacer(Modifier.weight(1f))
                    ServerNamePill(serverName(state.url))
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "Versione non\nsupportata.",
                    style = MaterialTheme.typography.displaySmall,
                    color = scheme.onBackground,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Questa build parla con OpenCode 2.x. " +
                        "Aggiorna il server oppure installa una build compatibile.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(28.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = scheme.surfaceContainer,
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, oc.error.copy(alpha = 0.55f)),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Eyebrow("Server trovato", color = oc.error)
                        Text(
                            text = ServerVersion.unsupportedMessage(found),
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onSurface,
                        )
                        Text(
                            text = "Nessuna richiesta viene inviata al server finché non " +
                                "riprovi o ti disconnetti.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text("Riprova", style = MaterialTheme.typography.titleSmall)
                }

                Spacer(Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text("Disconnetti", style = MaterialTheme.typography.titleSmall)
                }

                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun fieldColors(): TextFieldColors {
    val oc = OpenColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = oc.interactive,
        unfocusedBorderColor = oc.border,
        focusedLabelColor = oc.interactive,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        focusedContainerColor = oc.formField,
        unfocusedContainerColor = oc.formField,
        cursorColor = oc.interactive,
        focusedSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unfocusedSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ServerNamePill(name: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(OpenColors.current.accent, size = 5.dp)
            Text(
                text = name,
                style = monoStyle(
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    weight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
    }
}
