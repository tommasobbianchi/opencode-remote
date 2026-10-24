# Stato del progetto (chiusura sessione, 24 ott 2026)

Client Android generico per controllare un server **OpenCode 2.x** dal telefono
(nessun preset personale: URL vuoto, credenziali dell'utente).
Package `io.github.mirkotappini.opencoderemote`.
Ultima build consegnata: **0.9.3**, `versionCode 22`, sha256 `596764074…` (30,9 MB) (nome app **OC Remote**).

## Dove

| Cosa | Dove |
|---|---|
| Sorgente | `serber:/home/mirko/opencode-mobile` (**non è un repo git**) |
| Build | `build-host:/Users/<utente>/opencode-mobile` (JDK+SDK in `~/android-toolchain`) |
| Distribuzione | `serber:python3 serve.py` → `http://<ip-tailnet>:8091/app.apk` (sempre l'ultima da `dist/current.txt`) |
| Documenti | `docs/DESIGN.md`, `docs/API-M1..M4.md`, `docs/UI-M1..M4.md`, `docs/THEME.md`, `docs/M5-CHECKLIST.md`, `docs/M2-NOTE.md` |

## Stato per milestone

- **M0** trasporto (Tailscale serve, Basic auth, SSE) — fatto
- **M1** lista sessioni + chat read-only + storico — fatto
- **M2** invio prompt, streaming live, interrupt, riconnessione con resync — fatto
- **M3** drawer OMO (3 tab: Agenti/Skill/MCP), picker modello con ricerca, riga sessione, badge task, subagent informativi, indicatore agenti al lavoro — fatto
- **M4** approvazioni: pannello in chat, badge, notifiche con azioni, foreground service, credenziali cifrate (Keystore) + auto-connect — fatto
- **M5** hardening: version gate, tassonomia errori, politica 401, sessione sparita, NetworkCallback, watchdog SSE 30 s, countdown corretto, icona launcher — fatto
- **M7** identità: nome OC Remote, nuova icona (adaptive senza wordmark, legacy con wordmark, monochrome) — fatto
- **M6** vista turno live: ragionamento con durata, card tool con comando/output/stato/exit,
  testo in streaming, step consecutivi in un'unica bolla — fatto (spec in `docs/UI-M6-LIVE.md`)

Test: **94 JVM**, tutti verdi, eseguiti con `./gradlew --no-daemon --no-build-cache clean testDebugUnitTest assembleDebug` sul server di sviluppo.

## Throttle di pubblicazione dei delta (0.9.1)

§4.4 di `docs/UI-M6-LIVE.md`: il reducer accumula ogni delta (economico), ma la
UI riceve uno snapshot della chat **al massimo ogni 100 ms**, tranne gli eventi
di fase e fine turno che passano subito. Da 30–60 ricomposizioni/s a ~10.

- `net/PublishGate.kt`: cancello puro (nessun orologio interno, `now` è un
  parametro) con `offer` / `dueIn` / `takePending` / `dropPending` / `reset`.
  Il pending contiene sempre l'ultimo snapshot: nulla si perde se un evento
  terminale arriva prima dello scadere della finestra.
- `ChatReducer.publishedImmediately()`: throttled solo i delta puri
  (`text.delta`, `reasoning.delta`, `tool.input.delta`, `tool.progress`).
  Tutto il resto (started/ended, `tool.called`, success/failed, step, execution)
  è immediato: i cambi di fase non devono mai aspettare.
- `AppViewModel`: `ChatSnapshot` (messaggi + `ChatStream` + running + outcome) è
  l'unità pubblicata; `scheduleChatFlush` pubblica l'ultimo snapshot allo
  scadere, `flushChatNow` è obbligatorio prima di ogni mutazione di
  `messages`/`chat` fuori dal reducer (`sendPrompt`, rename del messaggio
  ottimistico, `loadOlder`, `resyncOpenSession`), altrimenti lo snapshot in
  attesa tornerebbe sopra scrivendola. `resetChatPublish` su apertura sessione,
  `backToList`, `stopStream`.
- Test: `PublishGateTest` (finestra, terminali, pending, 60 Hz → ~10
  pubblicazioni/s, classificazione eventi).

## Icona 0.9.3 (render 3D "OC remote")

Nuovo render fornito dall'utente: "OC" glossy (O giallo, C viola) con wordmark
"remote" verde neon, piastra nera arrotondata. Rigenerata con
`tools/icon/make_icon.py` da `tools/icon/icon-source.png`.

- **Adattiva**: marchio **intero** (glifo + wordmark) senza piastra, alpha dalla
  luminanza, al 66% della tela (safe zone). Niente riquadro annidato: il fondo
  nero del render diventa trasparente e sotto c'è `@color/ic_launcher_background`.
  Il wordmark resta dentro la maschera circolare, quindi non viene tagliato.
  (Decisione M7 "adattiva senza wordmark" superata da questo render: il taglio
  al wordmark produceva un bordo netto sul fondo di O e C.)
- **Legacy/round**: piastra intera, come prima.
- **Monochrome**: "OC" in JetBrains Mono.
- Fondo icona: `#040407` (prima `#121510`). Il colore ora è preso dal quantile
  basso di luminanza della cornice (`dark_background`), non dalla media: il glow
  giallo/viola trascinava la media su un marrone `#302219`.
- Anteprima rigenerata: `dist/icon-preview-0.9.3.png` (fuori dal repo).

## Auto-scroll della chat (0.9.2)

Sintomo riportato: quando i messaggi di OpenCode si susseguono la chat non
seguiva il fondo, con tastiera aperta o chiusa; bisognava trascinare a mano.

Causa: si decideva "sono in fondo?" **dopo** la crescita del contenuto, con una
tolleranza di 2 item (`lastVisible >= totalItemsCount - 2`). Se il contenuto
cresceva mentre la lista si assestava — step fusi in un'unica bolla, resync,
messaggi consecutivi — il confronto falliva e il flag `autoScrolled` restava
`true` senza più scrollare.

Ora c'è un follow **sticky** (`followBottom`), che non campiona mai la
posizione dopo la crescita:

- `DragInteraction.Start` dell'utente → follow spento;
- lista che si ferma in fondo (`isScrollInProgress == false` + `isAtBottom()`)
  → follow riacceso; "invia" lo riaccende comunque.
- La firma del contenuto viene dalla riga in coda (`partsScrollSignal`), che
  include già il turno live: conta testo e ragionamento, **non** l'output dei
  tool (leggere un output lungo non deve trascinare la vista).
- `docs/UI-M6-LIVE.md` §4.4 aggiornato. Test: `ScrollSignalTest` (6 casi).

## Cambio server dalla lista (0.6.4-m5)

Non c'era modo di tornare alla schermata di accesso dalla lista sessioni (solo
drawer OMO → "Disconnetti", troppo nascosto). Ora nell'header il vecchio eyebrow
host/versione è una **pillola tappabile** (`ServerChip`) con pallino di stato e
chevron: apre un `ModalBottomSheet` "Server" con host, URL, versione e i pulsanti
`Annulla` / `Cambia server`. `Cambia server` = `AppViewModel.disconnect()`: ferma
stream, servizio e notifiche, cancella le credenziali cifrate, e riporta al
`SetupScreen` **precompilato** con l'ultimo URL/utente/password (nessuna nuova
macchina a stati). Il drawer resta invariato, con la stessa copy sulle conseguenze.

