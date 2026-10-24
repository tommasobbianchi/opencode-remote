# M5 — checklist di hardening

Criterio del design: *test di rete persa, token scaduto, server spento*.
Ogni voce ha un esito atteso verificabile; le voci marcate **[dev]** si provano sul Fold
via `adb` (USB dal MacBook), le altre sono coperte da test JVM.

## A. Connettività

| # | Scenario | Comportamento atteso | Verifica |
|---|---|---|---|
| A1 | Wi-Fi spento con app aperta e SSE viva | banner "rete assente · attendo la connessione", tentativi sospesi (nessun backoff/numero), nessun crash | [dev] |
| A2 | Wi-Fi riacceso | riconnessione immediata (callback di rete), backoff azzerato, rilettura messaggi, nessun duplicato | [dev] |
| A3 | Wi-Fi spento per > 2 min (Doze simulato) | al ritorno si riallinea da sola; se l'app era in background, al foreground riparte | [dev] |
| A4 | Nessuna rete all'avvio (solo Tailscale spento) | schermata con errore "rete privata non attiva", pulsante Riprova; nessun crash | [dev] |

## B. Server

| # | Scenario | Comportamento atteso | Verifica |
|---|---|---|---|
| B1 | servizio OpenCode fermo su patchouli | stato offline con messaggio chiaro; i dati in cache restano visibili; nessun retry aggressivo | [dev] |
| B2 | servizio riavviato | riconnessione automatica entro il backoff; la sessione aperta si riallinea | [dev] |
| B3 | macchina in sleep / non raggiungibile | timeout mappato su "server non raggiungibile (timeout)", non su un errore generico | test + [dev] |
| B4 | versione server ≠ 2.x | schermata bloccante `VersionMismatch` con versione trovata e spiegazione; nessuna richiesta ulteriore | test + [dev con server finto] |

## C. Credenziali

| # | Scenario | Comportamento atteso | Verifica |
|---|---|---|---|
| C1 | password cambiata sul server (401) | stato `AuthFailed`, stop dei retry, invito a rifare il pairing; cache visibile | [dev] |
| C2 | token cancellato/disconnesso | il servizio in background si ferma, notifiche rimosse, setup mostrato | [dev] |
| C3 | 401 durante un invio prompt | il prompt non viene ritentato in automatico; messaggio chiaro; nessun duplicato inviato | test + [dev] |

## D. Dati

| # | Scenario | Comportamento atteso | Verifica |
|---|---|---|---|
| D1 | sessione cancellata mentre è aperta | messaggio + ritorno alla lista (padre se figlia), nessun crash | test + [dev] |
| D2 | risposte grandi (`/api/skill` ~98 KB, `/api/agent` 26 KB) | nessun blocco UI; parsing fuori dal main thread | test |
| D3 | cursore di paginazione scaduto (400) | messaggio "pagina non più valida" + refresh, non crash | test |
| D4 | eventi fuori ordine / duplicati | stato coerente (i messaggi sono la verità), nessun doppio testo | test (già coperto in parte) |

## E. Risorse

| # | Scenario | Comportamento atteso | Verifica |
|---|---|---|---|
| E1 | SSE aperta 10 min in foreground | nessuna crescita monotona di memoria, CPU a riposo bassa | [dev] `dumpsys meminfo` |
| E2 | animazioni attive su agenti in esecuzione | nessun consumo anomalo | [dev] |
| E3 | nessun segreto nei log | grep su logcat: password/token mai presenti | [dev] |

## Note di prova

- Wi-Fi: `adb shell svc wifi disable|enable` (USB resta attivo).
- Servizio: `ssh patchouli 'opencode service stop|start'`; le sessioni restano nel DB.
- Password: `opencode service set password <x>` + `restart` per simulare la revoca, poi ripristino.
