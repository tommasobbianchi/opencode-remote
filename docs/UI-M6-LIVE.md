# UI M6 — live turn (reasoning, tools, text)

Date: Oct 24 2026. References: `docs/THEME.md`, `docs/DESIGN.md`,
`docs/API-M2.md`, `docs/UI-M4.md`, `ChatScreen.kt`, `net/ChatReducer.kt`,
`net/Events.kt`, TUI screenshot `/tmp/ocm-screen.jpg`.

Goal: during the turn the chat must show **the same things shown by the
TUI**, in the same order: reasoning, command being written, command with output,
then the streaming text (and again tool → text). The single
"writing…" placeholder disappears as turn content.

Hard constraint: **a single renderer**. Live turns and the persisted
message after resync pass through the same composable with the same styles.
No separate "live" path, no duplication of colors or measurements.

---

## 1. Widget tree (turn)

A `LazyColumn` item, key `turn:<messageId>`, that stays the same
during live → resync (no jumping, no double bubble).

```
AssistantTurn(turn: AssistantTurnUi)          // sole entry point
└ Column(fillMaxWidth)
  ├ Row(Start)
  │ └ Surface(AssistantBubbleShape, surfaceContainer,
  │           border = if (running) interactive 30% else border 55%)
  │   └ Column(padding 14dp / 12dp, spacedBy 10dp)
  │     ├ if (parts.isEmpty() && turn.running) ThinkingPlaceholder   // §2.4
  │     └ parts.sortedBy(order).forEach { part ->
  │         key(part.key) {
  │           when (part) {
  │             is ReasoningPartUi -> ReasoningBlock
  │             is ToolPartUi      -> ToolCard
  │             is TextPartUi      -> MarkdownBody(part.text, streaming = ...)
  │           }
  │         }
  │         if (part is the last && turn.running && no active part)
  │             ThinkingPlaceholder                        // §2.4
  │       }
  └ Row(Start) padding(top 4dp, start 3dp) → MessageTimestamp
```

Tree rules:

- **A single `Surface` for the assistant message**: reasoning, tools and text are
  children of the same bubble. Tool cards do not escape the bubble.
- `parts` is already ordered by the reducer; the composable neither reorders
  nor groups by type. This is what is missing today: `AssistantMessage` renders
  first *all* the reasoning, then *all* the text, then *all* the tools, so
  the TUI interleaving is lost.
- **Persisted = live**: after resync `turn` is built from
  `MessageUi.parts` (mapped from `content[]`, order = array index).
  The composable receives the same type and produces the same tree. Zero
  duplicated styles, zero difference between "before" and "after".
- Handoff: the reducer replaces the live turn with the server message **having
  the same `id`**; the item has the same key, so the list does not jump and
  expanded parts stay expanded when the `key` matches (§3.5).
- Scrolling: the bubble uses the existing `bubbleMax`
  (`(maxWidth - 32dp) * 0.85`, minimum 220dp). No full-width blocks.

---

## 2. Parts, one by one

### 2.1 Reasoning

Clickable header (target ≥48dp), single line:

| Element | Style |
|---|---|
| chevron 14dp right/down | header color (below) |
| dot 5dp | only while streaming, `warningInk`, `pulsing = true` |
| "Reasoning" | `labelMedium` (12sp), header color |
| " · 1.3 s" | `labelMedium`, `mutedText`; ticking while streaming |
| "open" / "close" on the right | `labelSmall` (11sp), `mutedText` |

Header color, as today and as in the TUI (`THEME` §Mapping 1):
`text.base` while streaming → `warningInk` expanded → `warningInk` 60% (dark only)
collapsed and finished; in light mode, full `warningInk`.

- **Collapsed by default**, even while streaming.
- **While streaming** the body does not open on its own: below the header
  **a preview line** appears (last non-empty line of the text), `bodySmall`,
  `mutedText`, `maxLines = 1`, ellipsis. It gives a sign of life without making
  the chat jump at every token.
- **Expanded**: surface `surfaceContainerLowest` (dark = `#0a0a0a`), border
  1dp `warningInk` 22% (35% while streaming), shape `shapes.small` (10dp),
  padding 12dp, text `bodySmall` 12.5sp/18sp in `mutedText`,
  `SelectionContainer`.
