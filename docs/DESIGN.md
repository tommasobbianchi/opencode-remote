# Client Android per OpenCode remoto — documento di progetto

Stato: bozza di design, nessun codice scritto.
Data: 22 ottobre 2026.
Target: `patchouli` (macOS, OpenCode 2.1.3).

---

## 1. Obiettivo

Controllare da Android le sessioni OpenCode che girano su patchouli, agganciandosi al
**server già in esecuzione** (non a un'istanza indipendente), così da vedere e
continuare le stesse sessioni che si usano dalla TUI.

Requisito dichiarato dall'utente: un pannello laterale (swipe dal bordo destro verso
sinistra) che mostri l'elenco in stile sidebar OMO — sessioni, agenti, skill, MCP,
stato dei task.

### Non-obiettivi (prima versione)

- Terminale remoto (per quello esiste già Moshi).
- Editor di file, gestione worktree, PTY, settings avanzati.
- Replica del companion animato o delle animazioni della TUI.
- Invocazione diretta di singoli tool: si passa da prompt, comando slash o skill.
- Funzionamento senza Tailscale attivo.

---

## 2. Vincoli tecnici verificati

Fonte: ricognizione read-only del 22/10/2026 su patchouli.

| Vincolo | Dettaglio |
|---|---|
| Versione server | OpenCode 2.1.3, API V2 (`/api/*`) |
| Trasporto | server su `127.0.0.1:49374`; esposto dal 22/10/2026 via `tailscale serve` su `https://patchouli.tail2897ec.ts.net` (solo tailnet, TLS valido) |
| Autenticazione | HTTP Basic, utente `opencode`, password/token come password |
| Pairing | `POST /api/pair` → codice monouso; `GET /auth/connect/{code}` con `Accept: application/json` → token da usare come password |
| Invio messaggio | `POST /api/session/{id}/prompt` è **asincrono**: risponde con un ack (`Session.Inbox.User`), non con l'output |
| Streaming | `GET /api/event` → SSE. **Live-only, nessun replay.** Heartbeat periodico |
| Recupero storico | `GET /api/session/{id}/message`; per precisione `GET /api/experimental/session/{id}/log?seq=<n>&follow=true` |
| Approvazioni | `GET /api/session/{id}/permission`, `POST .../permission/{requestID}/reply` con `once` / `always` / `reject` |
| Agenti | `GET /api/agent`, `POST /api/session/{id}/agent` |
| Modelli | `GET /api/model`, `POST /api/session/{id}/model` |
| Skill / comandi | `GET /api/skill`, `GET /api/command`, `POST /api/session/{id}/command` |
| MCP | `GET /api/mcp` (stato connessione) |
| Salute / versione | `GET /api/info` → version, pid, urls, capabilities |
| Porta gestita | **fissa a 49374** di default (`opencode service set port` per cambiarla). Una sola istanza gestita per porta: una seconda istanza isolata va in conflitto, non ne parte un'altra |
| Cose solo TUI | Companion, sidebar, tab, palette, statusline, animazioni |

Nota: `1.18.35` è la versione del pacchetto `@opencode-ai/plugin`, **non** del runtime.
Un client costruito sul contratto V1 non funzionerebbe qui.

---

## 3. Decisioni da chiudere prima di scrivere codice

### D1 — Ciclo di vita del server — ✅ RISOLTA (22/10/2026)

**Esito: il servizio sopravvive all'uscita del client che lo ha avviato.**

Verifiche:

1. Il servizio gira in **sessione e process group propri** (`PGID = PID`, `STAT Ss`,
   `TTY ??`): è stato avviato con `setsid`, quindi un SIGHUP alla finestra non lo
   raggiunge.
2. Test in sandbox isolata (XDG dirs temporanee, porta 49375): un client effimero ha
   avviato il servizio e poi è uscito. Il servizio è passato a **PPID 1**, ha
   mantenuto sessione e group propri, e rispondeva `HTTP 200` su `/api/info` dopo
   10 secondi.
3. Nel contesto che la TUI riceve dal server, `service` espone solo `reconnect` e
   `restart`: **non esiste un `stop`** invocabile dal client all'uscita.

