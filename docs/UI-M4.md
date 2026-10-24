# UI M4 — approvals

Date: Oct 24 2026. References: `docs/API-M4.md`, `docs/DESIGN.md` §9,
`docs/THEME.md`, `docs/UI-M1.md`, `docs/UI-M3.md`.

Data: `UiState.approvals`, `approvalsLoading`, `approvalsError`,
`pendingApprovals`, `replyApproval()`, `refreshApprovals()`. The backend is
unchanged: SSE events, `GET …/permission`, `decision` reply and the 404
`PermissionNotFoundError` stay as per contract.

## In-chat panel

The panel lives **above the composer**, inside the chat column: it is not a
dialog and it does not block reading the conversation. It appears with a brief
transition (fade + expand) only when `approvals` is not empty and disappears as soon
as the last request is resolved.

- **Title** — action + first resource, in mono: `read perm-probe.env`,
  `bash rm -rf /tmp/x`. If there are other resources, a `+N resources` chip; if the
  server indicates rules to save, a `save +N` chip. The `1/2` count is
  next to the title.
- **Secondary row** — discreet origin in mono, `origin tool · per_3f9a…`.
  `ApprovalUi` exposes `sourceLabel` (the type, e.g. `tool`) and the `requestID`: the
  old `source.id` field is not in the UI model, so the truncated id shown
  is that of the request, not the tool's. If `message` is present, it appears
  below in `bodySmall` muted, maximum 3 lines.
- **Actions** — *Allow once* is the only full action (interactive
  `#FAB283`, dark text), *Deny* is a neutral outlined, *Allow always* is an
  amber text button (`warningInk`, in dark `#F5A742`) separated on the
  line below, with the note "lasts forever on the server". No default on "always".
- **Stack** — with multiple requests the selected one is expanded and the others stay as
  compact 48 dp rows (maximum 3, then `+N more requests`). You switch with
  a horizontal swipe on the card (threshold 64 dp), with the arrows in the header, or
  by touching a row. During the send the navigation is disabled, so the
  spinner stays on the pressed request.
- **Send** — the state is local to the panel: spinner on the pressed action and
  all actions disabled. The header refresh button shows a
  spinner while `approvalsLoading` is true.
- **404** — `request no longer valid` becomes an informational note (`info`,
  not `error`): "Request no longer active: it had already been resolved. The list was
  updated." The ViewModel already redid the GET. The general
  chat banner uses the same discreet note with "Close" instead of the
  red banner.

## Badge

- **Chat top bar** — compact amber badge with the number, next to the
  session state. It is not red: a request to approve is not a failure.
- **Session list** — badge in the right column of the row, above the age and
  state.
- **Drawer** — badge on the open-session card and on each row of the tasks
  in the task sheet. The old Sessions tab with the amber dot was removed in
  `0.5.2-m4` (see `docs/UI-M3.md`): the drawer no longer duplicates the list. The
  TalkBack description says how many approvals are pending.

The badges use `UiState.pendingApprovals`, a `session → requestID` map
fed by the `permission.asked` / `permission.replied` events and by the GET
of the open session. It is **presentation only**: no new network call,
no second source of truth. A request resolved from the app or a notification leaves
the map immediately; for the others the badge stays until a GET of the session
disproves it.

## Notifications permission

On the first entry into the connected surface (so after a successful
`connect()`) the app shows a non-modal informational row — "To approve even
from outside the app, allow notifications." — and opens the system `POST_NOTIFICATIONS`
dialog only once on Android 13+. The outcome is remembered in
private preferences (`NotificationOptInStore`): on subsequent startups it is not
asked again, neither after consent nor after a refusal. If the permission is
denied, the notice becomes "Notifications not active: the approvals stay here in
the chat" with **Settings** (`ACTION_APP_NOTIFICATION_SETTINGS`) and
**Close** actions. The in-app panel keeps working in any case.

## Setup copy

The old note "The credentials stay in memory only" was no longer true. Now
the setup says: **"Credentials encrypted on the device with the Keystore.
"Disconnect" deletes them."** The button stays "connect".

## Notifications

In `ApprovalNotifier` only the strings changed, not the dedupe, the ids or
the cancel:

- title: `Approval requested · OpenCode`;
- body: action + first resource (`read perm-probe.env`), eventual `+N`,
  `message` and, if present, `Always would save: …`;
- actions: **Allow**, **Deny**, **Always…** (the last opens the app: the
  explicit confirmation stays in-app as per DESIGN §9).

The project/agent name is not available in `ApprovalUi` and reading the
token in the notifier was not wanted just to compose the title: "OpenCode" is
the stable and understandable context.

## Disconnection

"Disconnect" in the drawer footer now opens an explicit confirmation: the saved
token and the encrypted credentials are deleted, the background connection and the
notifications stop. It is a modal used only for a
destructive action, not for the approvals.

## Palette, accessibility, measures

TUI tokens only: `warningInk` for the approvals and the badges, `interactive` for
the primary action and the links, `info` for the 404 note and the notifications notice,
`error` only for the disconnection and the real errors. No color outside the
palette. Every action and every arrow has a 48 dp target, the TalkBack
descriptions make clear the number and position of the request; the texts wrap and
the panel does not cover the conversation.

## Code changes

- New: `ApprovalPanel.kt` (panel, stack, "always" confirmation, 404 note),
  `NotificationOptIn.kt` (non-modal notice + memory of the first ask),
  `docs/UI-M4.md`.
- `ChatScreen.kt`: panel above the composer, badge in the top bar, notifications
  notice, 404 note in place of the red banner.
- `SessionListScreen.kt` / `OmoDrawer.kt`: badge per row, dot on the Sessions tab,
  disconnection confirmation.
- `Theme.kt`: shared `ApprovalBadge` (amber, TalkBack description).
- `SetupScreen.kt`: new note on the Keystore and Disconnect.
- `ApprovalNotifier.kt`: only the title, body and actions texts.
- `AppViewModel.kt`: **presentation only** — `pendingApprovals` map derived
  from the already existing events and GET, `approvalCount` /
  `approvalCountTotal` helpers, resetting the previous error on send. No
  change to `net/*`, `security/TokenStore.kt`, `ApprovalService` or the receiver.
- Version `0.5.0-m4` / `5`. No git commit.
