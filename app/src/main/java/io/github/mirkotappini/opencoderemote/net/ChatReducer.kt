package io.github.mirkotappini.opencoderemote.net

import kotlin.math.abs

/**
 * Legacy text model kept only for compatibility with the current rendering
 * (ChatScreen). The source of truth for the turn is [ChatStream.liveTurn].
 */
data class LivePart(
    val ordinal: Int,
    val text: String = "",
    val ended: Boolean = false,
)

@Deprecated("Chat uses AssistantTurnUi. LiveMessage stays for ChatScreen until the UI lane.")
data class LiveMessage(
    val messageId: String,
    val parts: List<LivePart> = emptyList(),
) {
    /** Concatenated text in ascending ordinal order (API-M2 contract §2). */
    val text: String
        get() = parts.sortedBy { it.ordinal }
            .joinToString("\n") { it.text }
            .trimEnd()

    val complete: Boolean
        get() = parts.isNotEmpty() && parts.all { it.ended }
}

/**
 * Live turn state.
 *
 * - [liveTurn]: at most one turn per session, identified by `assistantMessageID`. -
 * [closedParts]: text parts closed by `ended` (message + ordinal pair). -
 * [closedReasoningParts]: reasoning parts closed by `ended` (same pair, separate
 * set). - [finishedMessages]: messages closed by
 * `step.ended`/`step.failed`/execution end. - [terminalToolCalls]: already closed
 * tool outcomes; blocks late input/called. Duplicate success/failed on Ok/Failed
 * are ignored by comparing the phase. - [toolInputBuffers]: partial JSON of tool
 * inputs, never rendered. - [materialized]: turns already moved to `messages`
 * (multi-step).
 *
 * `ChatStream.live` is a deprecated derivative for the old textual renderer.
 */
data class ChatStream(
    val liveTurn: AssistantTurnUi? = null,
    val closedParts: Set<Pair<String, Int>> = emptySet(),
    val closedReasoningParts: Set<Pair<String, Int>> = emptySet(),
    val finishedMessages: Set<String> = emptySet(),
    val terminalToolCalls: Set<String> = emptySet(),
    val toolInputBuffers: Map<String, String> = emptyMap(),
    val materialized: Set<String> = emptySet(),
) {
    @Deprecated(
        message = "Usa liveTurn: AssistantTurnUi.",
        replaceWith = ReplaceWith("liveTurn"),
    )
    val live: LiveMessage?
        get() = liveTurn?.toLiveMessage()
}

data class OpenChatState(
    val sessionId: String,
    val messages: List<MessageUi> = emptyList(),
    val stream: ChatStream = ChatStream(),
    val running: Boolean = false,
    val outcome: String? = null,
)

/**
 * Pure SSE event reduction for the open chat. An event from another session does
 * not modify state (M2, criterion 5).
 *
 * Performance note (§4.4): deltas only accumulate here; throttled publication
 * (80–120 ms, immediate terminal events) is handled by AppViewModel via
 * [PublishGate] and [publishedImmediately].
 */
object ChatReducer {

    fun reduce(state: OpenChatState, event: ServerEvent): OpenChatState {
        val sessionId = event.sessionId ?: return state
        if (sessionId != state.sessionId) return state

        return when (event) {
            is ServerEvent.TextStarted -> onTextStarted(state, event)
            is ServerEvent.TextDelta -> onTextDelta(state, event)
            is ServerEvent.TextEnded -> onTextEnded(state, event)
            is ServerEvent.ReasoningStarted -> onReasoningStarted(state, event)
            is ServerEvent.ReasoningDelta -> onReasoningDelta(state, event)
            is ServerEvent.ReasoningEnded -> onReasoningEnded(state, event)
            is ServerEvent.ToolInputStarted -> onToolInputStarted(state, event)
            is ServerEvent.ToolInputDelta -> onToolInputDelta(state, event)
            is ServerEvent.ToolInputEnded -> onToolInputEnded(state, event)
            is ServerEvent.ToolCalled -> onToolCalled(state, event)
            is ServerEvent.ToolProgress -> onToolProgress(state, event)
            is ServerEvent.ToolSuccess -> onToolFinished(state, event, failed = false)
            is ServerEvent.ToolFailed -> onToolFinished(state, event, failed = true)
            is ServerEvent.StepStarted -> onStepStarted(state, event)
            is ServerEvent.StepEnded -> onStepEnded(state, event)
            is ServerEvent.StepFailed -> onStepFailed(state, event)
            is ServerEvent.ExecutionStarted -> state.copy(
                running = true,
                outcome = null,
                messages = state.messages.map {
                    if (it.pending) it.copy(pending = false) else it
                },
            )
            is ServerEvent.ExecutionFinished -> onExecutionFinished(state, event)
            is ServerEvent.InboxDelivered -> state.copy(
                messages = state.messages.map {
                    if (it.pending) it.copy(pending = false) else it
                },
            )
            else -> state
        }
    }