Conseguenze:

- Non serve `opencode service start`: il servizio nasce con la prima TUI e resta.
- Il client Android può collegarsi anche a TUI chiusa, a patto che la macchina sia
  accesa e il servizio non sia stato fermato esplicitamente.
- Resta un banner di stato nell'app: se il servizio è stato fermato o la macchina
  dorme, la connessione fallisce e va mostrato un messaggio chiaro.
- Non testato direttamente: chiusura della TUI reale (avrebbe terminato la sessione
  di lavoro). L'evidenza strutturale e il test sandbox coprono lo stesso percorso.

### D2 — Trasporto e autenticazione — ✅ ATTIVA (22/10/2026)

Configurazione in essere:

```
tailscale serve --bg 49374
→ https://patchouli.tail2897ec.ts.net  (tailnet only)
   proxy → http://127.0.0.1:49374
```

Verifiche eseguite:

| Test | Esito |
|---|---|
| `GET https://…/api/info` senza credenziali | **401** — l'auth non è aggirata dal proxy |
| Stesso endpoint con Basic `opencode:<password>` | **200**, `version 2.1.3`, `pid 37490` |
| Certificato TLS | valido (`ssl_verify_result = 0`), IP `100.106.115.79` (tailnet) |
| `tailscale funnel status` | nessuna esposizione pubblica: "tailnet only" |

Regole da mantenere:

- **Mai** `tailscale funnel`, **mai** `opencode pair --remote` (espongono a internet).
- Token di pairing al posto della password permanente del servizio.
- Il server continua ad ascoltare solo su loopback: l'unica porta "aperta" è quella
  di Tailscale, e solo nel tailnet.
- Disattivazione: `tailscale serve --https=443 off`.

### D3 — Perimetro MVP

Drawer + chat + approvazioni + switch agente/modello. Il resto dopo.

---

## 4. Architettura dell'app

```
UI (Compose)
  └─ ViewModel (StateFlow)
       └─ Repository
            ├─ OpenCodeApi      (REST, OkHttp + kotlinx.serialization)
            ├─ EventStream      (OkHttp SSE, foreground service)
            └─ LocalCache       (Room)
```

- **Kotlin**, **Jetpack Compose** + Material 3, minSdk 29, target 36.
- **OkHttp** + `okhttp-sse`; **kotlinx.serialization** con modelli generati
  dall'OpenAPI (`/openapi.json`, 146 operazioni, 293 schemi) — solo modelli e
  interfacce, il client HTTP resta scritto a mano.
- **Room** per cache di sessioni e messaggi (apertura istantanea, lettura offline).
- **DataStore + Android Keystore** per il token.
- **Foreground service** (`dataSync`) che tiene l'SSE e mostra le notifiche.

Separazione netta: `EventStream` produce eventi normalizzati in un `Flow`; i
ViewModel non conoscono né SSE né JSON.

---

## 5. Modello dati

Entità locali (Room):

```
ServerConfig   id, label, baseUrl, username="opencode", tokenRef, lastSeenAt, version
SessionRow     id, projectId, title, agent, modelProvider, modelId, costUsd,
               tokensIn/Out/Reasoning, state (running|idle), createdAt, updatedAt, lastSeq
MessageRow     id, sessionId, role, createdAt, partsJson, seq
AgentRow       id, name, mode, provider, model, variant        (cache, TTL)
SkillRow       id, name, description, path                      (cache, TTL)
McpRow         name, status                                    (cache, TTL)
ApprovalRow    id, sessionId, action, resourcesJson, message, status
```

Stato UI (non persistito):

```
DrawerState    isOpen, tab (Sessioni|Agenti|Skill|MCP)
ChatState      messages, streamingText, isBusy, pendingApprovals, error
Connection     Disconnected | Connecting | Connected | AuthFailed | VersionMismatch
```

Il drawer legge da `SessionRow` + `AgentRow` + `SkillRow` + `McpRow`: nessuna
chiamata bloccante all'apertura, refresh in background.

---

## 6. Schermate

