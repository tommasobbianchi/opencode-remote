# M9 — Domande del server (form) e comandi slash

Due lacune viste all'uso, stessa zona del codice. Contratti verificati sul
server 2.1.4 di patchouli il 24 ott 2026.

## 1. Domande del server ("form")

### 1.1 Perché

Quando l'agente ha bisogno di una risposta resta **in attesa**: il turno non
finisce, la sessione è `running`, quindi il composer è bloccato e l'unica
azione è "Ferma". Il server ha un meccanismo dedicato (form) che oggi l'app
ignora.

### 1.2 Contratto verificato

Endpoint (`openapi.json` del server):

| metodo | path | corpo / risposta |
|---|---|---|
| GET | `/api/form` | `{location, data: Form.Info[]}` — form pendenti (per il resync) |
| GET | `/api/session/{sessionID}/form/{formID}` | `Form.Detail` |
| POST | `/api/session/{sessionID}/form/{formID}/reply` | `{answer: { <key>: valore }}` |
| DELETE | `/api/session/{sessionID}/form/{formID}` | annulla |

Eventi SSE (nomi presenti nel binario): `form.created`, `form.replied`,
`form.cancelled`. Il payload di `form.created` è il `Form.Detail` (da
confermare a runtime: il parser deve degradare senza crash se i campi mancano).

`Form.Detail`:

```json
{ "id": "frm_…", "sessionID": "ses_…", "title": "…",
  "metadata": {…}, "state": {"status": "pending"},
  "fields": [ { "key": "scelta", "type": "string", "title": "Che facciamo?",
                "description": "…", "required": true, "hidden": false,
                "options": [{"value": "a", "label": "A", "description": "…"}] } ] }
```

Tipi di campo (`Form.Field`): `string`, `boolean`, `integer`, `number`,
`multiselect` (con `options`), `external` (con `url`: non componibile dall'app).
Proprietà utili: `key`, `title`, `description`, `required`, `hidden`, `when`
(condizioni: **ignorate in v1**), `minimum`/`maximum` per i numerici,
`options[]` per multiselect.

`Form.Value` (valore di una risposta): `string` | `number` | `boolean` |
`string[]`.

### 1.3 Comportamento nell'app

- Stato: `UiState.forms: List<FormUi>` (le domande pendenti della sessione
  aperta; in pratica una) + `formsLoading`/`formsError` e l'esito dell'invio.
- Sorgenti: eventi `form.created` (aggiunge), `form.replied` / `form.cancelled`
  (rimuove), più `GET /api/form` al connect, all'apertura sessione e al
  reconnect SSE (come per le approvazioni M4).
- UI: **pannello fissato sopra il composer** (non nella lista, così non si
  perde scorrendo), visibile solo con sessione aperta e form pendente:
  - eyebrow "domanda", titolo, descrizione se c'è;
  - un controllo per campo: testo (una o più righe per `description` lunga),
    checkbox per `boolean`, campo numerico con tastiera numerica per
    `integer`/`number`, chip selezionabili multipli per `multiselect`,
    `external` → link non componibile con nota "da compilare sul server";
  - campi con `hidden = true` non si disegnano;
  - pulsanti: "rispondi" (disabilitato finché i campi `required` non sono
    compilati) e "annulla" (DELETE);
  - errori: campo obbligatorio mancante → messaggio breve; 404 → "domanda
    scaduta", il pannello sparisce e si fa resync.
- Il composer resta bloccato mentre c'è una domanda pendente? **No**: il
  server accetta comunque prompt nel while-in-attesa, ma per non confondere si
  lascia il composer attivo solo se non c'è form pendente; con form pendente
  l'utente vede il pannello e il pulsante "Ferma" resta disponibile.
- TalkBack: il pannello annuncia "domanda di opencode" una volta sola.

## 2. Comandi slash

### 2.1 Perché

Oggi scrivere `/review` manda il testo **letterale** al modello: il comando non
viene espanso (il TUI lo espande lato client, l'API ha un endpoint dedicato).

### 2.2 Contratto verificato

| metodo | path | corpo |
|---|---|---|
| GET | `/api/command` | `{location, data: [{name, description}]}` |
| POST | `/api/session/{sessionID}/command` | `{name, text?, files?, agents?, skills?, delivery?}` |

Elenco attuale sul server (9): `init`, `review`, `deepwork`, `reflect`, `loop`,
`interview`, `implement`, `close`, `resume` — l'app deve leggerli a runtime, non
avere un elenco fisso.

### 2.3 Comportamento nell'app

- Catalogo: `CommandUi(name, description)` caricato con `GET /api/command`
  (TTL come gli altri cataloghi, es. 5 min), ricaricabile a mano.
- **Palette**: si apre quando il draft inizia con `/` e non contiene ancora
  spazi; `ModalBottomSheet` **scorrevole** (`LazyColumn`) con riga per comando
  (nome in mono, descrizione `mutedText`, 56 dp), filtro sul testo dopo `/`,
  tap → il draft diventa `/<name> ` e il cursore va in fondo; "annulla" chiude.
  Il placeholder del campo vuoto diventa "Scrivi un messaggio… oppure / per i
  comandi".
- **Invio**: se il draft è `/<name>` o `/<name> <argomenti>` e `<name>` è nel
  catalogo → `POST /api/session/{id}/command` con `name` e `text` = il resto
  (anche vuoto) e gli eventuali allegati; altrimenti → prompt normale.
  Comando sconosciuto → invio normale (nessun blocco, è testo come gli altri).
- Dopo l'invio di un comando: stessa bolla ottimistica di un prompt (testo
  digitato), poi il resync porta la verità dal server.

## 3. Dove sta cosa

| Area | File |
|---|---|
| DTO/eventi form + comandi | `net/Events.kt`, `net/Models.kt`, `net/OpenCodeApi.kt` |
| Stato e azioni | `AppViewModel.kt` (`forms`, `replyForm`, `cancelForm`, `refreshForms`, `commands`, `loadCommands`) |
| Pannello domanda + palette | `ChatScreen.kt` (+ eventuale `Forms.kt` accanto, come `Attachments.kt`) |
| Wiring | `MainScreen.kt` |
| Test JVM | parsing `form.created`, costruzione `answer` (tipi misti), scelta comando/allineamento del testo, parsing `GET /api/command` |

## 4. Criteri di accettazione

1. L'agente fa una domanda → compare il pannello con i campi; rispondo → il
   turno riprende e il pannello sparisce.
2. Form con campo obbligatorio vuoto → "rispondi" disabilitato.
3. Form numerico e multiselect → i valori arrivano nei tipi giusti (`number`,
   `string[]`).
4. "Annulla" sul pannello → DELETE e il pannello sparisce.
5. Riapro la sessione con una domanda pendente → il pannello c'è (resync).
6. Digito `/` → palette scorrevole con tutti i comandi del server; filtro
   digitando; tap → `/nome ` nel campo.
7. Invio `/review main` → parte il comando (non un prompt letterale).
8. Build verde con i test esistenti + quelli nuovi.