    // --- text ---------------------------------------------------------------

    private fun onTextStarted(state: OpenChatState, event: ServerEvent.TextStarted): OpenChatState {
        val base = ensureTurn(state, event.messageId)
        val stream = base.stream
        if (event.messageId in stream.finishedMessages) return base
        if (stream.closedParts.contains(event.messageId to event.ordinal)) return base

        val key = "text:${event.ordinal}"
        val updated = stream.mutatePart(
            messageId = event.messageId,
            key = key,
            create = { order -> TextPartUi(key, order, PartPhase.Streaming, "") },
            transform = { null },
        )
        return base.copy(stream = updated)
    }

    private fun onTextDelta(state: OpenChatState, event: ServerEvent.TextDelta): OpenChatState {
        val base = ensureTurn(state, event.messageId)
        val stream = base.stream
        if (event.messageId in stream.finishedMessages) return base
        if (stream.closedParts.contains(event.messageId to event.ordinal)) return base

        val key = "text:${event.ordinal}"
        val updated = stream.mutatePart(
            messageId = event.messageId,
            key = key,
            create = { order -> TextPartUi(key, order, PartPhase.Streaming, event.delta) },
            transform = { part ->
                if (part !is TextPartUi || part.phase != PartPhase.Streaming) {
                    null
                } else {
                    part.copy(text = part.text + event.delta)
                }
            },
        )
        return base.copy(stream = updated)
    }

    private fun onTextEnded(state: OpenChatState, event: ServerEvent.TextEnded): OpenChatState {
        val base = ensureTurn(state, event.messageId)
        val stream = base.stream
        if (event.messageId in stream.finishedMessages) return base

        val key = "text:${event.ordinal}"
        val updated = stream.mutatePart(
            messageId = event.messageId,
            key = key,
            create = { order -> TextPartUi(key, order, PartPhase.Done, event.text) },
            transform = { part ->
                if (part !is TextPartUi) {
                    null
                } else if (part.phase == PartPhase.Done && part.text == event.text) {
                    null
                } else {
                    // The server wins on the accumulated deltas.
                    part.copy(text = event.text, phase = PartPhase.Done)
                }
            },
        )
        return base.copy(
            stream = updated.copy(
                closedParts = updated.closedParts + (event.messageId to event.ordinal),
            ),
        )
    }

    // --- reasoning ----------------------------------------------------------

    private fun onReasoningStarted(
        state: OpenChatState,
        event: ServerEvent.ReasoningStarted,
    ): OpenChatState {
        val base = ensureTurn(state, event.messageId)
        val stream = base.stream
        if (event.messageId in stream.finishedMessages) return base
        if (stream.closedReasoningParts.contains(event.messageId to event.ordinal)) return base

        val key = "reasoning:${event.ordinal}"
        val updated = stream.mutatePart(
            messageId = event.messageId,
            key = key,
            create = { order ->
                ReasoningPartUi(
                    key = key,
                    order = order,
                    phase = PartPhase.Streaming,
                    text = "",
                    startedAt = nowMs(),
                )
            },
            transform = { null },
        )
        return base.copy(stream = updated)
    }