1. **ServerSetup** — nome, URL (`https://patchouli.tail2897ec.ts.net`), stato
   connessione, pulsante "Pair".
2. **Pairing** — scanner QR del link `/auth/connect/{code}` generato dalla TUI
   (`opencode pair` / comando `/pair`), oppure incolla-link. Al successo salva il
   token nel Keystore e fa `GET /api/info` per validare versione.
3. **SessionList** — lista sessioni con stato, costo, agente; pull-to-refresh;
   "nuova sessione" (agente + modello).
4. **Chat** — timeline messaggi, streaming, input con allegati, pulsante interrupt,
   badge approvazioni.
5. **Drawer laterale destro** — vedi §7.
6. **ApprovalsSheet** — richiesta permesso con Once / Always / Reject.
7. **Settings** — server, token (revoca), tema, diagnostica (versione, ping, stato SSE).

Navigazione: `Scaffold` + `ModalNavigationDrawer` con drawer **sul lato end**
(destro), apertura via edge-swipe da destra verso sinistra e via icona. La gesture
nativa di sistema va rispettata: usare `gesturesEnabled` e una soglia laterale per
non entrare in conflitto con il back gesture.

---

## 7. Il drawer "sidebar OMO"

Contenuto, con la fonte dati di ciascun blocco:

| Blocco | Dati | Endpoint |
|---|---|---|
| Sessioni | titolo, agente, stato, costo, tokens | `GET /api/session`, `GET /api/session/active` |
| Agenti | nome, modo, modello, variant | `GET /api/agent` |
| Skill | nome, descrizione | `GET /api/skill` |
| MCP | nome, stato connessione | `GET /api/mcp` |
| Progetto/location | directory corrente | `GET /api/location` |
| Task/subagenti | stato sintetico | eventi SSE + `Session.Info` |

Interazioni: tap su sessione → apre la chat; tap su agente → `session.switchAgent`;
tap su skill → attiva (`POST /api/experimental/session/{id}/skill`) o la inserisce
nel prompt; tap su MCP → dettaglio sola lettura.

Aggiornamento: gli agenti cambiano raramente → cache con TTL 5 minuti; le sessioni
si aggiornano sugli eventi e su pull-to-refresh.

---

## 8. Flusso di connessione

### 8.1 Primo avvio (pairing)

1. Sulla macchina: `opencode pair --url https://patchouli.tail2897ec.ts.net`
   (stampa link monouso + QR).
2. Sull'app: scansione QR → `GET {link}` con `Accept: application/json`.
3. Il server risponde con il token → salvataggio nel Keystore.
4. `GET /api/info` → confronto versione con la minima supportata (2.x).
5. `GET /api/session` → popolamento cache.

Se il token scade o viene revocato: 401 → stato `AuthFailed` → si rifà il pairing.

### 8.2 Avvio normale

```
GET /api/info            → versione, pid, capabilities
GET /api/session?limit=50&order=desc  → lista
GET /api/session/active  → eventuale sessione in corso
GET /api/event (SSE)     → live
```

### 8.3 Invio di un prompt

```
POST /api/session/{id}/prompt
{ "text": "...", "delivery": "steer" | "queue", "files": [...] }
→ 200 { data: Session.Inbox.User }   // solo ack
```

L'output arriva esclusivamente dagli eventi. UI: messaggio utente ottimistico,
indicatore "in coda" finché non arriva il primo evento della sessione.

### 8.4 Riconnessione e recupero (punto critico)

L'SSE non ha replay: ogni disconnessione è una potenziale perdita di eventi.

Algoritmo:

1. Su `onFailure`/`onClosed`: stato `Connecting`, backoff esponenziale
   (1s → 2s → 4s → … max 30s) con jitter.
2. Alla riconnessione: `GET /api/event` di nuovo **e** rilettura completa di
   `GET /api/session/{id}/message` per la sessione aperta.
3. ~~Colmare il buco con
   `GET /api/experimental/session/{id}/log?seq={lastSeq}&follow=true`.~~ **Corretto il
   23/10/2026:** quell'endpoint non riproduce la storia — risponde subito con
   `log.synced` e la seq corrente, poi resta in ascolto. Va usato solo come ping di
   allineamento; il recupero è sempre la rilettura dei messaggi (punto 2). Vedi
   `docs/API-M2.md` §4.
