# Contratto API per M2 — prompt, streaming, riconnessione

Verificato dal vivo il 23 ott 2026 sul server di sviluppo (OpenCode **2.1.4**), con una sessione usa-e-getta
poi cancellata (`DELETE /api/session/{id}` → 204). Nessun dato dell'utente in questo documento.
Fixture grezze: `/tmp/opencode/ocm/sse_run.jsonl` (15 eventi di un turno minimo).

## 1. Invio prompt

`POST /api/session/{sessionID}/prompt`, `Content-Type: application/json`

```json
{"text":"...","delivery":"steer","files":[],"agents":[],"skills":[]}
```

- `text` obbligatorio. `delivery`: `steer` (default di fatto) | `queue`.
- Risposta **200**: ack immediato, non l'output:
  `{"data":{"id":"msg_...","sessionID":"ses_...","time":{"created":...},"type":"user","payload":{"text":"..."},"delivery":"steer"}}`
- Errori: 400, 401, 404, 409 (ConflictError), 500.

L'output arriva **solo** dalla SSE. La UI deve mostrare il messaggio utente in modo ottimistico
e uno stato "in coda" finché non arriva `session.execution.started`.

## 2. SSE degli eventi

`GET /api/event`, `text/event-stream`, **globale** (tutte le sessioni: filtrare per
`data.sessionID`), **live-only senza replay**.
Frame: `data: {json}` + `: heartbeat` periodici (ignorare le righe che iniziano con `:`).

Envelope reale:

```json
{"id":"evt_...","created":1792792392493,"type":"session.text.delta",
 "location":{"directory":"/Users/<utente>"},
 "data":{"sessionID":"ses_...","assistantMessageID":"msg_...","ordinal":0,"delta":"pong"},
 "durable":{"aggregateID":"ses_...","seq":6,"version":1}}
```

`durable.seq` è monotono **per sessione**; `durable.aggregateID` è la sessionID.

### Sequenza reale di un turno minimo (ordine di arrivo)

| # | type | data utile |
|---|---|---|
| 1 | `server.connected` | `{}` — connessione stabilita |
| 2 | `session.created` | `sessionID, slug, version, projectID, location, title` |
| 3 | `project.updated` | `id, canonical, time` |
| 4 | `session.inbox.enqueued` | `inboxID, sessionID, item:{type:"user",payload:{text},delivery}` |
| 5 | `session.execution.started` | `sessionID` → **sessione occupata** |
| 6 | `session.instructions.updated` | `delta` (hash) — ignorare |
| 7 | `session.inbox.delivered` | `inboxID` — il prompt è stato preso in carico |
| 8 | `session.step.started` | `agent, model, assistantMessageID, started` |
| 9 | `session.text.started` | `assistantMessageID, ordinal` |
| 10..n | `session.text.delta` | `assistantMessageID, ordinal, delta` → **accodare il testo** |
| n+1 | `session.text.ended` | `assistantMessageID, ordinal, text` (testo finale della part) |
| n+2 | `session.step.streamed` | `assistantMessageID` — checkpoint, ignorabile |
| n+3 | `session.step.ended` | `assistantMessageID, finish, rawFinish, cost, tokens` |
| n+4 | `session.usage.updated` | `cost, tokens` — aggiornare il costo della sessione |
| n+5 | `session.execution.succeeded` | `sessionID` → **sessione libera** |

Varianti attese ma non catturate (stessa forma, `data.sessionID` presente): `session.execution.failed`,
`session.execution.interrupted`, `session.reasoning.*`, `session.tool.*`, `session.permission.*`,
`session.compacted`, `session.removed`, `session.title.updated`.
Regola: **type sconosciuto → si logga e si ignora**, mai crash.

Nota: `ordinal` identifica la part `text` dentro il messaggio assistente; più `ordinal` = più part
(testo intervallato da tool). Il rendering M2 può concatenare per `ordinal` crescente.

## 3. Interruzione

`POST /api/session/{sessionID}/interrupt` (query opzionale `resume=true|false`) → 200
`SessionInterruptResponse`. Dopo l'interrupt arriva `session.execution.interrupted`.

## 4. Riconnessione — correzione al DESIGN

Il punto §8.4.3 del DESIGN ("colmare il buco con
`GET /api/experimental/session/{id}/log?seq={lastSeq}&follow=true`") **non funziona come scritto**.
Verificato: con `seq=0` e con `seq=5` l'endpoint risponde **subito** con
`data: {"type":"log.synced","aggregateID":"<sid>","seq":10}` — cioè annuncia solo la seq corrente e,
con `follow=true`, resta in attesa di eventi nuovi. **Non riproduce la storia.**

Quindi la strategia corretta:

1. Su `onFailure`/`onClosed`: stato `Connecting`, backoff esponenziale 1→2→4…max 30s con jitter.
2. Alla riconnessione: **rilettura completa** di `GET /api/session/{id}/message?order=asc`
   (o `desc` + reverse) della sessione aperta; i messaggi sono la verità.
3. `GET /api/experimental/session/{id}/log?seq={lastSeq}&follow=true` si può usare solo come
   **ping di allineamento** (leggere `log.synced.seq` per sapere se si è perso qualcosa), non per
   recuperare gli eventi.
4. Deduplicazione per `messageID`; durante lo streaming, il delta si applica solo se
   `assistantMessageID` non è già chiuso da `session.text.ended` / `session.step.ended`.
5. Heartbeat: nessun dato per 90s ⇒ connessione morta ⇒ riconnetti.

## 5. Stato sessione

- `session.execution.started` → running
- `session.execution.succeeded|failed|interrupted` → idle (con esito)
- `GET /api/session/active` → `{"data":{}}` se nessuna sessione attiva
- `GET /api/session/{id}` → `Session.Info` con `outcome`, `cost`, `tokens` aggiornati