    private fun onReasoningDelta(
        state: OpenChatState,
        event: ServerEvent.ReasoningDelta,
    ): OpenChatState {
        val base = ensureTurn(state, event.messageId)
        val stream = base.stream
        if (event.messageId in stream.finishedMessages) return base
        if (stream.closedReasoningParts.contains(event.messageId to event.ordinal)) return base

        val key = "reasoning:${event.ordinal}"
        val updated = stream.mutatePart(
            messageId = event.messageId,
            key = key,
            create = { order ->
                ReasoningPartUi(
                    key = key,
                    order = order,
                    phase = PartPhase.Streaming,
                    text = event.delta,
                    startedAt = nowMs(),
                )
            },
            transform = { part ->
                if (part !is ReasoningPartUi || part.phase != PartPhase.Streaming) {
                    null
                } else {
                    part.copy(text = part.text + event.delta)
                }
            },
        )
        return base.copy(stream = updated)
    }

    private fun onReasoningEnded(
        state: OpenChatState,
        event: ServerEvent.ReasoningEnded,
    ): OpenChatState {
        val base = ensureTurn(state, event.messageId)
        val stream = base.stream
        if (event.messageId in stream.finishedMessages) return base

        val key = "reasoning:${event.ordinal}"
        val updated = stream.mutatePart(
            messageId = event.messageId,
            key = key,
            create = { order ->
                ReasoningPartUi(
                    key = key,
                    order = order,
                    phase = PartPhase.Done,
                    text = event.text,
                    startedAt = nowMs(),
                    endedAt = nowMs(),
                )
            },
            transform = { part ->
                if (part !is ReasoningPartUi) {
                    null
                } else if (part.phase == PartPhase.Done && part.text == event.text) {
                    null
                } else {
                    part.copy(
                        text = event.text,
                        phase = PartPhase.Done,
                        endedAt = nowMs(),
                    )
                }
            },
        )
        return base.copy(
            stream = updated.copy(
                closedReasoningParts = updated.closedReasoningParts +
                    (event.messageId to event.ordinal),
            ),
        )
    }

    // --- tool input ---------------------------------------------------------

    private fun onToolInputStarted(
        state: OpenChatState,
        event: ServerEvent.ToolInputStarted,
    ): OpenChatState {
        val base = ensureTurn(state, event.messageId)
        val stream = base.stream
        if (event.messageId in stream.finishedMessages) return base

        val key = toolKey(event.callId)
        val updated = stream.mutatePart(
            messageId = event.messageId,
            key = key,
            create = { order ->
                ToolPartUi(
                    key = key,
                    order = order,
                    phase = PartPhase.Streaming,
                    callId = event.callId,
                    name = event.name.takeIf { it.isNotBlank() } ?: "tool",
                )
            },
            transform = { null },
        )
        return base.copy(stream = updated)
    }

    private fun onToolInputDelta(
        state: OpenChatState,
        event: ServerEvent.ToolInputDelta,
    ): OpenChatState {
        val base = ensureTurn(state, event.messageId)
        val stream = base.stream
        val key = toolKey(event.callId)
        if (event.messageId in stream.finishedMessages) return base
        if (terminalKey(event.messageId, event.callId) in stream.terminalToolCalls) return base

        val buffer = (stream.toolInputBuffers[event.callId].orEmpty() + event.delta)
        val command = ToolInputParsing.commandFromPartial(buffer)
        val updated = stream
            .copy(toolInputBuffers = stream.toolInputBuffers + (event.callId to buffer))
            .mutatePart(
                messageId = event.messageId,
                key = key,
                create = { order ->
                    ToolPartUi(
                        key = key,
                        order = order,
                        phase = PartPhase.Streaming,
                        callId = event.callId,
                        name = "tool",
                        command = command,
                    )
                },
                transform = { part ->
                    if (part !is ToolPartUi || part.phase != PartPhase.Streaming) {
                        null
                    } else if (command == null || command == part.command) {
                        null
                    } else {
                        part.copy(command = command)
                    }
                },
            )
        return base.copy(stream = updated)
    }

