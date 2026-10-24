# OC Remote

Client Android per collegarsi al **proprio** server OpenCode (2.x) e gestirne le
sessioni dal telefono.

L'app non ha server, account o modelli preimpostati: al primo avvio si inserisce
l'indirizzo del proprio server OpenCode e le credenziali del servizio.

## Cosa si può fare

- lista delle sessioni, nuova sessione, apertura della chat
- prompt e risposta in streaming, con vista live del turno: ragionamento con
  durata, tool con comando e output, testo in streaming
- interruzione del turno, riconnessione automatica con risincronizzazione
- approvazioni dei permessi, con notifica e azioni
- pannello agenti / skill / MCP del server, scelta del modello
- cambio server dalla lista (le credenziali cifrate vengono cancellate)

## Requisiti

- server OpenCode **2.x** con il servizio HTTP attivo
- endpoint **HTTPS** raggiungibile dal telefono (per esempio con
  `tailscale serve`, così il certificato è valido)
- utente e password del servizio OpenCode (di norma l'utente è `opencode`)

## Primo avvio

1. apri l'app e inserisci l'indirizzo del server, per esempio
   `https://macchina.tailxxxx.ts.net`
2. inserisci utente e password del servizio
3. connetti: le credenziali vengono salvate cifrate (Android Keystore) e
   riusate al riavvio

## Build

Serve JDK 17 e Android SDK 36 (compile/target 36, min 29).

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

## Privacy

L'app parla solo con il server configurato: nessun servizio terzo, nessuna
telemetria. Le credenziali restano sul telefono, cifrate con l'Android Keystore;
il traffico è ammesso solo su HTTPS.

## Licenze

Font inclusi: Space Grotesk, IBM Plex Sans, JetBrains Mono (SIL Open Font
License). La nota di licenza completa va aggiunta al repository.
