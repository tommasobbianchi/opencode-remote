# UI M3 — "OMO sidebar" drawer

Date: Oct 24 2026. References: `docs/DESIGN.md` §6–§7, `docs/API-M3.md`,
`docs/THEME.md`. Data: `UiState.catalog`, `UiState.children`, `UiState.openSession`.

## Structure

`ModalNavigationDrawer` with the sheet on the **end side (right)**, available from the list and
the chat; the setup stays out of it. Material 3 anchors the sheet to start: the subtree runs
in an RTL layout and the drawer and content come right back to LTR. It is not a reimplementation:
the gesture, scrim and predictive back stay those of the component. `gesturesEnabled` is
tied to the state (`targetValue == Open`): when closed Material does not accept a swipe on
the whole surface (it only opens the edge strip described below); when open the closing swipe
on the sheet and the tap on the scrim stay active.

- Width: the Material sheet maximum is **360 dp** (with `fillMaxWidth` on narrower
  screens it drops to the available width). On the phone it stays in the 320–360 dp range;
  on the Fold inner it stays on the side and covers about half the screen, never a whole
  page. The screen content is not resized (modal drawer): no
  layout jump on open.
- **Opening**: a `GlyphSidebar` icon in the list and chat top bar (48 dp, content
  description "Open the panel for agents, skills and MCP") **and** a swipe from the right
  edge toward the left. Closing via the × icon, a tap on the scrim, and **system back**
  (`ModalDrawerSheet` with `drawerState`: predictive back on Android 14+,
  `BackHandler` fallback below).
- The drawer closes when a session is opened, when going to the SSE log, or when disconnecting.

### Edge gesture and system back

With the drawer closed the swipe is detected **only in the last 22 dp** from the right edge and
requires a horizontal drag of at least 26 dp (vertical component excluded)
before opening; no other part of the screen opens the panel. The detector is on the screen
container, not on a strip over the UI: taps and scroll of the children are not intercepted.
`systemGestureExclusionRects` are not registered: in Android gesture navigation the system back
in the edge zone takes precedence, and that is right. Declared consequence: with
gesture navigation the swipe may be read by the system; **the top bar icon is the
reliable path**, the swipe is an extra habit (and it works with three-button
navigation). The narrow threshold avoids stealing the system gesture as required.

## Content

**Header** — connection indicator (green connected, amber connecting, red on
error), host and version, project name (`catalog.location.projectName`, fallback
to the directory basename) and the directory path with ellipsis; the connection
pill is on the same line as the path, on the right. Below, the **open-session
row** — it is context, not navigation. It shows the title on one line with ellipsis, a status
dot (`active` pulsing; otherwise the outcome `OK` / `error` / `interrupted`, or
`inactive`), `agent · model` with `default` when the server has not assigned them
yet, the pending-approvals badge (M4) and, on the right, the **task badge**
with the number of children running. With no open session the row is replaced by
muted text "no open session": no action, consistent with the Agents and
Skill tabs that stay disabled. At the bottom, the footer keeps **SSE log** (info) and
**Disconnect** (error), both at 48 dp.

**Background tasks** — the task badge (target 48 dp, TalkBack description) opens a
**compact bottom sheet** with the children (`childrenWithActivity`): title, chip of the agent
that is working (`default` if the server has not assigned it yet), model in
mono, live state — `running`, `done · OK`, `done · error`, `interrupted`,
`pending` — approvals badge, refresh in the header and automatic re-read
on open. Tap on a child → opens that session and closes the sheet and the drawer. If there
are no children, the sheet explains that the tasks are the children sessions of the open
session. No "recent" list in the drawer: sessions are opened from the main
screen.

Why: the old Sessions tab repeated the main screen (same list, same
taps) and took space from the catalogs. The drawer now answers a single question — what
am I working on and with which agents/skills/MCP — and navigation between sessions stays where
it belongs.

**Tabs** — compact segmented control (**Agents · Skill · MCP**), 48 dp per segment,
`animateColorAsState` on change, `Role.Tab` and selected state for TalkBack. The active tab
stays in `rememberSaveable`: it survives rotation and the drawer
close. With three entries the segments are wider and the labels are not compressed.

- **Agents** — at the top, with an open session, the **Agent** row with the name of who
  leads the session and the green check (only if it is a primary agent); then the
  **model** card (current agent and model, tap → picker). Follows
  `catalog.agentsForDrawer` with a 2-line description and a `provider/model` reference,
  and the **Subagent** section in **read-only**: no tap, no check, no
  spinner, subdued rows (75%) and microcopy "invoked automatically by the
  primary agent". Delegation is decided by the primary agent, as in the TUI: the list is only
  there to understand what can be delegated and with which model, not to set the session's
  agent. Tap on a primary → `switchAgent` with a spinner and confirmation from the next
  GET (the server is the truth), 9 s timeout if nothing arrives. With no open
  session: "open a session" hint and disabled primary rows (55% alpha).