    private fun onToolInputEnded(
        state: OpenChatState,
        event: ServerEvent.ToolInputEnded,
    ): OpenChatState {
        val base = ensureTurn(state, event.messageId)
        val stream = base.stream
        val key = toolKey(event.callId)
        if (event.messageId in stream.finishedMessages) return base

        val command = ToolInputParsing.commandFromPartial(event.text)
        val updated = stream
            .copy(toolInputBuffers = stream.toolInputBuffers - event.callId)
            .mutatePart(
                messageId = event.messageId,
                key = key,
                create = { order ->
                    ToolPartUi(
                        key = key,
                        order = order,
                        phase = PartPhase.Streaming,
                        callId = event.callId,
                        name = "tool",
                        command = command,
                    )
                },
                transform = { part ->
                    if (part !is ToolPartUi) {
                        null
                    } else {
                        val next = command ?: part.command
                        if (next == part.command) null else part.copy(command = next)
                    }
                },
            )
        return base.copy(stream = updated)
    }

    private fun onToolCalled(state: OpenChatState, event: ServerEvent.ToolCalled): OpenChatState {
        val base = ensureTurn(state, event.messageId)
        val stream = base.stream
        val key = toolKey(event.callId)
        if (event.messageId in stream.finishedMessages) return base

        val command = ToolInputParsing.commandFromJson(event.input)
            ?: ToolInputParsing.commandFromPartial(
                stream.toolInputBuffers[event.callId].orEmpty(),
            )
        val now = nowMs()
        val updated = stream
            .copy(toolInputBuffers = stream.toolInputBuffers - event.callId)
            .mutatePart(
                messageId = event.messageId,
                key = key,
                create = { order ->
                    ToolPartUi(
                        key = key,
                        order = order,
                        // `executed` does not mean "permission to execute": a
                        // called tool is always in progress, and the outcome comes
                        // from success/failed.
                        phase = PartPhase.Running,
                        callId = event.callId,
                        name = "tool",
                        command = command,
                        // UI-facing value: `executed=false` does not mean denied.
                        executed = true,
                        startedAt = now,
                    )
                },
                transform = { part ->
                    if (part !is ToolPartUi ||
                        part.phase == PartPhase.Ok ||
                        part.phase == PartPhase.Failed
                    ) {
                        null
                    } else {
                        part.copy(
                            phase = PartPhase.Running,
                            command = command ?: part.command,
                            executed = true,
                            startedAt = part.startedAt ?: now,
                            endedAt = null,
                        )
                    }
                },
            )
        return base.copy(stream = updated)
    }

    private fun onToolProgress(
        state: OpenChatState,
        event: ServerEvent.ToolProgress,
    ): OpenChatState {
        val base = ensureTurn(state, event.messageId)
        val stream = base.stream
        val key = toolKey(event.callId)
        if (event.messageId in stream.finishedMessages) return base

        val updated = stream.mutatePart(
            messageId = event.messageId,
            key = key,
            create = { order ->
                ToolPartUi(
                    key = key,
                    order = order,
                    phase = PartPhase.Running,
                    callId = event.callId,
                    name = "tool",
                    exitCode = event.exitCode,
                    truncated = event.truncated,
                    startedAt = nowMs(),
                )
            },
            transform = { part ->
                if (part !is ToolPartUi || part.phase.isToolTerminal()) {
                    null
                } else {
                    val next = part.copy(
                        exitCode = event.exitCode ?: part.exitCode,
                        truncated = part.truncated || event.truncated,
                    )
                    if (next == part) null else next
                }
            },
        )
        return base.copy(stream = updated)
    }

    private fun onToolFinished(
        state: OpenChatState,
        event: ServerEvent.ToolSuccess,
        failed: Boolean,
    ): OpenChatState = finishTool(
        state = state,
        messageId = event.messageId,
        callId = event.callId,
        content = event.content,
        exitCode = event.exitCode,
        truncated = event.truncated,
        failed = failed,
    )

