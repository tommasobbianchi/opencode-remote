# UI M6 — turno live (ragionamento, tool, testo)

Data: 24 ottobre 2026. Riferimenti: `docs/THEME.md`, `docs/DESIGN.md`,
`docs/API-M2.md`, `docs/UI-M4.md`, `ChatScreen.kt`, `net/ChatReducer.kt`,
`net/Events.kt`, screenshot del TUI `/tmp/ocm-screen.jpg`.

Obiettivo: durante il turno la chat deve mostrare **le stesse cose che mostra il
TUI**, nello stesso ordine: pensiero, comando in scrittura, comando con output,
poi il testo in streaming (e di nuovo tool → testo). Sparisce il placeholder
unico "sta scrivendo…" come contenuto del turno.

Vincolo forte: **un solo renderer**. Il turno live e il messaggio persistito
dopo il resync passano dallo stesso composable, con gli stessi stili. Nessun
path "live" separato, nessuna copia dei colori o delle misure.

---

## 1. Albero dei widget (turno)

Un item della `LazyColumn`, chiave `turn:<messageId>`, che resta la stessa
durante live → resync (niente rimbalzo, niente doppia bolla).

```
AssistantTurn(turn: AssistantTurnUi)          // unico entry point
└ Column(fillMaxWidth)
  ├ Row(Start)
  │ └ Surface(AssistantBubbleShape, surfaceContainer,
  │           border = if (running) interactive 30% else border 55%)
  │   └ Column(padding 14dp / 12dp, spacedBy 10dp)
  │     ├ if (parts.isEmpty() && turn.running) ThinkingPlaceholder   // §2.4
  │     └ parts.sortedBy(order).forEach { part ->
  │         key(part.key) {
  │           when (part) {
  │             is ReasoningPartUi -> ReasoningBlock
  │             is ToolPartUi      -> ToolCard
  │             is TextPartUi      -> MarkdownBody(part.text, streaming = ...)
  │           }
  │         }
  │         if (part è l'ultimo && turn.running && nessuna part attiva)
  │             ThinkingPlaceholder                        // §2.4
  │       }
  └ Row(Start) padding(top 4dp, start 3dp) → MessageTimestamp
```

Regole dell'albero:

- **Un solo `Surface` per messaggio assistente**: reasoning, tool e testo sono
  figli della stessa bolla. Le card dei tool non escono dalla bolla.
- `parts` è già ordinata dal reducer; il composable non riordina e non
  raggruppa per tipo. È questo che oggi manca: `AssistantMessage` disegna
  prima *tutto* il reasoning, poi *tutto* il testo, poi *tutti* i tool, quindi
  l'interleaving del TUI si perde.
