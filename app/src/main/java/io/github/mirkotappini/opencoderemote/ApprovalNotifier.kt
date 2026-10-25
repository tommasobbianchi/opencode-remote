package io.github.mirkotappini.opencoderemote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.mirkotappini.opencoderemote.net.ApprovalIds
import io.github.mirkotappini.opencoderemote.net.ApprovalNotificationTracker
import io.github.mirkotappini.opencoderemote.net.ApprovalUi

/**
 * Building and deduplicating approval notifications.
 *
 * The tracker is shared at the process level: even the receiver, which lives in a
 * different notifier instance, sees the same state. One notification per
 * `requestID`, `setOnlyAlertOnce`, cancellation on resolution.
 */
class ApprovalNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /** RequestIDs already notified per session (to cancel those that disappeared). */
    private val perSession = HashMap<String, Set<String>>()

    fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        val approvals = NotificationChannel(
            CHANNEL_APPROVALS,
            "Approvals",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Permission requests waiting on the OpenCode server"
            enableVibration(true)
        }
        val service = NotificationChannel(
            CHANNEL_SERVICE,
            "Background connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the connection to receive permission requests"
            setShowBadge(false)
        }
        system.createNotificationChannels(listOf(approvals, service))
    }

    /** Persistent notification for the foreground service. */
    fun serviceNotification(): Notification {
        ensureChannels()
        val content = appIntent(null, null)
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("OC Remote")
            .setContentText("Listening for approval requests")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(content)
            .build()
    }

    fun notify(request: ApprovalUi) {
        ensureChannels()
        if (!manager.areNotificationsEnabled()) return
        if (!tracker.markShown(request.id)) return

        val summary = buildSummary(request)
        val builder = NotificationCompat.Builder(context, CHANNEL_APPROVALS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Approval requested · OpenCode")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setWhen(request.createdAt ?: System.currentTimeMillis())
            .setContentIntent(appIntent(request.sessionId, request.id))
            .addAction(0, "Allow", replyIntent(request, "once"))
            .addAction(0, "Reject", replyIntent(request, "reject"))
            // "Always" has a permanent effect: never from a notification, opens the
            // app.
            .addAction(0, "Always…", appIntent(request.sessionId, request.id, "always"))

        manager.notify(ApprovalIds.notificationId(request.id), builder.build())
    }

    fun cancel(requestId: String) {
        cancelFor(context, requestId)
    }

    /** Align session notifications with the server's authoritative list. */
    fun sync(sessionId: String, requests: List<ApprovalUi>) {
        val ids = requests.mapTo(LinkedHashSet()) { it.id }
        val previous = perSession.put(sessionId, ids) ?: emptySet()
        (previous - ids).forEach { cancelFor(context, it) }
        requests.forEach(::notify)
    }

    private fun buildSummary(request: ApprovalUi): String {
        val target = buildString {
            append(request.action)
            request.resources.firstOrNull()?.let {
                append(' ')
                append(it)
            }
            val more = request.resources.size - 1
            if (more > 0) append(" (+$more)")
        }
        val message = request.message?.let { "\n$it" }.orEmpty()
        val save = if (request.save.isNotEmpty()) {
            "\nAlways would save: ${request.save.joinToString(" ")}"
        } else {
            ""
        }
        return "$target$message$save"
    }

    private fun replyIntent(
        request: ApprovalUi,
        decision: String,
    ): PendingIntent {
        val intent = Intent(context, ApprovalActionReceiver::class.java).apply {
            action = ApprovalActionReceiver.ACTION_REPLY
            putExtra(ApprovalActionReceiver.EXTRA_SESSION_ID, request.sessionId)
            putExtra(ApprovalActionReceiver.EXTRA_REQUEST_ID, request.id)
            putExtra(ApprovalActionReceiver.EXTRA_DECISION, decision)
        }
        return PendingIntent.getBroadcast(
            context,
            ApprovalIds.actionRequestCode(request.id, decision),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Tap and "Always" action: open the app (explicit confirmation is in-app). */
    private fun appIntent(
        sessionId: String?,
        requestId: String?,
        action: String? = null,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
            requestId?.let { putExtra(EXTRA_REQUEST_ID, it) }
            action?.let { putExtra(EXTRA_APPROVAL_ACTION, it) }
        }
        val requestCode = if (requestId != null) {
            ApprovalIds.actionRequestCode(requestId, action ?: "open")
        } else {
            SERVICE_CONTENT_REQUEST
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_APPROVALS = "approvals"
        const val CHANNEL_SERVICE = "approval_service"

        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_APPROVAL_ACTION = "approval_action"

        private const val SERVICE_CONTENT_REQUEST = 10

        private val tracker = ApprovalNotificationTracker()

        /** Also used by the ViewModel/receiver outside the service. */
        fun cancelFor(context: Context, requestId: String) {
            tracker.markResolved(requestId)
            NotificationManagerCompat.from(context)
                .cancel(ApprovalIds.notificationId(requestId))
        }

        /**
         * M5: cancel all pending approvals (401, disconnection, service stop).
         * Notifications are rebuilt on the next reconnection.
         */
        fun cancelAll(context: Context) {
            // cancelAll() also covers notifications left over from a previous
            // process that the in-memory tracker no longer knows about.
            NotificationManagerCompat.from(context).cancelAll()
            tracker.clear()
        }
    }
}