    private fun onToolFinished(
        state: OpenChatState,
        event: ServerEvent.ToolFailed,
        failed: Boolean,
    ): OpenChatState = finishTool(
        state = state,
        messageId = event.messageId,
        callId = event.callId,
        content = event.content,
        exitCode = event.exitCode,
        truncated = event.truncated,
        failed = failed,
        status = event.status,
        message = event.message,
    )

    private fun finishTool(
        state: OpenChatState,
        messageId: String,
        callId: String,
        content: List<String>,
        exitCode: Int?,
        truncated: Boolean,
        failed: Boolean,
        status: String? = null,
        message: String? = null,
    ): OpenChatState {
        val base = ensureTurn(state, messageId)
        val stream = base.stream
        if (messageId in stream.finishedMessages) return base
        val marker = terminalKey(messageId, callId)

        val key = toolKey(callId)
        val output = content.joinToString("").trimEnd().takeIf { it.isNotEmpty() }
        val buffer = stream.toolInputBuffers[callId].orEmpty()
        val command = ToolInputParsing.commandFromPartial(buffer)
        // `Denied` only if the failure explicitly states so. `executed` is not
        // involved: in 2.1.4 it is false even for tools that completed
        // successfully.
        val phase = when {
            !failed -> PartPhase.Ok
            isExplicitDenial(content, status, message) -> PartPhase.Denied
            else -> PartPhase.Failed
        }
        val now = nowMs()
        val updated = stream
            .copy(
                terminalToolCalls = stream.terminalToolCalls + marker,
                toolInputBuffers = stream.toolInputBuffers - callId,
            )
            .mutatePart(
                messageId = messageId,
                key = key,
                create = { order ->
                    ToolPartUi(
                        key = key,
                        order = order,
                        phase = phase,
                        callId = callId,
                        name = "tool",
                        command = command,
                        output = output,
                        exitCode = exitCode,
                        truncated = truncated,
                        executed = phase != PartPhase.Denied,
                        startedAt = now,
                        endedAt = now,
                    )
                },
                transform = { part ->
                    if (part !is ToolPartUi) {
                        null
                    } else if (part.phase == PartPhase.Ok || part.phase == PartPhase.Failed) {
                        // First terminal outcome wins: no output bounce.
                        null
                    } else {
                        part.copy(
                            phase = phase,
                            command = part.command ?: command,
                            output = output ?: part.output,
                            exitCode = exitCode ?: part.exitCode,
                            truncated = part.truncated || truncated,
                            executed = phase != PartPhase.Denied,
                            startedAt = part.startedAt ?: now,
                            endedAt = now,
                        )
                    }
                },
            )
        return base.copy(stream = updated)
    }

    // --- step / execution ---------------------------------------------------

    private fun onStepStarted(state: OpenChatState, event: ServerEvent.StepStarted): OpenChatState {
        if (event.messageId.isBlank()) return state
        if (event.messageId in state.stream.finishedMessages) return state

        val live = state.stream.liveTurn
        if (live?.id == event.messageId) {
            if (live.running && live.createdAt != null) return state
            return state.copy(
                stream = state.stream.copy(
                    liveTurn = live.copy(
                        running = true,
                        createdAt = live.createdAt ?: event.startedAt ?: nowMs(),
                        revision = live.revision + 1,
                    ),
                ),
            )
        }

        // Multi-step: the previous turn does not disappear while waiting for the
        // resync.
        val materializedState = materializeLive(state)
        val turn = AssistantTurnUi(
            id = event.messageId,
            parts = emptyList(),
            createdAt = event.startedAt ?: nowMs(),
            running = true,
            revision = 1,
        )
        return materializedState.copy(
            stream = materializedState.stream.copy(liveTurn = turn),
        )
    }

    private fun onStepEnded(state: OpenChatState, event: ServerEvent.StepEnded): OpenChatState {
        val stream = state.stream
        val live = stream.liveTurn
        val turn = if (live?.id == event.messageId) {
            live.copy(
                running = false,
                finish = event.finish ?: live.finish,
                revision = live.revision + 1,
            )
        } else {
            live
        }
        return state.copy(
            stream = stream.copy(
                liveTurn = turn,
                finishedMessages = stream.finishedMessages + event.messageId,
            ),
        )
    }