- **Limit**: first 14 lines (or 2400 characters) + "show all · N
  lines" line (48dp, `interactive`, `labelSmall`) that opens the §2.5 sheet with
  the full text. Beyond the limit the full text is never passed to `Text`.
- **Duration**: `reasoning.started` → `reasoning.ended`. Format:
  `<10 s` → "1.3 s" (decimal point, one decimal); `<60 s` → "12 s";
  beyond → "1 min 12 s". If the times are missing (old messages),
  " · N lines" / " · 1 line" is used, as today. Never both: duration wins.
- Accessibility: `stateDescription` "expanded"/"collapsed", `onClickLabel`
  "Show reasoning" / "Hide reasoning".

### 2.2 Tool

Card always visible, **always open** (the TUI has no collapse): no
chevron, no tap on the whole card (that would break text selection).
Only the "show all" line is clickable.

Structure:

```
Column(surfaceContainerLow, border 1dp border 50%, shapes.small, padding 10/8)
├ Row 1 (min 48dp, spacedBy 7dp)
│  ├ StatusDot 5dp                     // color = phase
│  ├ tool name  mono 12sp/16sp Medium  // interactive, maxLines 1, max 40% of the row
│  ├ status label  labelSmall 11sp     // color = phase
│  ├ exit chip (if exit ≠ 0) mono 10sp // error, e.g. "exit 1"
│  └ Spacer(weight 1) + duration mono 10sp/13sp lineNumber
├ command line (if any)
│  "$ " mono 12sp lineNumber + command mono 12sp/17sp text.base
└ output block (if any)
   └ mono 11.5sp/17sp text, last 6 lines, + "show all · N lines"
```

- **Name**: `bash`, `read`, `edit`… `interactive`, mono 12sp Medium, 1 line,
  ellipsis, at most ~40% of the line.
- **Command**: its own line, `$ ` in `lineNumber`, command in `text.base`
  (in the TUI the command is white, the `$` is dim). Mono 12sp/17sp, **wrap
  up to 4 lines**, then ellipsis + the "show all" line opens the sheet with
  the full command **in horizontal scroll** (`softWrap = false`), so
  a very long one-liner stays readable without breaking tokens.
- **Non-command subject** (`read`, `write`, `edit`, `grep`…): no `$`;
  the first useful field between `path` / `filePath` / `pattern` /
  `query` / `url` / `description` is shown. Same line, same mono.
- **Status** (dot + label, colors `THEME` §Mapping 2):

| phase | dot | label | color |
|---|---|---|---|
| input arriving, command not yet extractable | pulsing warning | "writing the command…" | `warningInk` |
| call started, running | pulsing warning | "in progress" | `warningInk` |
| finished | success | "ok" | `success` |
| failed | error | "error" | `error` |
| denied / not run (`executed=false`) | error | "denied" | `error` |
| interrupted (turn stopped) | warning | "interrupted" | `warningInk` |

- **Duration**: from call start to outcome, same format as reasoning.
  Shown only when ≥1 s; ticks while running. Not shown for "denied".
- **Exit**: chip `exit 1` in mono 10sp `error` next to the status when
  `exit != 0`; for "ok" with exit 0 nothing is shown. If `truncated` is true,
  chip `output truncated` (`labelSmall`, `lineNumber`).
- **Output**: last **6 lines** (the tail is where the errors are, as in the
  terminal), mono 11.5sp/17sp `text`, no extra background (the card is
  already `surfaceContainerLow`), 8dp below the command. If the tool produced
  no text: line "(no output)" in `lineNumber` mono 11.5sp. While running,
  the output grows at the tail, with no layout animations.
- **Failed**: same layout, dot/label `error`, output visible at the tail; the
  border does **not** turn red (the TUI does not color borders; the status is
  already carried by dot, label and exit).
- **Denied**: name only, then the "not run" note in `mutedText`; no output
  block (the command is not shown anywhere in the UI since 0.13.0).
- **"show all · N lines"**: 48dp line, `labelSmall` `interactive`,
  left-aligned, below the output. Opens the §2.5 sheet with the full **output
  only** (the command was removed from the sheet in 0.13.0). N = total lines
  of the output.
