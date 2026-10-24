package io.github.mirkotappini.opencoderemote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridge between the notification tap (Activity) and the ViewModel.
 *
 * It holds no UI state: only the id of the session to open. The ViewModel
 * consumes it as soon as the connection is ready, even if the app was closed.
 */
object ApprovalIntentRouter {

    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    fun request(sessionId: String?) {
        if (!sessionId.isNullOrBlank()) _sessionId.value = sessionId
    }

    fun consume() {
        _sessionId.value = null
    }
}
