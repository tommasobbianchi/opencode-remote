# Contratto API per M1 (verificato sul server 2.1.3, 23 ott 2026)

Estratto da `GET /openapi.json` (121 path) e da risposte reali sul server di sviluppo.
Fixture reali (non committare): `/tmp/opencode/ocm/{sessions.json,msgs.json,openapi2.json}`.

Auth: `Authorization: Basic base64(utente:password)`; tutte le risposte sono JSON con
involucro `{ "data": ... }` (tranne dove indicato). Timestamp in **epoch millisecondi**.

## GET /api/session

Query: `limit` (string), `order` = `asc|desc`, `search`, `parentID`, `archived`,
`directory`, `project`, `subpath`, `cursor`.

```json
{"data":[{
  "id":"ses_...", "parentID":null, "projectID":"9884...",
  "agent":"orchestrator",
  "model":{"id":"deepseek-flash","providerID":"deepseek","variant":"default"},
  "cost":0.000861444,
  "tokens":{"input":5590,"output":12,"reasoning":0,"cache":{"read":5248,"write":0}},
  "outcome":"succeeded",                       // succeeded | failed | interrupted
  "time":{"created":1792791042489,"updated":1792791043724,"idle":...,"viewed":...,"archived":null},
  "title":"Greeting",
  "location":{"directory":"/Users/<utente>"}
}],
 "cursor":{"previous":null,"next":"<token>"}}
```

Campi opzionali: `fork`, `subpath`, `metadata`, `permissions`, `revert`.
Lista reale: 5 sessioni top-level, `parentID` null.

## GET /api/session/{sessionID}/message

Query: `limit`, `order` = `asc|desc`, `cursor`, `type`.
Risposta: `{"data":[...],"cursor":{"previous":"<tok>","next":"<tok>"}}`.
Con `order=desc` il primo elemento è il più recente; `cursor.previous` serve per
caricare i messaggi più vecchi.

I messaggi sono **polimorfici su `type`**:

```jsonc
// utente
{"id":"msg_...","time":{"created":1792705642259},"type":"user",
 "text":"...", "files":[], "agents":[]}

// assistente: il testo sta nelle part di content
{"id":"msg_...","time":{...},"type":"assistant",
 "agent":"orchestrator",
 "model":{"id":"deepseek-flash","providerID":"deepseek","variant":"default"},
 "content":[ {"type":"reasoning","text":"...","native":...,"time":{...}},
             {"type":"text","text":"..."},
             {"type":"tool","id":"...","name":"bash","executed":true,"state":{...},"time":{...}} ],
 "finish":"stop",            // stop | tool-calls | ...
 "rawFinish":..., "cost":0.00114405, "tokens":{...}}

// macchina a stati della sessione
{"id":"msg_...","time":{...},"type":"idle","outcome":"..."}
```

Altri `type` possibili (contratto OpenAPI): `agent-switched`, `model-switched`,
`location-switched`, `synthetic`, `shell`, `skill`, `error`, vari `status`.
Regola: **ignorare con eleganza** ogni type/part sconosciuto (non far crashare il parser).

## GET /api/session/active

Risposta reale senza sessione attiva: `{"data": {}}` (oggetto vuoto, non `null`).
Trattare `data` vuoto come "nessuna sessione attiva".

## GET /api/session/{sessionID}/permission  (per M4)

`{"data":[{"id":"...","sessionID":"...","action":"...","resources":["..."],
"save":["..."],"metadata":{},"source":{"type":"tool","messageID":"...","id":"..."},
"message":"..."}]}`

## Note di rendering per M1

- Titolo sessione: `title`; se vuoto/assente → `id`.
- Riga secondaria: `agent` · `model.id` · età relativa da `time.updated` · `cost` (USD, 4 decimali).
- Badge stato: `outcome` (succeeded/failed/interrupted) oppure "attiva" se presente in `/api/session/active`.
- Chat: solo le part `text` rendono markdown-ish (M1: testo semplice, righe preservate);
  `reasoning` collassato ("ragionamento · N righe"); `tool` come chip `tool: <name>` apribile
  per mostrare `state` (JSON troncato).
- `idle`/`status` → divisore sottile, non una bolla.
