# M2 — implementation notes

- **Events**: `net/Events.kt` parses the real envelope (`id, created, type, data, durable, location`)
  into a sealed `ServerEvent`. Unknown type → `Unknown`; missing `data.sessionID` on a
  session event → dropped; malformed JSON → `null`. No payload is ever logged.
- **Streaming**: deltas are accumulated per `(assistantMessageID, ordinal)` in `net/ChatReducer.kt`;
  the "in progress" bubble concatenates the parts by ascending `ordinal`. `text.ended` finalizes the
  text and closes the part; `step.ended` closes the message.
- **Reconnection**: `EventStream` reads the SSE response in raw form so it can also observe the
  `: heartbeat` lines; 30s timeout only in the total absence of bytes (server heartbeat ~10–12 s).
  Backoff `1,2,4…30s` with jitter (max 20%) in `net/Backoff.kt`; while the network is absent the
  attempts are suspended by the network callback in the ViewModel. On every reopen it re-reads
  `GET /api/session/{id}/message` and dedupes by `messageID`; `log?seq` is not used for replay.
- **Optimistic send**: immediate user bubble with a `pending` flag; the 200 ack replaces the
  provisional id, and reconciliation (id or text+time) happens when the messages are re-read.
- **Events from other sessions**: they only update the list/cost/badge; `ChatReducer` ignores the chat.
