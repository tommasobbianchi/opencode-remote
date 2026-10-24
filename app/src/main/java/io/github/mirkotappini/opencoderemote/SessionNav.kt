package io.github.mirkotappini.opencoderemote

import io.github.mirkotappini.opencoderemote.net.SessionUi

/**
 * Un livello della pila di navigazione: la sessione che era aperta prima di
 * entrare in un task figlio, con la modalità con cui era stata aperta.
 *
 * È logica pura (nessuna dipendenza da Android) così la pila si può testare
 * offline. Il ViewModel la usa per `openSession`/`back`; la UI legge solo
 * `UiState.canGoBackToParent`.
 */
data class SessionFrame(
    val session: SessionUi,
    val readOnly: Boolean = false,
)

/** Esito di un'apertura: pila da salvare e modalità della sessione richiesta. */
data class SessionOpenPlan(
    val stack: List<SessionFrame>,
    val readOnly: Boolean,
)

/** Esito di un `back()`: frame da riaprire (null = lista) e pila rimasta. */
data class SessionPopPlan(
    val frame: SessionFrame?,
    val stack: List<SessionFrame>,
)

/**
 * Regole della pila padre → figlio:
 *  - apertura dalla lista (nessuna sessione aperta): pila vuota, scrivibile;
 *  - apertura con una sessione già aperta: la corrente viene impilata e la
 *    nuova sessione è figlia, quindi in sola lettura;
 *  - stesso id della sessione aperta (retry, refresh, notifica): nessun
 *    duplicato in pila;
 *  - id già presente più in basso nella pila: si torna a quel livello senza
 *    creare cicli (A → B → A non lascia B impilato sopra A);
 *  - `back`: riapre l'ultimo frame impilato; pila vuota = lista.
 */
object SessionNav {

    fun planOpen(
        stack: List<SessionFrame>,
        current: SessionFrame?,
        nextSession: SessionUi,
    ): SessionOpenPlan {
        // Dalla lista: la pila riparte da zero, la sessione è scrivibile.
        if (current == null) return SessionOpenPlan(emptyList(), readOnly = false)

        // Retry/refresh della stessa sessione: pila e modalità non cambiano.
        if (current.session.id == nextSession.id) {
            return SessionOpenPlan(stack, readOnly = current.readOnly)
        }

        // Salto a un antenato: si chiudono i livelli sopra di lui.
        val ancestor = stack.indexOfLast { it.session.id == nextSession.id }
        if (ancestor >= 0) {
            return SessionOpenPlan(
                stack = stack.take(ancestor),
                readOnly = stack[ancestor].readOnly,
            )
        }

        // Nuovo figlio: la sessione corrente resta in pila, lui è sola lettura.
        return SessionOpenPlan(stack + current, readOnly = true)
    }

    fun pop(stack: List<SessionFrame>): SessionPopPlan =
        if (stack.isEmpty()) {
            SessionPopPlan(frame = null, stack = emptyList())
        } else {
            SessionPopPlan(frame = stack.last(), stack = stack.dropLast(1))
        }

    /** `backToList()` e `disconnect()` azzerano sempre la pila. */
    fun cleared(): List<SessionFrame> = emptyList()

    fun canGoBackToParent(stack: List<SessionFrame>): Boolean = stack.isNotEmpty()
}