- **Selection**: `SelectionContainer` on the output, never on the whole card,
  so the tap on the "show all" line stays reliable.

### 2.3 Text

- Reuse of `MarkdownBody` (heading/link/code/quote already themed), `bodyLarge`
  16sp/24sp, `oc.text`, `SelectionContainer` per block.
- **Cursor**: `▌` appended to the last text part **only** when
  `phase == Streaming`, color `interactive`, same `bodyLarge`; blink
  1 ↔ 0.35 in 900 ms (at the end of the text it stays full if animators are off).
  One cursor per turn: the one of the last streaming text part.
- No "is responding" label: the cursor and the active parts convey the state.
- The text is never truncated: it is the main content.
- Every block has `key = "text:<ordinal>"`; the markdown is recomputed with
  a throttle (§4.4).

### 2.4 No part yet (fallback)

Covers two cases: `step.started` with no part, or in-progress turn with
the last part already finished (pause between text and the next tool).

- Inside the bubble, in place of the content / after the last part, on one line:
  `WorkingIndicator(12dp, success)` + "writing…" (`labelMedium`,
  `mutedText`). Replaces the three dots: a single arc, like the rest
  of the app.
- Appears **only** if `turn.running && no part is Streaming/Running`.
- **400 ms grace** (`LaunchedEffect` + `delay`) before appearing: avoids the
  flicker in the text.ended → tool.started transition.
- TalkBack: the arc is decorative (`description = "waiting"`), the line
  announces "writing…" only once.
- With `running == false` and zero parts nothing is drawn (the bubble
  does not exist): any error is already carried by the `ErrorBanner`.

### 2.5 Detail sheet (single, shared)

`ModalBottomSheet` (Material 3), `surfaceContainer` = `raised`, top corners
`shapes.large`.

- Title: `output · bash` (mono 11sp, `mutedText` in `Eyebrow` uppercase) and
  on the right "close" (48dp, `labelSmall` `mutedText`).
- Body: "command" and "output" sections with `Eyebrow`; text mono 11.5sp/17sp
  in **horizontal** (no wrap) and vertical scroll, max height 80% of the screen.
- "copy" action (`interactive`, 48dp) copies the visible section; discreet
  "copied" confirmation for 1.5 s.
- Used by: tool output/command, long reasoning. A single composable,
  parameters `title`, `sections`.

---

## 3. Order, duplicates, reducer (contract)

### 3.1 Identity and order

- Turn = `assistantMessageID`. At most **one live turn** per session.
- `order` is a monotonic cursor **per message**, incremented on the first
  event of a part (`reasoning.started`, `text.started`,
  `tool.input.started`, or `tool.called` if the input never arrived) and never
  reused. It is not the `ordinal`: the `ordinal` is per type, tools do not have
  it, and a single counter is the only thing that reconstructs the interleaving.
- After resync `order` = index of the part in `content[]`. Same semantics,
  same ordering, same renderer.
- `ordinal` remains a part field (`reasoning`/`text`) because it identifies
  the SSE delta, not the visual position.
- Stable `key`, never dependent on text or state:
  `reasoning:<ordinal>`, `text:<ordinal>`, `tool:<callId>`.
  Persisted without call id: `tool:<index>`; without ordinal: `<type>:<index>`.

### 3.2 Events → state map

