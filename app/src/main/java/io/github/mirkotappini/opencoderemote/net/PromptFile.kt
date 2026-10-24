package io.github.mirkotappini.opencoderemote.net

import kotlinx.serialization.Serializable

/**
 * Allegato di un prompt, nella forma attesa da `POST /api/session/{id}/prompt`.
 *
 * `uri` è obbligatorio e vale un data URI (`data:<mime>;base64,…`); `name` e
 * `description` sono facoltativi. Il contenuto resta in memoria e non viene
 * mai loggato.
 */
@Serializable
data class PromptFile(
    val uri: String,
    val name: String? = null,
    val description: String? = null,
)