4. Deduplicazione per `messageID` e per `event.id`.
5. Timeout heartbeat: se non arriva nulla per 30s, considerare la connessione morta e
   riconnettere. La perdita di rete è rilevata subito dal `NetworkCallback` del
   `ConnectivityManager` (tentativi sospesi finché la rete non torna).

Regola d'oro: **la UI non deve mai dipendere dall'ordine degli eventi**; i messaggi
sono la verità, gli eventi sono un acceleratore.

### 8.5 Background

Foreground service con notifica persistente e SSE attiva. Android può sospendere la
rete in Doze: al risveglio si applica §8.4. Senza FCM non c'è garanzia di notifica
immediata a processo ucciso — limite accettato e documentato.

---

## 9. Approvazioni

- Sorgente: `GET /api/session/{id}/permission` + eventi.
- Notifica locale con tre azioni: Once / Always / Reject.
- Risposta: `POST /api/session/{id}/permission/{requestID}/reply`.
- `Always` ha effetto permanente lato server: va mostrato con conferma esplicita,
  mai come default.
- Se la richiesta è scaduta (sessione interrotta), la risposta restituisce errore:
  gestire con messaggio "richiesta non più valida" e refresh.

---

## 10. Gestione errori

| Situazione | Stato UI | Azione |
|---|---|---|
| 401 | `AuthFailed` | proponi nuovo pairing |
| Versione server ≠ 2.x | `VersionMismatch` | blocca, spiega |
| Tailscale assente | `Disconnected` | banner "rete privata non attiva" |
| Server non raggiungibile | `Disconnected` | retry con backoff |
| Sessione cancellata | torna alla lista | toast |
| SSE chiuso dal server | `Connecting` | §8.4 |

---

## 11. Sicurezza

- `cleartextTrafficPermitted=false`; solo HTTPS (Tailscale serve).
- Token nel Keystore, mai in log, mai in Room.
- Nessun log di header, prompt o contenuto file.
- Revoca: cancellazione token + nuovo pairing.
- Superficie esposta: solo il tailnet; nessun funnel, nessuna porta pubblica.
- Il QR contiene un token monouso: va mostrato solo sulla macchina, non condiviso.

---

## 12. Milestone

| M | Contenuto | Criterio di accettazione |
|---|---|---|
| M0 | Spike: Tailscale serve + pairing + `GET /api/info` + SSE | Da telefono vedo versione e ricevo almeno un evento |
| M1 | Lista sessioni + chat read-only + storico | Apro una sessione e leggo la conversazione |
| M2 | Invio prompt + streaming + riconnessione | Il testo arriva in tempo reale e sopravvive a un cambio rete |
| M3 | Drawer OMO + switch agente/modello + interrupt | Lo swipe apre il pannello e i dati sono corretti |
| M4 | Approvazioni con notifiche | Approvo da notifica senza aprire l'app |
| M5 | Hardening | Test di rete persa, token scaduto, server spento |

---

## 13. Rischi

1. ~~**Ciclo di vita del servizio** (D1)~~ — risolta: il servizio sopravvive
   all'uscita del client. Resta il caso "servizio fermato o macchina in sleep", da
   coprire con un banner di stato.
2. **SSE senza replay**: buchi nei messaggi se la riconnessione è mal gestita.
   Mitigazione: §8.4 e deduplicazione.
3. **Endpoint `experimental`**: possono cambiare. Isolarli dietro un adapter con
   fallback.
4. **Doze / batteria**: notifiche non garantite a processo ucciso.
5. **Deriva del contratto**: OpenCode 2.x evolve; fissare una versione minima e
   validarla con `/api/info` all'avvio.

---

## 14. Domande ancora aperte

- Le ACL tailnet sono già restrittive o vanno impostate?
- Il drawer deve mostrare anche i task in background con dettaglio, o solo un badge?
- Serve la modalità offline in sola lettura, o è sufficiente la cache per l'avvio?
