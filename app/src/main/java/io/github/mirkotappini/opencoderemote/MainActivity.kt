package io.github.mirkotappini.opencoderemote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleApprovalIntent(intent)
        enableEdgeToEdge()
        setContent {
            androidx.compose.material3.MaterialTheme {
                MainScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleApprovalIntent(intent)
    }

    /** Notification tap / "Always" action: opens the indicated session. */
    private fun handleApprovalIntent(intent: Intent?) {
        val sessionId = intent?.getStringExtra(ApprovalNotifier.EXTRA_SESSION_ID) ?: return
        ApprovalIntentRouter.request(sessionId)
        intent.removeExtra(ApprovalNotifier.EXTRA_SESSION_ID)
        intent.removeExtra(ApprovalNotifier.EXTRA_REQUEST_ID)
        intent.removeExtra(ApprovalNotifier.EXTRA_APPROVAL_ACTION)
    }
}
