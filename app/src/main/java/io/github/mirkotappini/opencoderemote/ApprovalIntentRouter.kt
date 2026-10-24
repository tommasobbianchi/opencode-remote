package io.github.mirkotappini.opencoderemote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ponte tra il tap sulla notifica (Activity) e il ViewModel.
 *
 * Non contiene stato UI: solo l'id di sessione da aprire. Il ViewModel lo
 * consuma appena la connessione è pronta, anche se l'app era chiusa.
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
