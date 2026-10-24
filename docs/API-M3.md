# Contratto API per M3 — drawer OMO (agenti, skill, MCP, modelli)

Verificato sul server di sviluppo 2.1.4 il 24 ott 2026 con chiamate reali (dati in `/tmp/opencode/ocm/*.json`).

Tutti gli endpoint sotto `/api/*` richiedono Basic auth e rispondono con involucro `{data:...}`
salvo dove indicato. Le POST di modifica rispondono **204 No Content**.

## Cataloghi (lettura)

### GET /api/agent — 10 agenti
```json
{"location":{"directory":"/Users/<utente>"},"data":[{
  "id":"orchestrator","name":"orchestrator",
  "model":{"id":"deepseek-flash","providerID":"deepseek","variant":"default"},
  "request":{"settings":{},"headers":{},"body":{}},
  "description":"AI coding orchestrator that delegates…",
  "mode":"primary|subagent|…","hidden":false,
  "permissions":[...],
  "system":"<4,7k caratteri di prompt>"
}]}
```
- **Ignorare `system`** nel drawer (peso inutile); usare `id, name, description, mode, hidden, model`.
- Filtrare `hidden == true` (compaction, title, summary) e **non** mostrare `mode == "subagent"` nella lista principale (o in sezione separata).
- Il modello per-agente è opzionale (`model` assente su build/plan).

### GET /api/skill — 16 skill
`{"data":[{"id","name","description","path","content"}]}` — `content` può essere grande: **non
richiederlo per la lista** (l'endpoint lo restituisce comunque, scartarlo in parsing).

### GET /api/mcp — 3 server
`{"data":[{"name":"context7","status":{"status":"connected"}}]}` — stati osservati: `connected`.
Altri stati possibili (dal contratto): `disabled`, `failed`, `needs_auth`… trattare come stringa.

### GET /api/location
`{"directory":"/Users/<utente>","project":{"id":"…","directory":"…","canonical":"…"}}`

### GET /api/model — 90 modelli (⚠️ sicurezza)
Ogni voce ha `id, modelID, providerID, family, name, capabilities, settings, …` e **`settings.apiKey`
può contenere una chiave reale del provider**. Regola: parsare **solo** `id, modelID, providerID,
name, family, capabilities.input/output`; **scartare `settings`** e non loggarlo mai. Non persistere.

## Azioni

| Azione | Chiamata | Body | Esito |
|---|---|---|---|
| Cambia agente | `POST /api/session/{id}/agent` | `{"agent":"fixer"}` | 204 |
| Cambia modello | `POST /api/session/{id}/model` | `{"model":{"id":"deepseek-flash","providerID":"deepseek","variant":"default"}}` | 204 |
| Attiva skill | `POST /api/experimental/session/{id}/skill` | `{"id":"opencode","resume":null}` | 204 |
| Comando slash | `POST /api/session/{id}/command` | `{"name":"review","text":"…"}` (`text` obbligatorio) | 204 |
| Interrompi | `POST /api/session/{id}/interrupt` | — | 200 |
| Sessione in background | `POST /api/session/{id}/background` | — | 204 (semantica non documentata: **non usarla in M3**) |

`Agent.ID` e `Skill.ID` sono stringhe; `Model.Ref` richiede `id` + `providerID`, `variant` opzionale.

## Sessioni figlie (task in background)

`GET /api/session?parentID={sessionID}` → stessa forma di `/api/session`.
Osservato: `{"data":[],"cursor":{...}}` quando non ci sono figli.
Il campo `parentID` **non** compare nell'oggetto sessione restituito dalla lista top-level: per
sapere se una sessione ha figli si interroga per `parentID` (una chiamata per sessione aperta è
sufficiente) oppure si osservano gli eventi `session.created` con `data.parentID`.

## Comandi slash

`GET /api/command` → 9 voci `{name, description}` (init, review, deepwork, reflect, loop, interview, …).
Nessun campo di stato: sono statici.

## Note per la UI

- Il drawer ha 4 tab: **Sessioni · Agenti · Skill · MCP**, con in testa location/progetto e il
  badge dei task (figli in esecuzione).
- Cache: agenti/skill/MCP/modelli cambiano raramente → TTL 5 minuti in memoria; refresh manuale dal drawer.
- `switchAgent`/`switchModel` hanno effetto sulla **sessione aperta** (non globale).
- Dopo `switchAgent`/`switchModel` la UI deve riflettere il valore restituito dal server: rifare
  `GET /api/session/{id}` invece di assumere il successo locale.

## Verifica dal vivo delle azioni (24 ott 2026)

Eseguita su una sessione usa-e-getta (poi cancellata: `DELETE` → 204):

| Passo | Esito |
|---|---|
| `POST /api/session/{id}/agent` `{"agent":"fixer"}` | **204**, poi `GET /api/session/{id}` mostra `agent:"fixer"` |
| `POST /api/session/{id}/model` `{"model":{"id":"deepseek-flash","providerID":"deepseek","variant":"default"}}` | **204**, poi `GET` mostra `model.id/providerID/variant` |
| `GET /api/session?parentID={id}&limit=5` | `{"data":[],"cursor":{...}}` |

**Attenzione:** una sessione appena creata e mai usata **non ha i campi `agent` e `model`** nella
risposta di `GET /api/session/{id}`; compaiono solo dopo un cambio esplicito (o dopo il primo turno).
La UI deve quindi mostrare "predefinito" invece di una riga vuota, e il picker modello deve
funzionare anche partendo da quello stato.
