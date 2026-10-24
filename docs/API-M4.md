# Contratto API per M4 — approvazioni

Verificato sul server di sviluppo 2.1.4 il 24 ott 2026 con chiamate reali.

## Richieste pendenti

`GET /api/session/{sessionID}/permission` → `{"data":[Permission.Request]}`
(sessione senza richieste: `{"data":[]}`)

```json
{"id":"per_…","sessionID":"ses_…","action":"bash","resources":["rm -rf /tmp/x"],
 "save":["bash"],"metadata":{},"source":{"type":"tool","messageID":"msg_…","id":"tool_…"},
 "message":"…"}
```

- `id` ha pattern `^per`.
- `action` è l'operazione (es. `bash`, `edit`, `webfetch`), `resources` le risorse coinvolte.
- `save` elenca cosa verrebbe memorizzato in caso di "always".
- `source.type` osservato: `tool` (con `messageID` e `id` del tool).

## Risposta

`POST /api/session/{sessionID}/permission/{requestID}/reply`

```json
{"decision":"once"}          // once | always | reject
{"decision":"reject","message":"motivo facoltativo"}
```

- **Il campo si chiama `decision`**, non `reply`. Valori: `once`, `always`, `reject` (`Permission.Reply`).
- Risposta **204 No Content**. Sessione inesistente → 404 `SessionNotFoundError`.
- Richiesta scaduta/inesistente → **404** con corpo:
  `{"_tag":"PermissionNotFoundError","requestID":"per_finto","message":"Permission request not found: per_finto"}`
  La UI deve tradurlo in "richiesta non più valida" e rifare `GET …/permission`.

## Cosa NON è

`POST /api/session/{sessionID}/permission` (senza `requestID`) **non crea una richiesta**: registra
una regola/risposta e restituisce `{"data":{"id":"per_…","effect":"allow"}}`.
Non usarlo in M4.

## Eventi SSE — VERIFICATI

Catturati dal vivo il 24 ott 2026 con una richiesta genuina (prompt che legge un `*.env`, regola
`ask`), su una sessione usa-e-getta poi cancellata:

```json
{"id":"evt_…","created":1792796421246,"type":"permission.asked",
 "location":{"directory":"/Users/<utente>"},
 "data":{"id":"per_…","sessionID":"ses_…","action":"read",
         "resources":["perm-probe.env"],"save":["*"],
         "source":{"type":"tool","messageID":"msg_…","id":"call_…"}}}
```

- **`permission.asked`** porta l'intero `Permission.Request` in `data` (stessa forma della GET).
- **`permission.replied`** arriva dopo la risposta ed è la conferma di risoluzione (dedupe/annulla notifica).
- Estrarre `data.id` (requestID) e `data.sessionID`; nessun involucro annidato.

Quindi l'evento è la **fonte primaria**; la `GET /api/session/{id}/permission` resta come
allineamento all'apertura, alla riconnessione e in foreground (utile se l'app era chiusa).
Il polling periodico non è più necessario: se lo tieni, fallo solo come fallback dopo una
riconnessione.

## Notifiche (lato app)

- Canale dedicato (importanza alta) + `POST_NOTIFICATIONS` runtime su Android 13+ (targetSdk 36).
- Una notifica per richiesta, dedupe per `requestID`; azioni `Once` / `Reject` dirette, `Always`
  **solo dopo conferma esplicita** (DESIGN §9: ha effetto permanente sul server, mai default).
- Alla risoluzione (da app o da notifica) la notifica va annullata.
- Il foreground service che tiene la SSE deve dichiarare `foregroundServiceType="dataSync"` e i
  permessi `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`; senza FCM non c'è garanzia di
  consegna a processo ucciso (limite accettato, DESIGN §8.5).

## Token per il servizio

Il foreground service deve autenticarsi senza l'Activity: il token va persistito cifrato
(AES/GCM con chiave in Android Keystore, preferenze private) al primo `connect()` riuscito e
cancellato da "Disconnetti". Mai in chiaro, mai nei log.
