package io.github.mirkotappini.opencoderemote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mirkotappini.opencoderemote.net.OpenCodeApi
import io.github.mirkotappini.opencoderemote.security.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * "Once" and "Reject" actions directly from the notification.
 *
 * It answers the API immediately with the encrypted credentials and cancels
 * the notification. "Always" does not go through here: it opens the app for
 * the explicit confirmation.
 */
class ApprovalActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val decision = intent.getStringExtra(EXTRA_DECISION) ?: return
        if (decision != "once" && decision != "reject") return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val credentials = TokenStore(appContext).load()
                if (credentials != null) {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
                        .writeTimeout(20, TimeUnit.SECONDS)
                        .build()
                    val api = OpenCodeApi(
                        client,
                        credentials.baseUrl,
                        credentials.user,
                        credentials.password,
                    )
                    api.replyPermission(sessionId, requestId, decision)
                }
            } catch (_: Exception) {
                // Expired request or no network: the notification is dismissed anyway.
            } finally {
                ApprovalNotifier.cancelFor(appContext, requestId)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "io.github.mirkotappini.opencoderemote.action.APPROVAL_REPLY"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_DECISION = "decision"
    }
}
