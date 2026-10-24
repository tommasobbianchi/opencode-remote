# M2 — note di implementazione

- **Eventi**: `net/Events.kt` parsea l'envelope reale (`id, created, type, data, durable, location`)
  in un sealed `ServerEvent`. Type ignoto → `Unknown`; `data.sessionID` assente su un evento di
  sessione → scarto; JSON malformato → `null`. Nessun payload viene loggato.
- **Streaming**: i delta sono accumulati per `(assistantMessageID, ordinal)` in `net/ChatReducer.kt`;
  la bolla "in corso" concatena i part per `ordinal` crescente. `text.ended` fissa il testo finale e
  chiude la part; `step.ended` chiude il messaggio.
- **Riconnessione**: `EventStream` legge la risposta SSE in raw per osservare anche le righe
  `: heartbeat`; timeout a 30s solo in assenza totale di byte (heartbeat server ~10-12 s).
  Backoff `1,2,4…30s` con jitter (max 20%) in `net/Backoff.kt`; con la rete assente i
  tentativi sono sospesi dal callback di rete nel ViewModel. A ogni riapertura si rilegge
  `GET /api/session/{id}/message` e si deduplica per `messageID`; `log?seq` non è usato per replay.
- **Invio ottimistico**: bolla utente immediata con flag `pending`; l'ack 200 sostituisce l'id
  provvisorio e la riconciliazione (id o testo+tempo) avviene alla rilettura dei messaggi.
- **Eventi di altre sessioni**: aggiornano solo lista/costo/badge; `ChatReducer` ignora la chat.
