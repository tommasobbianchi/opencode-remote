package io.github.mirkotappini.opencoderemote

import android.content.Context
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Testo e azione dell'avviso non bloccante per le notifiche. */
data class NotificationPromptUi(
    val message: String,
    val actionLabel: String,
    val opensSettings: Boolean = false,
)

/**
 * Il permesso `POST_NOTIFICATIONS` viene chiesto una volta sola, al primo
 * `connect()` riuscito. L'esito (concesso o negato) resta su preferenze private:
 * agli avvii successivi l'app non riapre il dialogo di sistema.
 */
internal object NotificationOptInStore {

    private const val PREFS = "notification_opt_in"
    private const val KEY_ASKED = "asked_v1"

    fun shouldAsk(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (NotificationPermission.isGranted(context)) return false
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED, false)
    }

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ASKED, true)
            .apply()
    }
}

/**
 * Riga informativa, non modale: spiega a cosa serve il permesso e lascia
 * l'app pienamente usabile anche se viene negato.
 */
@Composable
fun NotificationNoticeBar(
    prompt: NotificationPromptUi,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val oc = OpenColors.current
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, oc.info.copy(alpha = 0.4f)),
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(oc.info, size = 5.dp)
                Text(
                    text = prompt.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 9.dp),
                ) {
                    Text(
                        text = prompt.actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = oc.interactive,
                        maxLines = 1,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Chiudi avviso notifiche" },
                ) {
                    GlyphClose(size = 15.dp)
                }
            }
        }
    }
}