| event | effect |
|---|---|
| `session.step.started` | creates the turn `running`, `startedAt`; if a **non-empty live turn with a different id** already exists, materialize it into `messages` first (multi-step turns) |
| `reasoning.started` | creates `ReasoningPartUi(phase=Streaming, order=cursor++)`; idempotent if it exists |
| `reasoning.delta` | **appends** only if the part is not terminal; if the part is missing it creates it (recovery) |
| `reasoning.ended` | replaces the text with the server's `text`, `phase=Done`, `endedAt`; closes the part |
| `text.*` | identical, with `TextPartUi` |
| `tool.input.started` | creates `ToolPartUi(phase=Streaming, order=cursor++, callId=id, name)`; idempotent on `callId` |
| `tool.input.delta` | accumulates into a private buffer; does **not** render the JSON. Tries to leniently extract `command` from the partial JSON: on success updates `command`; otherwise stays "writing the command…" |
| `tool.input.ended` | `text` = full JSON: extracts `command`/subject and `inputPreview`; the phase does **not** change (waiting for `called`) |
| `tool.called` | structured input available: updates name/command; `executed=false` → `phase=Denied`; otherwise `phase=Running`, `startedAt` |
| `tool.progress` | updates metadata only (exit, truncated); no invented output line |
| `tool.success` | `output` = join of `content[].text`, `exitCode`, `truncated`, `phase=Ok`, `endedAt` |
| `tool.failed` | as above, `phase=Failed` (or `Denied` if `executed=false`) |
| `session.step.streamed` | no visual change (the cursor depends on the text part phase) |
| `session.step.ended` | turn `running=false`, `finish`; does **not** remove the parts; the turn stays until resync replaces it with the same id |
| `session.step.failed` | all `Streaming/Running` parts → `Interrupted`; turn `running=false` |

### 3.3 Late, duplicate, repeated

- Every handler is **idempotent**: `started` on an existing part does
  nothing; `ended` replaces the text (the server wins over the accumulated
  delta); `success`/`failed` on an already terminal part is ignored.
- After `step.ended` for that `messageId`: new delta/parts are
  ignored (`finishedMessages`, as today). Only resync is the exception.
- A part that arrives **after** others with a lower `order` is **inserted**
  into its place (sorted list), not appended; the stable `key` preserves
  the expansion state.
- Delta without `started`, `ended` without delta: recover by creating/finishing
  the part at the current cursor. Never a crash, never JSON on screen.
- `tool.input.ended` without `tool.called`: keep the extracted command and
  the `Streaming` phase; if `success` arrives without `called`, go directly
  to `Ok`.
- Double `tool.success` with different content: the first terminal outcome wins
  (no "bouncing" of the output on screen).

### 3.4 Multiple turns

`step.started` with a different id while the live turn is non-empty: the old
turn is materialized into `messages` (same id, same parts, `running =
false`) and the new one starts live. Earlier bubbles do not disappear waiting
for the final resync (behavior already present, to be extended to parts).

### 3.5 Handoff live → persisted

- `GET …/message` returns the message with the same `id` and the complete
  parts: the reducer **replaces** the live entry with the server one,
  list keys unchanged (`message.id`).
- The parts' `key` must coincide whenever possible (call id and ordinal
  are in the payload); if they do not, the expanded state is reset: acceptable,
  never a different style.
- If the resync fails at the end of the turn, the live turn stays materialized
  in `messages` (already the case today): no content is lost.

---

## 4. Measures, tokens, mobile, performance

### 4.1 Tokens (all already in `Theme.kt` / `OpenPalette`)