- **Persistito = live**: dopo il resync `turn` è costruito da
  `MessageUi.parts` (mappato da `content[]`, order = indice dell'array). Il
  composable riceve lo stesso tipo e produce lo stesso albero. Zero stili
  duplicati, zero differenze tra "prima" e "dopo".
- Handoff: il reducer sostituisce il live con il messaggio server **con lo
  stesso `id`**; l'item ha la stessa chiave, quindi la lista non salta e le
  part espanse restano tali quando la `key` coincide (§3.5).
- Scorrimento: la bolla usa `bubbleMax` già esistente
  (`(maxWidth - 32dp) * 0.85`, minimo 220dp). Nessun blocco a piena larghezza.

---

## 2. Le part, una per una

### 2.1 Ragionamento

Header cliccabile (target ≥48dp), una riga:

| Elemento | Stile |
|---|---|
| chevron 14dp destra/giù | colore header (sotto) |
| dot 5dp | solo in streaming, `warningInk`, `pulsing = true` |
| "Ragionamento" | `labelMedium` (12sp), colore header |
| " · 1,3 s" | `labelMedium`, `mutedText`; ticking mentre streama |
| a destra "apri" / "chiudi" | `labelSmall` (11sp), `mutedText` |

Colore header, come oggi e come TUI (`THEME` §Mappatura 1):
`text.base` in streaming → `warningInk` espanso → `warningInk` 60% (solo dark)
collassato e concluso; nel chiaro `warningInk` pieno.

- **Default collassato**, anche in streaming.
- **In streaming** il corpo non si apre da solo: sotto l'header compare **una
  riga di anteprima** (ultima riga non vuota del testo), `bodySmall`,
  `mutedText`, `maxLines = 1`, ellissi. Dà il segno di vita senza far saltare
  la chat a ogni token.
- **Espanso**: superficie `surfaceContainerLowest` (dark = `#0a0a0a`), bordo
  1dp `warningInk` 22% (35% in streaming), shape `shapes.small` (10dp),
  padding 12dp, testo `bodySmall` 12.5sp/18sp in `mutedText`,
  `SelectionContainer`.
- **Limite**: prime 14 righe (o 2400 caratteri) + riga "mostra tutto · N
  righe" (48dp, `interactive`, `labelSmall`) che apre il foglio §2.5 con il
  testo intero. Oltre il limite non si passa il testo completo a `Text`.
- **Durata**: `reasoning.started` → `reasoning.ended`. Formato:
  `<10 s` → "1,3 s" (virgola, un decimale); `<60 s` → "12 s";
  oltre → "1 min 12 s". Se i tempi mancano (messaggi vecchi) si usa
  " · N righe" / " · 1 riga", come oggi. Mai entrambi: la durata vince.
- Accessibilità: `stateDescription` "espanso"/"collassato", `onClickLabel`
  "Mostra ragionamento" / "Nascondi ragionamento".

### 2.2 Tool

Card sempre visibile, **sempre aperta** (il TUI non ha collapse): niente
chevron, niente tap sull'intera card (romperebbe la selezione del testo).
Solo la riga "mostra tutto" è cliccabile.

Struttura:

```
Column(surfaceContainerLow, bordo 1dp border 50%, shapes.small, padding 10/8)
├ Row 1 (min 48dp, spacedBy 7dp)
│  ├ StatusDot 5dp                     // colore = fase
│  ├ nome tool  mono 12sp/16sp Medium  // interactive, maxLines 1, max 40% riga
│  ├ label stato  labelSmall 11sp      // colore = fase
│  ├ exit chip (se exit ≠ 0) mono 10sp // error, es. "exit 1"
│  └ Spacer(weight 1) + durata mono 10sp/13sp lineNumber
├ riga comando (se presente)
│  "$ " mono 12sp lineNumber + comando mono 12sp/17sp text.base
└ blocco output (se presente)
   └ mono 11.5sp/17sp text, ultime 6 righe, + "mostra tutto · N righe"
```

- **Nome**: `bash`, `read`, `edit`… `interactive`, mono 12sp Medium, 1 riga,
  ellissi, massimo ~40% della riga.
- **Comando**: riga propria, `$ ` in `lineNumber`, comando in `text.base`
  (nel TUI il comando è bianco, il `$` è spento). Mono 12sp/17sp, **wrap
  fino a 4 righe**, poi ellissi + la riga "mostra tutto" apre il foglio con
  il comando intero **in scroll orizzontale** (`softWrap = false`), così un
  one-liner lunghissimo resta leggibile senza spezzare i token.
- **Soggetto non-comando** (`read`, `write`, `edit`, `grep`…): niente `$`;
  si mostra il primo campo utile fra `path` / `filePath` / `pattern` /
  `query` / `url` / `description`. Stessa riga, stesso mono.
- **Stato** (dot + label, colori `THEME` §Mappatura 2):

| fase | dot | label | colore |
|---|---|---|---|
| input in arrivo, comando non ancora estraibile | warning pulsante | "scrivo il comando…" | `warningInk` |
| chiamata partita, in esecuzione | warning pulsante | "in corso" | `warningInk` |
| concluso | success | "ok" | `success` |
| fallito | error | "errore" | `error` |
| negato / non eseguito (`executed=false`) | error | "negato" | `error` |
| interrotto (turno fermato) | warning | "interrotto" | `warningInk` |

- **Durata**: da inizio chiamata a esito, stesso formato del ragionamento.
  Compare solo se ≥1 s; in esecuzione ticka. Per "negato" non c'è durata.
- **Exit**: chip `exit 1` in mono 10sp `error` accanto allo stato quando
  `exit != 0`; per "ok" con exit 0 non si mostra nulla. Se `truncated` è vero,
  chip `output troncato` (`labelSmall`, `lineNumber`).
- **Output**: ultime **6 righe** (il fondo è dove stanno gli errori, come nel
  terminale), mono 11.5sp/17sp `text`, nessuno sfondo aggiuntivo (la card è
  già `surfaceContainerLow`), 8dp sotto il comando. Se il tool non ha prodotto
  testo: riga "(nessun output)" in `lineNumber` mono 11.5sp. Mentre è in
  corso l'output cresce in coda, senza animazioni di layout.
- **Fallito**: stesso layout, dot/label `error`, output visibile in coda; il
  bordo **non** diventa rosso (il TUI non colora i bordi; lo stato è già
  veicolato da dot, label ed exit).
- **Negato**: si mostra nome + comando, poi la nota "non eseguito" in
  `mutedText`; nessun blocco output.
- **"mostra tutto · N righe"**: riga da 48dp, `labelSmall` `interactive`,
  allineata a sinistra, sotto l'output. Apre il foglio §2.5 con comando e
  output completi. N = righe totali dell'output.
- **Selezione**: `SelectionContainer` su comando e output (separati), mai
  sull'intera card, così il tap sulla riga "mostra tutto" resta affidabile.

### 2.3 Testo

- Riuso di `MarkdownBody` (heading/link/code/quote già a tema), `bodyLarge`
  16sp/24sp, `oc.text`, `SelectionContainer` per blocco.
- **Cursore**: `▌` accodato all'ultima part text **solo** con
  `phase == Streaming`, colore `interactive`, stessa `bodyLarge`; lampeggio
  1 ↔ 0.35 in 900 ms (a fine testo resta pieno se gli animatori sono spenti).
  Un solo cursore per turno: quello dell'ultima part text in streaming.
- Niente label "sta rispondendo": lo stato lo danno cursore e part attive.
- Il testo non si tronca mai: è il contenuto principale.
- Ogni blocco ha `key = "text:<ordinal>"`; il markdown si ricalcola con
  throttle (§4.4).

### 2.4 Nessuna part ancora (fallback)

Vale per due casi: `step.started` senza part, oppure turno in corso con
l'ultima part già conclusa (pausa fra testo e tool successivo).

- Dentro la bolla, al posto del contenuto / dopo l'ultima part, su una riga:
  `WorkingIndicator(12dp, success)` + "sta scrivendo…" (`labelMedium`,
  `mutedText`). Sostituisce i tre puntini: un solo arco, come il resto
  dell'app.
- Compare **solo** se `turn.running && nessuna part Streaming/Running`.
- **Grazia 400 ms** (`LaunchedEffect` + `delay`) prima di comparire: evita il
  lampeggio nel passaggio text.ended → tool.started.
- TalkBack: l'arco è decorativo (`description = "attesa"`), la riga annuncia
  una volta sola "sta scrivendo…".
- Con `running == false` e zero part non si disegna nulla (la bolla non
  esiste): l'errore eventuale lo porta già l'`ErrorBanner`.

### 2.5 Foglio di dettaglio (unico, condiviso)

`ModalBottomSheet` (Material 3), `surfaceContainer` = `raised`, angoli
superiori `shapes.large`.

- Titolo: `output · bash` (mono 11sp, `mutedText` in `Eyebrow` uppercase) e a
  destra "chiudi" (48dp, `labelSmall` `mutedText`).
- Corpo: sezioni "comando" e "output" con `Eyebrow`; testo mono 11.5sp/17sp
  in **scroll orizzontale** (no wrap) e verticale, altezza max 80% schermo.
- Azione "copia" (`interactive`, 48dp) copia la sezione visibile; conferma
  discreta "copiato" per 1,5 s.
- Usato da: output/comando del tool, ragionamento lungo. Un solo composable,
  parametri `title`, `sections`.

---

## 3. Ordine, duplicati, reducer (contratto)

### 3.1 Identità e ordine

- Turno = `assistantMessageID`. Al massimo **un turno live** per sessione.
- `order` è un cursore monotono **per messaggio**, incrementato al primo
  evento di una part (`reasoning.started`, `text.started`,
  `tool.input.started`, oppure `tool.called` se l'input non è arrivato) e mai
  riusato. Non è l'`ordinal`: l'`ordinal` è per tipo, i tool non ce l'hanno, e
  un contatore unico è l'unica cosa che ricostruisce l'interleaving.
- Dopo il resync `order` = indice della part in `content[]`. Stessa semantica,
  stesso ordinamento, stesso renderer.
- `ordinal` resta un campo della part (`reasoning`/`text`) perché identifica
  i delta SSE, non la posizione visiva.
- `key` stabile, mai dipendente da testo o stato:
  `reasoning:<ordinal>`, `text:<ordinal>`, `tool:<callId>`.
  Persistito senza call id: `tool:<indice>`; senza ordinal: `<tipo>:<indice>`.

### 3.2 Mappa eventi → stato

| evento | effetto |
|---|---|
| `session.step.started` | crea il turno `running`, `startedAt`; se esiste un turno live **non vuoto con altro id**, prima lo materializza in `messages` (turni multi-step) |
| `reasoning.started` | crea `ReasoningPartUi(phase=Streaming, order=cursore++)`; idempotente se esiste |
| `reasoning.delta` | **accoda** solo se la part non è terminale; se manca la part la crea (recupero) |
| `reasoning.ended` | sostituisce il testo con `text` del server, `phase=Done`, `endedAt`; chiude la part |
| `text.*` | identico, con `TextPartUi` |
| `tool.input.started` | crea `ToolPartUi(phase=Streaming, order=cursore++, callId=id, name)`; idempotente per `callId` |
| `tool.input.delta` | accumula in un buffer privato; **non** renderizza il JSON. Tenta l'estrazione tollerante di `command` dal JSON parziale: se riesce, aggiorna `command`; se no, resta "scrivo il comando…" |
| `tool.input.ended` | `text` = JSON completo: estrae `command`/soggetto e `inputPreview`; la fase **non** cambia (aspetta `called`) |
| `tool.called` | input strutturato disponibile: aggiorna nome/comando; `executed=false` → `phase=Denied`; altrimenti `phase=Running`, `startedAt` |
| `tool.progress` | aggiorna solo metadati (exit, truncated); nessuna riga di output inventata |
| `tool.success` | `output` = join delle `content[].text`, `exitCode`, `truncated`, `phase=Ok`, `endedAt` |
| `tool.failed` | come sopra, `phase=Failed` (o `Denied` se `executed=false`) |
| `session.step.streamed` | nessun cambio visivo (il cursore dipende dalla fase della part text) |
| `session.step.ended` | turno `running=false`, `finish`; **non** cancella le part; il turno resta finché il resync non lo sostituisce con lo stesso id |
| `session.step.failed` | tutte le part `Streaming/Running` → `Interrupted`; turno `running=false` |

### 3.3 Late, duplicate, ripetute

- Ogni handler è **idempotente**: `started` su part esistente non fa nulla;
  `ended` sostituisce il testo (il server vince sui delta accumulati);
  `success`/`failed` su part già terminale ignorano.
- Dopo `step.ended` per quel `messageId`: si ignorano nuovi delta/part
  (`finishedMessages`, come oggi). Fa eccezione solo il resync.
- Una part che arriva **dopo** altre con `order` minore viene **inserita** al
  suo posto (lista ordinata), non accodata; `key` stabile conserva lo stato
  di espansione.
- Delta senza `started`, `ended` senza delta: si recupera creando/completando
  la part al cursore corrente. Mai un crash, mai un JSON a schermo.
- `tool.input.ended` senza `tool.called`: si tiene il comando estratto e la
  fase `Streaming`; se arriva `success` senza `called`, si passa direttamente
  a `Ok`.
- Doppio `tool.success` con contenuti diversi: vince il primo esito terminale
  (nessun "rimbalzo" dell'output a schermo).

### 3.4 Turni multipli

`step.started` con id diverso mentre il turno live è non vuoto: il vecchio
turno viene materializzato in `messages` (stesso id, stesse part, `running =
false`) e il nuovo parte live. Le bolle precedenti non spariscono in attesa
del resync finale (comportamento già presente, da estendere alle part).

### 3.5 Handoff live → persistito

- `GET …/message` restituisce il messaggio con lo stesso `id` e le part
  complete: il reducer **sostituisce** la voce live con quella server,
  chiavi di lista invariate (`message.id`).
- Le `key` delle part devono coincidere quando possibile (call id e ordinal
  sono nel payload); se non coincidono, lo stato espanso si azzera: accettabile,
  mai uno stile diverso.
- Se il resync fallisce a fine turno, il turno live resta materializzato in
  `messages` (già oggi): non si perde il contenuto.

---

## 4. Misure, token, mobile, performance

### 4.1 Token (tutti già in `Theme.kt` / `OpenPalette`)

| uso | token |
|---|---|
| bolla, card tool | `surfaceContainer` (`raised` #141414) / `surfaceContainerLow` (#101010) |
| corpo ragionamento | `surfaceContainerLowest` (#0a0a0a) |
| testo | `oc.text`; secondario `oc.mutedText`; meta `oc.lineNumber` |
| bordi | `oc.border` 50–55%; ragionamento `warningInk` 22/35% |
| ragionamento | `oc.warningInk` (60% solo dark, collassato e concluso) |
| nome tool, cursore, "mostra tutto", link | `oc.interactive` |
| ok | `oc.success`; in corso/interrotto `oc.warningInk`; errore/negato `oc.error` |
| sfondo codice markdown | `oc.formField` (invariato) |

Nessun colore nuovo. Nessun font nuovo. `monoStyle()` per ogni dato tecnico.

### 4.2 Tipografia e spazi

| elemento | misura |
|---|---|
| padding bolla | 14dp H / 12dp V (invariato) |
| gap fra part | 10dp (invariato) |
| header ragionamento | min 48dp; corpo 12dp; gap header→corpo 6dp |
| card tool | padding 10dp H / 8dp V; radius `shapes.small` 10dp; gap interni 6dp; comando→output 8dp |
| nome tool | mono 12sp/16sp Medium |
| comando | mono 12sp/17sp |
| output | mono 11.5sp/17sp |
| label stato | `labelSmall` 11sp |
| durata / exit | mono 10sp/13sp |
| ragionamento (header) | `labelMedium` 12sp; anteprima/corpo `bodySmall` 12.5sp/18sp |
| testo | `bodyLarge` 16sp/24sp |
| "mostra tutto" | `labelSmall` 11sp, riga 48dp |

### 4.3 Vincoli mobile

- Larghezza: tutto dentro `bubbleMax`; il foglio di dettaglio è l'unica
  superficie a piena larghezza.
- **Nessuno scroll verticale annidato illimitato**: inline si usano solo
  limiti di righe (output 6, comando 4, ragionamento 14) e il resto va nel
  foglio. Lo scroll orizzontale è ammesso solo dentro il foglio.
- Target ≥48dp: header ragionamento, riga "mostra tutto", azioni del foglio,
  "chiudi"/"copia". La card tool non è cliccabile.
- Troncamento: nomi 1 riga con ellissi; comandi 4 righe; output 6 righe;
  ragionamento 14 righe. Il testo del modello non si tronca.
- Selezione: un `SelectionContainer` per blocco (testo, ragionamento, output),
  mai uno solo per tutta la bolla.
- `maxLines` non basta: al `Text` si passa **solo la porzione visibile**
  (ultime 6 righe per l'output, prime 14 per il ragionamento). Il resto resta
  nello stato e va al foglio.

### 4.4 Performance con delta frequenti

- **Un item solo** per il turno (`turn:<id>`): i delta non ricostruiscono la
  `LazyColumn`, solo la bolla. Dentro la bolla, part in `Column` con `key`
  stabile (un turno ha poche decine di part; niente `LazyColumn` annidata).
- **Publish throttled**: il reducer accumula i delta (economico); la UI
  riceve uno snapshot al massimo ogni **80–120 ms**, tranne gli eventi
  terminali (`ended`, `success`, `failed`, `step.ended`) che pubblicano
  subito. Evita 30–60 ricomposizioni/s.
  *Implementato in 0.9.1*: `net/PublishGate.kt` (finestra 100 ms, `now`
  iniettato) + `ChatReducer.publishedImmediately()` (throttled solo i delta
  puri); lo snapshot pubblicato è `ChatSnapshot` in `AppViewModel`.
- **Markdown incrementale**: `parseMarkdown` memoizzato su
  `(key, lunghezza/hash del testo)`; i blocchi già chiusi restano in cache,
  si ri-analizza solo la coda aperta. Il cursore non fa parte del testo
  parsato: si disegna in coda, così il parse non cambia a ogni blink.
- **Una sola animazione per turno**: un `rememberInfiniteTransition` per il
  cursore (alpha via `graphicsLayer`), uno per il dot attivo, non uno per
  part. Se `ValueAnimator.areAnimatorsEnabled()` è falso, cursore e dot restano
  pieni.
- **Un solo ticker** per il turno (`produceState`, 100 ms) attivo solo se
  esiste almeno una part `Streaming/Running`; si ferma a turno concluso.
  La durata mostra i decimi solo sotto i 10 s.
- **Niente animazioni di layout** sulla crescita di testo/output: un
  `AnimatedVisibility` per delta è la causa principale di jank. L'unica
  transizione ammessa è l'apertura/chiusura del ragionamento (azione utente).
- **Auto-scroll**: solo se l'utente è in fondo (logica già presente), con
  throttle ~150 ms, agganciato a `turn.revision`, mai mentre l'utente ha
  scrollato su. La crescita dell'output di un tool non deve forzare lo scroll.
- **Niente misurazione**: nessun `onTextLayout` per i limiti di riga; si
  tronca la stringa prima del rendering.
- `output` e `command` sono campi già calcolati nello stato: nessun join o
  parsing dentro la composable a ogni frame.

---

## 5. Copy (italiano, minuscolo, breve)

| contesto | testo |
|---|---|
| ragionamento | "Ragionamento" |
| durata | "1,3 s" · "12 s" · "1 min 12 s" |
| fallback durata | "4 righe" · "1 riga" |
| apri/chiudi | "apri" · "chiudi" |
| tool input in corso | "scrivo il comando…" |
| tool in esecuzione | "in corso" |
| esiti | "ok" · "errore" · "negato" · "interrotto" |
| tool non eseguito | "non eseguito" |
| exit | "exit 1" (mono) |
| output vuoto | "(nessun output)" |
| output tagliato dal server | "output troncato" |
| espansione | "mostra tutto · 42 righe" |
| nessuna part | "sta scrivendo…" |
| foglio | "comando" · "output" · "copia" · "copiato" · "chiudi" |
| TalkBack | "Mostra ragionamento" · "Nascondi ragionamento" · "Mostra tutto l'output di bash" · "Ragionamento in corso" · "Tool in corso" · "Tool completato" · "Tool fallito" · "Tool negato" |

Nessun punto esclamativo, nessun "prego", nessun titolo in maiuscolo nel
sorgente (`Eyebrow` fa l'uppercase lui).

---

## 6. Cosa rimuovere o cambiare

1. **`StreamingMessage`** (bolla separata + "sta rispondendo"): eliminata.
   Il turno live è `AssistantTurn` come il persistito; la label sparisce.
2. **`TypingIndicator`**: resta un solo caso d'uso, il fallback §2.4, con
   `WorkingIndicator` al posto dei tre puntini. Non è più il contenuto del
   turno: compare solo se non c'è nessuna part attiva.
3. **`ToolChipView`**: eliminata. Il dump JSON troncato dello stato non è
   informazione utile ed è la ragione per cui il tool sembrava vuoto.
   Sostituita da `ToolCard` (§2.2) con comando, output, stato, durata, exit.
4. **`highlightToolOutput`**: non più usata per il JSON di stato. L'output dei
   tool è testo semplice in mono; se resta per altri usi, non viene chiamata
   dalla chat.
5. **`toolStatusOf` / `ToolChip.state`**: lo stato non si ricava più con regex
   dal JSON in UI; arriva tipizzato dagli eventi (§3.2) e dal `content[]` al
   resync. `summarize()` non serve più al rendering.
6. **`AssistantMessage`**: non raggruppa più reasoning → testo → tool. Itera
   `parts`. I campi `text`/`reasoning`/`tools` di `MessageUi` non sono più la
   fonte di verità per il disegno (restano per ricerca/copia/compatibilità).
7. **`ReasoningSection`**: header con durata (+ fallback righe), anteprima di
   una riga in streaming, limite 14 righe con "mostra tutto".
8. **Logica `showLive` / `showTyping` in `ChatScreen`**: sostituita da un solo
   `liveTurn`; `extraRows` non dipende più dalla presenza di testo.
9. **Animazioni di espansione dei tool**: rimosse (non c'è più collapse).
   Resta solo quella del ragionamento, su azione dell'utente.

Invariati: bolle utente, timestamp, `MetaDivider`, header chat, composer,
banner, approvazioni, markdown, palette e tipografia.

---

## 7. Contratto dati richiesto (per chi implementa il reducer)

Tipi che la UI vuole. Nessun JSON, nessun `JsonElement` oltre il confine del
reducer.

```kotlin
// Done = text/reasoning conclusi; Ok/Failed/Denied = esiti di un tool.
enum class PartPhase { Streaming, Running, Done, Ok, Failed, Denied, Interrupted }

sealed interface TurnPartUi {
    val key: String      // stabile: "reasoning:0" | "text:1" | "tool:call_abc"
    val order: Int       // cursore per messaggio; resync = indice in content[]
    val phase: PartPhase
}

data class ReasoningPartUi(
    override val key: String,
    override val order: Int,
    override val phase: PartPhase,
    val text: String,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
) : TurnPartUi

data class TextPartUi(
    override val key: String,
    override val order: Int,
    override val phase: PartPhase,
    val text: String,
) : TurnPartUi

data class ToolPartUi(
    override val key: String,
    override val order: Int,
    override val phase: PartPhase,
    val callId: String,
    val name: String,            // "bash", "read", ...
    val command: String? = null, // comando o soggetto, già estratto
    val output: String? = null,  // content[].text già unito
    val exitCode: Int? = null,
    val truncated: Boolean = false,
    val executed: Boolean = true,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
) : TurnPartUi

data class AssistantTurnUi(
    val id: String,                       // assistantMessageID
    val parts: List<TurnPartUi>,          // ordinate per order
    val createdAt: Long? = null,
    val finish: String? = null,
    val running: Boolean = false,
    val revision: Long = 0,               // bump a ogni modifica; auto-scroll/throttle
)
```

Modifiche minime di stato:

- `ChatStream`: `liveTurn: AssistantTurnUi?` al posto di `LiveMessage`/
  `LivePart`; `nextOrder` per messaggio; `closedParts` e `finishedMessages`
  restano; `materialized` per i turni multi-step.
- `MessageUi`: `parts: List<TurnPartUi>` popolata da `content[]` (indice →
  `order`); `text`/`reasoning`/`tools` restano come campi derivati per
  compatibilità, non per il rendering.
- Mappatura dal payload: `tool` → `ToolPartUi` con
  `state.status` → `PartPhase`, `state.input.command`/`path`/`pattern` →
  `command`, `state.content[].text` → `output`, `state.metadata.exit` /
  `truncated` → campi omonimi, `time.start`/`time.end` → `startedAt`/`endedAt`.

Criterio di accettazione: durante un turno con tool, la chat mostra in
sequenza ragionamento (durata), riga "scrivo il comando…", comando con `$` e
output entro pochi decimi di secondo dall'evento, testo in streaming con
cursore; al resync la stessa bolla resta al suo posto con gli stessi stili,
senza placeholder residui e senza dump JSON.
