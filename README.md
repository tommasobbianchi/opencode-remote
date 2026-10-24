# OC Remote

An Android client to connect to **your own** OpenCode server (2.x) and manage
its sessions from your phone.

The app has no pre-configured server, account, or preset models: on first launch
you enter the address of your OpenCode server and the service credentials.

## What you can do

- session list, new session, opening the chat
- prompt and response in streaming, with a live view of the turn: reasoning with
  duration, tools with command and output, text in streaming
- interrupting the turn, automatic reconnection with re-synchronization
- permission approvals, with notifications and actions
- server agents / skill / MCP panel, model selection
- changing server from the list (the encrypted credentials are deleted)

## Requirements

- an OpenCode **2.x** server with the HTTP service running
- an **HTTPS** endpoint reachable from the phone (for example with
  `tailscale serve`, so the certificate is valid)
- the OpenCode service username and password (typically the user is `opencode`)

## First launch

1. open the app and enter the server address, for example
   `https://machine.tailxxxx.ts.net`
2. enter the service username and password
3. connect: the credentials are saved encrypted (Android Keystore) and
   reused at restart

## Build

Requires JDK 17 and Android SDK 36 (compile/target 36, min 29).

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

## Privacy

The app only talks to the configured server: no third-party services, no
telemetry. Credentials stay on the phone, encrypted with the Android Keystore;
traffic is only allowed over HTTPS.

## Licenses

Fonts included: Space Grotesk, IBM Plex Sans, JetBrains Mono (SIL Open Font
License). The full license note still needs to be added to the repository.