| use | token |
|---|---|
| bubble, tool card | `surfaceContainer` (`raised` #141414) / `surfaceContainerLow` (#101010) |
| reasoning body | `surfaceContainerLowest` (#0a0a0a) |
| text | `oc.text`; secondary `oc.mutedText`; meta `oc.lineNumber` |
| borders | `oc.border` 50–55%; reasoning `warningInk` 22/35% |
| reasoning | `oc.warningInk` (60% dark only, collapsed and finished) |
| tool name, cursor, "show all", links | `oc.interactive` |
| ok | `oc.success`; in progress/interrupted `oc.warningInk`; error/denied `oc.error` |
| markdown code background | `oc.formField` (unchanged) |

No new colors. No new fonts. `monoStyle()` for every piece of technical data.

### 4.2 Typography and spacing

| element | measure |
|---|---|
| bubble padding | 14dp H / 12dp V (unchanged) |
| gap between parts | 10dp (unchanged) |
| reasoning header | min 48dp; body 12dp; header→body gap 6dp |
| tool card | padding 10dp H / 8dp V; radius `shapes.small` 10dp; inner gaps 6dp; command→output 8dp |
| tool name | mono 12sp/16sp Medium |
| command | mono 12sp/17sp |
| output | mono 11.5sp/17sp |
| status label | `labelSmall` 11sp |
| duration / exit | mono 10sp/13sp |
| reasoning (header) | `labelMedium` 12sp; preview/body `bodySmall` 12.5sp/18sp |
| text | `bodyLarge` 16sp/24sp |
| "show all" | `labelSmall` 11sp, 48dp line |

### 4.3 Mobile constraints

- Width: everything inside `bubbleMax`; the detail sheet is the only
  full-width surface.
- **No unlimited nested vertical scroll**: inline only
  line limits are used (output 6, command 4, reasoning 14) and the rest goes
  to the sheet. Horizontal scroll is allowed only inside the sheet.
- Target ≥48dp: reasoning header, "show all" line, sheet actions,
  "close"/"copy". The tool card is not clickable.
- Truncation: names 1 line with ellipsis; commands 4 lines; output 6 lines;
  reasoning 14 lines. The model's text is never truncated.
- Selection: one `SelectionContainer` per block (text, reasoning, output),
  never a single one for the whole bubble.
- `maxLines` is not enough: to `Text` **only the visible portion** is passed
  (last 6 lines for output, first 14 for reasoning). The rest stays
  in state and goes to the sheet.

### 4.4 Performance with frequent deltas

- **A single item** for the turn (`turn:<id>`): deltas do not rebuild the
  `LazyColumn`, only the bubble. Inside the bubble, parts in a `Column` with
  stable `key` (a turn has a few dozen parts; no nested `LazyColumn`).
- **Throttled publish**: the reducer accumulates deltas (cheap); the UI
  receives a snapshot at most every **80–120 ms**, except terminal
  events (`ended`, `success`, `failed`, `step.ended`) that publish
  immediately. Avoids 30–60 recompositions/s.
- **Incremental markdown**: `parseMarkdown` memoized on
  `(key, text length/hash)`; already closed blocks stay cached,
  only the open tail is re-parsed. The cursor is not part of the
  parsed text: it is drawn at the tail, so the parse does not change on every blink.
- **One animation per turn only**: one `rememberInfiniteTransition` for
  the cursor (alpha via `graphicsLayer`), one for the active dot, not one per
  part. If `ValueAnimator.areAnimatorsEnabled()` is false, the cursor and dot stay
  full.
- **A single ticker** for the turn (`produceState`, 100 ms), active only if
  there is at least one `Streaming/Running` part; stops when the turn ends.
  Duration shows decimals only below 10 s.
- **No layout animations** for text/output growth: an
  `AnimatedVisibility` per delta is the main cause of jank. The only
  allowed transition is opening/closing the reasoning (user action).
- **Auto-scroll**: only if the user is at the bottom (logic already present),
  throttled ~150 ms, tied to `turn.revision`, never while the user has
  scrolled up. Tool output growth must not force the scroll.
- **No measurement**: no `onTextLayout` for the line limits; strings
  are truncated before rendering.
- `output` and `command` are fields already computed in state: no join or
  parsing inside the composable on every frame.

---

## 5. Copy (English, lowercase, short)

| context | text |
|---|---|
| reasoning | "Reasoning" |
| duration | "1.3 s" · "12 s" · "1 min 12 s" |
| duration fallback | "4 lines" · "1 line" |
| open/close | "open" · "close" |
| tool input in flight | "writing the command…" |
| tool running | "in progress" |
| outcomes | "ok" · "error" · "denied" · "interrupted" |
| tool not run | "not run" |
| exit | "exit 1" (mono) |
| empty output | "(no output)" |
| output truncated by the server | "output truncated" |
| expansion | "show all · 42 lines" |
| no parts | "writing…" |
| sheet | "command" · "output" · "copy" · "copied" · "close" |
| TalkBack | "Show reasoning" · "Hide reasoning" · "Show the full output of bash" · "Reasoning in progress" · "Tool in progress" · "Tool completed" · "Tool failed" · "Tool denied" |

No exclamation marks, no "please", no uppercase titles in the
source (`Eyebrow` does the uppercasing itself).

---

## 6. What to remove or change

1. **`StreamingMessage`** (separate bubble + "is responding"): removed.
   The live turn is `AssistantTurn` like the persisted one; the label disappears.
2. **`TypingIndicator`**: a single use case remains, the §2.4 fallback, with
   `WorkingIndicator` instead of the three dots. It is no longer the content
   of the turn: it appears only if there is no active part.
3. **`ToolChipView`**: removed. The truncated JSON dump of the state is not
   useful information and is the reason the tool looked empty.
   Replaced by `ToolCard` (§2.2) with command, output, status, duration, exit.
4. **`highlightToolOutput`**: no longer used for the state JSON. Tool
   output is plain mono text; if it remains for other uses, it is not called
   from the chat.
5. **`toolStatusOf` / `ToolChip.state`**: the status is no longer derived with
   a regex from the JSON in the UI; it arrives typed from the events (§3.2) and
   from the `content[]` at resync. `summarize()` is no longer needed for rendering.
6. **`AssistantMessage`**: no longer groups reasoning → text → tool. It
   iterates `parts`. The `text`/`reasoning`/`tools` fields of `MessageUi` are no longer
   the source of truth for rendering (they remain for search/copy/compatibility).
7. **`ReasoningSection`**: header with duration (+ line fallback), one-line
   preview while streaming, 14-line limit with "show all".
8. **`showLive` / `showTyping` logic in `ChatScreen`**: replaced with a single
   `liveTurn`; `extraRows` no longer depends on the presence of text.
9. **Tool expand animations**: removed (no more collapse).
   Only the one for reasoning remains, on user action.

Unchanged: user bubbles, timestamps, `MetaDivider`, chat header, composer,
banners, approvals, markdown, palette and typography.

---

## 7. Data contract required (for the reducer implementer)

Types the UI wants. No JSON, no `JsonElement` beyond the
reducer's boundary.

```kotlin
// Done = finished text/reasoning; Ok/Failed/Denied = outcomes of a tool.
enum class PartPhase { Streaming, Running, Done, Ok, Failed, Denied, Interrupted }

sealed interface TurnPartUi {
    val key: String      // stable: "reasoning:0" | "text:1" | "tool:call_abc"
    val order: Int       // per-message cursor; resync = index in content[]
    val phase: PartPhase
}

data class ReasoningPartUi(
    override val key: String,
    override val order: Int,
    override val phase: PartPhase,
    val text: String,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
) : TurnPartUi

data class TextPartUi(
    override val key: String,
    override val order: Int,
    override val phase: PartPhase,
    val text: String,
) : TurnPartUi

data class ToolPartUi(
    override val key: String,
    override val order: Int,
    override val phase: PartPhase,
    val callId: String,
    val name: String,            // "bash", "read", ...
    val command: String? = null, // command or subject, already extracted
    val output: String? = null,  // content[].text already joined
    val exitCode: Int? = null,
    val truncated: Boolean = false,
    val executed: Boolean = true,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
) : TurnPartUi

data class AssistantTurnUi(
    val id: String,                       // assistantMessageID
    val parts: List<TurnPartUi>,          // sorted by order
    val createdAt: Long? = null,
    val finish: String? = null,
    val running: Boolean = false,
    val revision: Long = 0,               // bumped on every change; auto-scroll/throttle
)
```

Minimal state changes:

- `ChatStream`: `liveTurn: AssistantTurnUi?` instead of `LiveMessage`/
  `LivePart`; `nextOrder` per message; `closedParts` and `finishedMessages`
  stay; `materialized` for the multi-step turns.
- `MessageUi`: `parts: List<TurnPartUi>` populated from `content[]` (index →
  `order`); `text`/`reasoning`/`tools` remain as derived fields for
  compatibility, not for rendering.
- Mapping from the payload: `tool` → `ToolPartUi` with
  `state.status` → `PartPhase`, `state.input.command`/`path`/`pattern` →
  `command`, `state.content[].text` → `output`, `state.metadata.exit` /
  `truncated` → fields of the same name, `time.start`/`time.end` → `startedAt`/`endedAt`.

Acceptance criterion: during a turn with tools, the chat shows in
sequence the reasoning (duration), the "writing the command…" line, the command with `$`
and the output within a few tenths of a second from the event, the streaming
text with the cursor; at resync the same bubble stays in its place with the
same styles, without residual placeholders and without JSON dumps.