## Scoperte tecniche da non riscoprire

- `/api/experimental/session/{id}/log?seq=N` **non** riproduce la storia: risponde `log.synced` con la seq corrente.
- Eventi approvazione reali: `permission.asked` (porta l'intero request) e `permission.replied`.
- La risposta alle approvazioni usa il campo **`decision`** (`once|always|reject`), non `reply`.
- `GET /api/model` espone `settings.apiKey`: il client deve scartare `settings`.
- Nel catalogo modelli `modelID` è la famiglia (duplicata), `id` è il riferimento concreto: chiavi di lista e `Model.Ref` usano `id`.
- Un `opencode service stop` sul server di sviluppo **non tiene fermo** il servizio: riparte da solo in ~8 s.
- Heartbeat SSE ogni ~10-12 s; watchdog client 30 s.

## Contratto eventi del turno (M6)

Catturato dal server 2.1.4 e messo in fixture (`app/src/test/resources/fixtures/sse_tool.jsonl`,
`sse_reasoning.jsonl`):

- `session.reasoning.started {assistantMessageID, ordinal, native}` / `.delta {delta}` / `.ended {text}`
- `session.tool.input.started {id, name}` / `.delta` / `.ended {text = JSON dell'input}`
- `session.tool.called {id, input, executed}` / `.progress {metadata}`
- `session.tool.success {content[], metadata{status,truncated,exit}, executed}` / `.failed`
- `session.text.started {ordinal}` / `.delta {delta}` / `.ended {text}`

Gotcha: **`executed` non significa "permesso di eseguire"** — su 170 tool part reali
vale `false` anche con `state.status = completed`. L'esito vero è `state.status`
(`completed` / `error`); `Denied` solo con messaggi espliciti ("Permission denied", …).
Le part REST hanno `time.{created,ran,completed}`: la durata si calcola da lì.

## Thread aperti

1. ~~Ripristinare la password generata~~ → **decisione dell'utente: NON toccarla.**
   La password di test del servizio resta quella impostata a mano: niente
   `opencode service unset password`, niente restart per rigenerarla (24 ott 2026).
2. ~~**git init + primo commit**~~ → fatto: repo privata `github.com/mirkotappini/opencode-remote`.
3. ~~**Color i del pannello OMO**~~ → **risolto il 24 ott** (vedi sotto)
4. ~~**Ricolorazione dell'icona** al petrolio `#8ED8C6`~~ → **decaduto** (24 ott 2026):
   non interessa. La formulazione "dal blu elettrico" era comunque superata da M7,
   che ha rigenerato l'icona dal render neon dell'utente (fondo `#121510`).
5. Licenza OFL dei font da includere come nota nel repo.

## Fix colori pannello OMO (patch locale, 24 ott)

Il plugin `oh-my-opencode-slim` (fino a 3.0.5) mappa il tema v2 con chiavi
vecchie: `background.default`, `text.default`, `text.subdued`, `border.default`.
L'host 2.1.4 espone `currentTokens()` con `background.base`, `text.base`,
`text.muted`, `border.base` (e `text.feedback.*.base`), quindi sfondo, testo e
bordo del pannello cadevano sui default opentui.

- Script idempotente: `build-host:~/.config/opencode/omo-theme-fix.py` (`-v` per il dettaglio).
- Patcher riscrive `v2ThemeView()` in **tutte** le copie caricate dal plugin:
  `~/.cache/opencode/packages/oh-my-opencode-slim*/…` **e**
  `~/.cache/opencode/npm/<pkg>@latest/<hash>/…` (quest'ultima è quella davvero
  in uso: lo ha rivelato `OMO_THEME_DEBUG=1`, che scrive la vista risolta in
  `/tmp/omo-theme-debug.json`).
- I token sono oggetti colore (`toInts() -> [r,g,b,a]`), non stringhe: la patch li
  converte in `#rrggbb` prima di passarli al renderer.
- Vista risolta verificata: `#9d7cd8` accent, `#0a0a0a` bg, `#141414` raised,
  `#484848` border, `#eeeeee` text, `#808080` muted, `#1e1e1e` hover,
  `#7fd88f` success, `#f5a742` warning.
- **Da rilanciare dopo ogni update del plugin** (la dipendenza è `@latest`, quindi
  un update reintroduce il bug): in `~/.zshrc` del server di sviluppo c'è la funzione
  `opencode()` che esegue lo script prima del binario
  (`command /opt/homebrew/bin/opencode`), backup `.zshrc.bak-omo-theme`.
  Non usare `command -v opencode` dentro la funzione: in zsh ritorna il nome della
  funzione e va in ricorsione.

## Prossimo passo suggerito

Fatto il throttle (§4.4). Resta: nota licenza OFL, release con APK firmato.
Ripresa: "riprendiamo opencode remote".