    private fun onStepFailed(state: OpenChatState, event: ServerEvent.StepFailed): OpenChatState {
        val stream = state.stream
        val live = stream.liveTurn
        val targetId = event.messageId ?: live?.id
        val turn = if (live != null && (targetId == null || live.id == targetId)) {
            live.copy(
                running = false,
                parts = live.parts.map { it.interruptIfActive() },
                revision = live.revision + 1,
            )
        } else {
            live
        }
        return state.copy(
            stream = stream.copy(
                liveTurn = turn,
                finishedMessages = if (targetId != null) {
                    stream.finishedMessages + targetId
                } else {
                    stream.finishedMessages
                },
            ),
        )
    }

    private fun onExecutionFinished(
        state: OpenChatState,
        event: ServerEvent.ExecutionFinished,
    ): OpenChatState {
        val live = state.stream.liveTurn
        val interrupted = live?.let { turn ->
            val needsInterrupt = turn.parts.any {
                it.phase == PartPhase.Streaming || it.phase == PartPhase.Running
            }
            if (needsInterrupt) {
                turn.copy(
                    running = false,
                    parts = turn.parts.map { it.interruptIfActive() },
                    revision = turn.revision + 1,
                )
            } else {
                turn.copy(running = false)
            }
        }
        val materialized = if (interrupted != null) {
            materializeLive(state.copy(stream = state.stream.copy(liveTurn = interrupted)))
        } else {
            state
        }
        return materialized.copy(
            // If resync fails (network lost at end of shift), the content remains.
            running = false,
            outcome = event.outcome,
            stream = materialized.stream.copy(
                liveTurn = null,
                finishedMessages = if (live != null) {
                    materialized.stream.finishedMessages + live.id
                } else {
                    materialized.stream.finishedMessages
                },
            ),
        )
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Ensures the live turn is the one from the event message. A previous non-empty
     * turn is materialized (multi-step or events arrived with a different id).
     */
    private fun ensureTurn(
        state: OpenChatState,
        messageId: String,
        createdAt: Long? = null,
    ): OpenChatState {
        if (messageId.isBlank()) return state
        val stream = state.stream
        if (messageId in stream.finishedMessages) return state

        val live = stream.liveTurn
        if (live?.id == messageId) return state

        val materializedState = materializeLive(state)
        val turn = AssistantTurnUi(
            id = messageId,
            parts = emptyList(),
            createdAt = createdAt ?: nowMs(),
            running = true,
            revision = 1,
        )
        return materializedState.copy(
            stream = materializedState.stream.copy(liveTurn = turn),
        )
    }

    /**
     * Move the live turn (if it has a part) into `messages` without duplicating the
     * id.
     */
    private fun materializeLive(state: OpenChatState): OpenChatState {
        val live = state.stream.liveTurn ?: return state
        if (live.parts.isEmpty()) return state
        val alreadyPresent = state.messages.any { it.id == live.id }
        val messages = if (alreadyPresent) {
            state.messages
        } else {
            state.messages + live.copy(running = false).toMessageUi()
        }
        return state.copy(
            messages = messages,
            stream = state.stream.copy(
                materialized = state.stream.materialized + live.id,
            ),
        )
    }

    /**
     * Apply a transformation to the `key` part of the live turn. If the part does
     * not exist, it is created with the current cursor; the list remains sorted by
     * `order`, so a late update of a part with a lower order does not move it to
     * the end.
     */
    private inline fun ChatStream.mutatePart(
        messageId: String,
        key: String,
        create: (order: Int) -> TurnPartUi,
        transform: (TurnPartUi) -> TurnPartUi?,
    ): ChatStream {
        val turn = liveTurn ?: return this
        if (turn.id != messageId) return this

        val parts = turn.parts.toMutableList()
        val index = parts.indexOfFirst { it.key == key }
        if (index >= 0) {
            val old = parts[index]
            val new = transform(old) ?: return this
            if (new == old) return this
            parts[index] = new
        } else {
            val nextOrder = (turn.parts.maxOfOrNull { it.order } ?: -1) + 1
            parts.add(create(nextOrder))
        }
        return copy(
            liveTurn = turn.copy(
                parts = parts.sortedBy { it.order },
                revision = turn.revision + 1,
            ),
        )
    }

    private fun TurnPartUi.interruptIfActive(): TurnPartUi = when {
        phase != PartPhase.Streaming && phase != PartPhase.Running -> this
        this is ReasoningPartUi -> copy(phase = PartPhase.Interrupted, endedAt = nowMs())
        this is TextPartUi -> copy(phase = PartPhase.Interrupted)
        this is ToolPartUi -> copy(phase = PartPhase.Interrupted, endedAt = nowMs())
        else -> this
    }

    /** A failure is `Denied` only if the text/status explicitly states so. */
    private fun isExplicitDenial(
        content: List<String>,
        status: String?,
        message: String?,
    ): Boolean {
        val text = buildString {
            append(content.joinToString("\n"))
            if (!message.isNullOrBlank()) append('\n').append(message)
            if (!status.isNullOrBlank()) append('\n').append(status)
        }
        if (text.isBlank()) return false
        val haystack = text.lowercase(java.util.Locale.ROOT)
        return DENIAL_HINTS.any { haystack.contains(it) }
    }

    private val DENIAL_HINTS = listOf(
        "denied",
        "rejected",
        "not approved",
        "permission denied",
        "aborted by user",
    )

    private fun toolKey(callId: String): String = "tool:$callId"

    private fun terminalKey(messageId: String, callId: String): String =
        "$messageId/$callId"

    private fun nowMs(): Long = System.currentTimeMillis()
}

/**
 * Adds the streaming message to the list only if it is not already present: on
 * resync the server returns the same `messageID` and the server version wins.
 */
@Suppress("unused")
private fun List<MessageUi>.withLive(live: LiveMessage?, expectedId: String?): List<MessageUi> {
    val message = live ?: return this
    if (message.text.isBlank()) return this
    if (expectedId != null && message.messageId != expectedId) return this
    if (any { it.id == message.messageId }) return this
    return this + MessageUi(
        id = message.messageId,
        role = Role.Assistant,
        text = message.text,
    )
}

/**
 * Reconcile server truth with local messages. - dedupe by id; - an optimistic
 * message is replaced by the server version with the same id or the same text
 * within [maxSkewMs]; - pending messages not yet present on the server remain
 * queued at the bottom.
 */
fun reconcileMessages(
    server: List<MessageUi>,
    local: List<MessageUi>,
    maxSkewMs: Long = 120_000L,
): List<MessageUi> {
    val result = ArrayList<MessageUi>(server.size + local.size)
    val seen = HashSet<String>()
    server.forEach { message ->
        if (seen.add(message.id)) result.add(message)
    }

    local.filter { it.pending }.forEach { candidate ->
        val duplicate = result.any { existing ->
            existing.id == candidate.id || (
                existing.role == Role.User &&
                    candidate.role == Role.User &&
                    existing.text == candidate.text &&
                    timeMatches(existing.createdAt, candidate.createdAt, maxSkewMs)
                )
        }
        if (!duplicate && seen.add(candidate.id)) result.add(candidate)
    }
    return result
}

private fun timeMatches(a: Long?, b: Long?, maxSkewMs: Long): Boolean {
    if (a == null || b == null || a <= 0L || b <= 0L) return true
    return abs(a - b) <= maxSkewMs
}

/**
 * Events that must reach the UI **without** waiting for the throttle window (§4.4):
 * phase changes and turn end. Only pure deltas pass through [PublishGate], where a
 * dropped frame is invisible.
 */
internal fun ServerEvent.publishedImmediately(): Boolean = when (this) {
    is ServerEvent.TextDelta,
    is ServerEvent.ReasoningDelta,
    is ServerEvent.ToolInputDelta,
    is ServerEvent.ToolProgress -> false

    else -> true
}
