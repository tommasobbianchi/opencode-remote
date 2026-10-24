---
name: opencode-remote-connect
description: >-
  Wizard that connects the OC Remote Android client (opencode-remote, io.github.mirkotappini.opencoderemote)
  on the user's Android phone to an OpenCode v2 server. Beginner-friendly, guided, step-by-step: it
  detects what is missing (server, phone, app) and only asks the user to do the small manual parts the
  agent cannot automate (e.g. enabling USB debugging, pairing wireless debugging, providing the server
  URL). Use when the user says "connect the app", "/opencode-remote-connect", "collega l'app",
  "connect the remote app", "opencode remote connect", "setup the app", or asks to install or hook the
  Android client up to an OpenCode server. Works on any Android device, with or without Tailscale.
---

# opencode-remote-connect

A guided wizard that connects the **OC Remote** Android app on the user's phone to an **OpenCode v2**
server. It is written for people who have never used adb, Tailscale or env vars: the agent asks simple
questions, prints one-line explanations, and only asks the user to perform the few physical steps on
the phone that automation cannot do. Power users can skip the prompts with environment variables.

## What the wizard will do (overview)

1. **Server**: figure out an HTTPS URL for the OpenCode server that the phone can reach — either you
   already have one, or it is set up/exposed for you.
2. **Phone**: find your Android phone over adb (USB or wireless). If it is not connected, it guides
   you through enabling developer/USB debugging.
3. **App**: make sure the OC Remote app is installed on the phone — builds and installs it
   automatically if you have the source, otherwise tells you exactly how to get the APK.
4. **Connect**: launches the app, fills in the server URL + credentials, taps Connect.
5. **Verify**: confirms the phone reached the server and shows the session list.

## How to use

- Invoke `/opencode-remote-connect` (or just say "connect the app") in an OpenCode session on your computer.
- Answer the agent's questions; when it asks you to do something on the phone, do it and say **"done"**.
- If you get stuck at any point, say "help" and it will re-explain that step in plainer terms.

---

## The wizard

### Step 0 – Quick checks (guided)

The agent checks that the basic tools exist. Missing tools don't stop the wizard; they only make the
matching step manual instead of automatic. For each missing tool it prints a one-liner on how to get it:

- `adb` – needed to talk to the phone. Install Android platform-tools (e.g. `sudo apt install
  android-tools-adb` on Debian/Ubuntu, or download "command line tools" from developer.android.com).
- `opencode` CLI – needed only to expose a *local* server automatically. If it's missing and you
  already have a server URL, it doesn't matter.
- `git` + `java` (JDK 17+) – needed only to build the app from source.
- `tailscale` – optional, one of several ways to make a local server reachable from the phone.

### Step 1 – Server side

The agent asks:

> Do you already have an OpenCode server (v2) reachable from your phone? If yes, paste its HTTPS URL.

**Case A – you have a URL.** You provide:
- server URL (e.g. `https://opencode.example.com`),
- username (normally `opencode`),
- password/token.

The agent verifies the URL answers (`curl -skI <url>/api/info` → 200/401/403). If it doesn't answer,
it explains the likely causes (wrong address, phone and server not on the same network, server not
running, HTTPS not configured) and lets you correct it.

**Case B – no URL, but opencode is installed on this machine.** The agent offers to expose it:
- **Tailscale available** → runs `tailscale serve --bg https+insecure://localhost:PORT`, shows you the
  public `https://…ts.net:PORT` URL, and verifies it. (Both the PC and the phone need the Tailscale
  app and to be signed in to the same tailnet.)
- **No Tailscale** → explains simply: "Your phone must be able to open an HTTPS address of this
  computer." It offers two easy paths: (1) install Tailscale on PC + phone (free) and rerun, or
  (2) give any other HTTPS URL (reverse proxy / tunnel / existing public address). You pick.

**Case C – no URL and no opencode installed.** The agent guides installing opencode (one command from
https://opencode.ai, e.g. `npm i -g opencode-ai`), starting the service (`opencode serve --service`),
then returns to Case B. Or you can supply an existing URL from another machine.

### Step 2 – Phone side

The agent looks for an adb device (USB or network). If it finds exactly one, it uses it. If it finds
several, it lists them and asks which one is the phone.

If none is found, the agent walks you through one of these:

- **USB (simplest).** On the phone: Settings → About phone → tap **Build number** 7× (unlocks
  Developer options) → Settings → System → Developer options → enable **USB debugging** → plug the
  phone into the computer with a USB cable → on the phone, accept the "Allow USB debugging?" RSA
  dialog. Then say **"done"**; the agent re-checks.
- **Wireless.** On the phone: Developer options → **Wireless debugging** → enable it → **Pair device
  with pairing code** → tell the agent the `IP:port` and the 6-digit code it shows. The agent pairs
  (`adb pair`) and connects automatically.
- **No adb on this machine.** The agent gives the install command for adb first, then the above.

### Step 3 – App

The agent checks whether OC Remote is installed (`pm list packages` → `io.github.mirkotappini.opencoderemote`).

- **Already installed** → moves on.
- **Not installed** →
  - If the source repo exists locally (default `~/projects/opencode-remote`) → the agent builds it
    (`./gradlew assembleDebug`) and installs the APK automatically.
  - Otherwise → the agent offers both options and lets you pick:
    - download the latest APK from the project's GitHub Releases page (it gives the link), or
    - clone the repo and let the agent build + install.
    After you download it, it installs the APK on the phone.

### Step 4 – Launch & configure

The agent launches the app, waits, and inspects the screen:
- If the **setup screen** is shown (server address / user / password fields), it fills in the values
  from Step 1 and taps **Connect**.
- If the setup screen is **not** shown, the app is already configured — it goes straight to verification.

### Step 5 – Verify

The agent re-checks the screen:
- **Success**: the session list appears (fields gone). It tells you: "Connected — you can now manage
  your OpenCode sessions from the phone."
- **Failure**: an error banner is shown. The agent diagnoses in plain terms: phone cannot reach the
  URL (check the phone has internet/network access to it and opens it in a browser), wrong credentials,
  or server version mismatch — and offers to retry with corrected values.

---

## Power-user notes (skip if you're new)

Environment variables override the interactive prompts and are handy for scripting:

| Variable | Meaning |
|---|---|
| `OC_REMOTE_URL` | HTTPS base URL of the OpenCode server as reachable from the phone. |
| `OC_REMOTE_USER` | Service username (normally `opencode`). |
| `OC_REMOTE_PASSWORD` | Service password/token (never logged). |
| `OC_REMOTE_DEVICE` | adb target: serial (e.g. `R58M1234567`) or `host:port` (e.g. `192.168.1.50:5555`). |
| `OC_REMOTE_ADB_HOST` | SSH host on which `adb` sees the phone (e.g. your workstation with USB). Used when local adb finds nothing. |
| `OC_REMOTE_APK` | Path to the APK to install. Default: `<repo>/app/build/outputs/apk/debug/app-debug.apk` if a repo is present; otherwise the app is assumed to be installed already. |

Technical details the agent needs:

- Typing into fields: `adb shell input text` replaces spaces with `%s`; the three setup fields are, in
  UI order: server URL, user, password. Tap **Connect** (label `Connect` or legacy `Connetti`).
- UI inspection: `adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml`
- Uninstall-if-stale: `adb uninstall io.github.mirkotappini.opencoderemote` then reinstall.
- Verify URL: `curl -skI <url>/api/info` (200/401/403 = TLS OK).
- Never print the password in output.
