# M5 — hardening checklist

Design criterion: *lost network, expired token, server off*.
Each item has a verifiable expected outcome; items marked **[dev]** are tested on the Fold
via `adb` (USB from the MacBook), the others are covered by JVM tests.

## A. Connectivity

| # | Scenario | Expected behavior | Verification |
|---|---|---|---|
| A1 | Wi-Fi off with the app open and SSE alive | banner "no network · waiting for connection", attempts suspended (no backoff/number), no crash | [dev] |
| A2 | Wi-Fi back on | immediate reconnection (network callback), backoff reset, message re-read, no duplicates | [dev] |
| A3 | Wi-Fi off for > 2 min (simulated Doze) | on return it re-aligns by itself; if the app was in the background, it restarts on foreground | [dev] |
| A4 | No network at startup (Tailscale off only) | screen with error "private network not active", Retry button; no crash | [dev] |

## B. Server

| # | Scenario | Expected behavior | Verification |
|---|---|---|---|
| B1 | OpenCode service stopped on the dev server | offline state with a clear message; cached data stays visible; no aggressive retry | [dev] |
| B2 | service restarted | automatic reconnection within the backoff; the open session re-aligns | [dev] |
| B3 | machine asleep / unreachable | timeout mapped to "server unreachable (timeout)", not to a generic error | test + [dev] |
| B4 | server version ≠ 2.x | blocking `VersionMismatch` screen with the version found and an explanation; no further requests | test + [dev with fake server] |

## C. Credentials

| # | Scenario | Expected behavior | Verification |
|---|---|---|---|
| C1 | password changed on the server (401) | `AuthFailed` state, retries stopped, invite to redo pairing; cache visible | [dev] |
| C2 | token dismissed/disconnected | the background service stops, notifications removed, setup shown | [dev] |
| C3 | 401 during a prompt send | the prompt is not retried automatically; clear message; no duplicated send | test + [dev] |

## D. Data

| # | Scenario | Expected behavior | Verification |
|---|---|---|---|
| D1 | session deleted while it is open | message + back to the list (parent if it is a child), no crash | test + [dev] |
| D2 | large responses (`/api/skill` ~98 KB, `/api/agent` 26 KB) | no UI block; parsing off the main thread | test |
| D3 | expired pagination cursor (400) | "page no longer valid" message + refresh, no crash | test |
| D4 | out-of-order / duplicate events | consistent state (the messages are the truth), no double text | test (already partially covered) |

## E. Resources

| # | Scenario | Expected behavior | Verification |
|---|---|---|---|
| E1 | SSE open 10 min in foreground | no monotonic memory growth, CPU low at rest | [dev] `dumpsys meminfo` |
| E2 | animations active on running agents | no abnormal consumption | [dev] |
| E3 | no secrets in the logs | grep on logcat: password/token never present | [dev] |

## Test notes

- Wi-Fi: `adb shell svc wifi disable|enable` (USB stays active).
- Service: `ssh build-host 'opencode service stop|start'`; the sessions remain in the DB.
- Password: `opencode service set password <x>` + `restart` to simulate a revocation, then restore.
