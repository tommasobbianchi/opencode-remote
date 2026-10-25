package io.github.mirkotappini.opencoderemote.net

import kotlinx.serialization.Serializable

/**
 * Attachment for a prompt, in the form expected by
 * `POST /api/session/{id}/prompt`.
 *
 * `uri` is required and must be a data URI (`data:<mime>;base64,…`); `name` and
 * `description` are optional. The content remains in memory and is never logged.
 */
@Serializable
data class PromptFile(
    val uri: String,
    val name: String? = null,
    val description: String? = null,
)