- **Working indicator** — next to the name of each agent (primaries and subagents) a
  `success` 12 dp arc rotates in 1.2 s when at least one child is running with
  `agent == agent.id`; with more than one active child it shows the count. The
  agent → active children map is built in presentation from `childrenWithActivity`
  (no extra call). The same indicator replaces the static dot in the
  session card when it is active and in the task sheet rows when the child
  is running. With system animators off ("remove animations") it becomes a
  static dot; the TalkBack description says "task running" / "session
  running".
- **Skill** — name and description on 2 lines; tap → `activateSkill` on the open
  session, with an ephemeral confirmation. Same rule on the open session.
- **MCP** — read-only: green `connected` dot, amber for `needs_auth`/unknown
  state, red for `failed`, grey for `disabled`, plus the Italian label.

**States** — compact skeleton during the first load, error with a "Retry" that
calls `loadCatalog(force = true)`, empty catalog with an explanation and an action, dismissible
action error. The 5-minute TTL is respected: opening the drawer does not force a
refresh, the drawer's refresh button does (always visible).

## Model picker

Full-screen dialog (Material 3 needs nothing else): search at the top
(name, provider, `modelID`, family), **90 models grouped by provider**, with
provider heading + count, legible name and `provider · modelID` in mono,
an `in use` badge on the current entry. No flat unfiltered list. Count
`N/total` during the search, "no results" and "no models" states with an action.
The selection calls `switchModel(providerId, modelID, variant = null)` and closes the
picker; the confirmation comes from the value re-read in the session.

## Real cases respected

- A **never used** session has no `agent`/`model`: the session row and the tasks
  show `default` (never an empty row), the model card shows "default ·
  default model" and the picker stays usable — no current model is required
  to pick one.
- `POST .../agent` and `POST .../model` answer 204 and the following GET reflects the
  value: the UI does not assume local success, it waits for the session re-read by the
  ViewModel to confirm (and shows the error if it arrives).

## Palette, typography, accessibility

TUI tokens only (`OpenColors` / `ColorScheme`): accent `#9D7CD8` for the project name,
interactive `#FAB283` for tabs and tappable entries, `success` for active/connected,
`warning` for pending, `error` for error/disconnection, `info` for the children tasks and the
guides. No color outside the palette, no gradient beyond the accent halo
in the header. Space Grotesk for the names, IBM Plex Sans for the text, JetBrains Mono for
paths, ages and model references. Every target is ≥48 dp (tabs, badges, refresh, rows),
the icons have a content description, the segmented control exposes role and state.

## Code changes

- New: `OmoDrawer.kt` (sheet, tabs, rows, states), `ModelPicker.kt` (picker + search),
  `docs/UI-M3.md`.
- `Theme.kt`: four drawn glyphs (`GlyphSidebar`, `GlyphClose`, `GlyphSearch`,
  `GlyphCheck`), same stroke as the others, plus `WorkingIndicator` (1.2 s animated arc,
  static if `ValueAnimator.areAnimatorsEnabled()` is false).
- `UiFormat.kt`: `drawerSessionMeta` ("default" fallback) and `modelRefLabel`.
- `AppViewModel.kt`: **only declared presentation helpers** — `openSessionRow`
  (opens a session known to the drawer, e.g. a child task, without losing its title),
  `UiState.childrenWithActivity` / `runningChildren` (rejoin the children with
  the observed activity on the sessions: `GET ?parentID=` does not bring a live state, the
  SSE events update `sessions`). No new logic or network call.
- `MainScreen.kt`: container with drawer + edge detector; `ChatScreen` and
  `SessionListScreen` receive `onOpenDrawer` and show the icon in the top bar. The
  list overflow (SSE log, Disconnect) was moved into the drawer footer.
- Revision `0.5.2-m4`: removed `SessionsTab`, `SessionFilter`, the filter and the
  recent sessions rows; added `OpenSessionCard`, `TasksSheet` (`ModalBottomSheet`),
  `TaskRow` (agent chip + live state), `SessionAgentRow` and `AgentChip`. Subagents
  in read-only, working indicator mapped to the active children. The drawer no longer
  receives `onRefreshSessions` (the list stays in the main screen).
- Version `0.4.0-m3` / `4`; drawer revision **`0.5.2-m4` / `7`** (3 tabs, session row,
  task bottom sheet). No change to `net/*`, DTO, manifest or API docs; no
  git commit.
