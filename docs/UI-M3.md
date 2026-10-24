# UI M3 — drawer "sidebar OMO"

Data: 24 ottobre 2026. Riferimenti: `docs/DESIGN.md` §6–§7, `docs/API-M3.md`,
`docs/THEME.md`. Dati: `UiState.catalog`, `UiState.children`, `UiState.openSession`.

## Struttura

`ModalNavigationDrawer` con lo sheet sul **lato end (destro)**, disponibile da lista e
chat; il setup ne resta fuori. Material 3 àncora lo sheet a start: il sottoalbero gira
in layout RTL e drawer e contenuto tornano subito in LTR. Non è una reimplementazione:
gesture, scrim e predictive back restano quelli del componente. `gesturesEnabled` è
legato allo stato (`targetValue == Open`): da chiuso Material non accetta swipe su
tutta la superficie (apre solo la striscia di bordo descritta sotto); da aperto restano
attivi lo swipe di chiusura sullo sheet e il tap sullo scrim.

- Larghezza: il massimo dello sheet Material è **360 dp** (con `fillMaxWidth` su schermi
  più stretti scende fino alla larghezza disponibile). Su telefono sta nel range
  320–360 dp; su Fold interno resta laterale e copre ~metà schermo, mai una pagina
  intera. Il contenuto della schermata non viene ridimensionato (drawer modale): nessun
  salto di layout all'apertura.
- **Apertura**: icona `GlyphSidebar` nella top bar di lista e chat (48 dp, content
  description "Apri il pannello di agenti, skill e MCP") **e** swipe dal bordo
  destro verso sinistra. Chiusura con icona ×, tap sullo scrim e **back di sistema**
  (`ModalDrawerSheet` con `drawerState`: predictive back su Android 14+, fallback
  `BackHandler` sotto).
- Il drawer si chiude quando si apre una sessione, si va al log SSE o ci si disconnette.

### Gesto di bordo e back di sistema

A drawer chiuso lo swipe è rilevato **solo negli ultimi 22 dp** dal bordo destro e
richiede un trascinamento orizzontale di almeno 26 dp (componente verticale esclusa)
prima di aprire; nessun'altra zona della schermata apre il pannello. Il rilevatore è sul contenitore della schermata, non su una striscia sopra la
UI: tap e scroll dei figli non vengono intercettati. Non si registrano
`systemGestureExclusionRects`: nella navigazione a gesti Android il back di sistema
nella zona di bordo ha la precedenza, ed è giusto così. Conseguenza dichiarata: con la
navigazione a gesti lo swipe può essere letto dal sistema; **l'icona in top bar è la
via affidabile**, lo swipe è un'abitudine in più (e funziona con la navigazione a tre
pulsanti). La soglia stretta evita di rubare il gesto di sistema come richiesto.

## Contenuto

**Header** — indicatore di connessione (verde connesso, ambra in connessione, rosso in
errore), host e versione, nome del progetto (`catalog.location.projectName`, fallback
sul basename della directory) e path della directory con ellissi; la pill di
connessione sta sulla stessa riga del path, a destra. Sotto, la **riga della sessione
aperta** — è contesto, non navigazione. Mostra titolo su una riga con ellissi, pallino
di stato (`attiva` pulsante; altrimenti l'esito `ok` / `errore` / `interrotta`, o
`inattiva`), `agente · modello` con `predefinito` quando il server non li ha ancora
assegnati, il badge delle approvazioni pendenti (M4) e, a destra, il **badge dei task**
con il numero dei figli in esecuzione. Senza sessione aperta la riga è sostituita dal
testo muted "nessuna sessione aperta": nessuna azione, coerente con i tab Agenti e
Skill che restano disabilitati. In basso, il footer tiene **Log SSE** (info) e
**Disconnetti** (error), entrambi a 48 dp.

**Task in background** — il badge dei task (target 48 dp, descrizione TalkBack) apre un
**bottom sheet compatto** con i figli (`childrenWithActivity`): titolo, chip dell'agente
che sta lavorando (`predefinito` se il server non l'ha ancora assegnato), modello in
mono, stato live — `in esecuzione`, `concluso · ok`, `concluso · errore`, `interrotta`,
`in attesa` — badge approvazioni, refresh nella testata e rilettura automatica
all'apertura. Tap su un figlio → apre quella sessione e chiude foglio e drawer. Se non
ci sono figli, il foglio spiega che i task sono le sessioni figlie della sessione
aperta. Nessun elenco "recenti" nel drawer: le sessioni si aprono dalla schermata
principale.

Perché: la vecchia tab Sessioni ripeteva la schermata principale (stessa lista, stessi
tap) e toglieva spazio ai cataloghi. Il drawer ora risponde a una sola domanda — su cosa
sto lavorando e con quali agenti/skill/MCP — e la navigazione tra sessioni resta dove
appartiene.

**Tab** — segmented control compatto (**Agenti · Skill · MCP**), 48 dp per segmento,
`animateColorAsState` sul cambio, `Role.Tab` e stato selezionato per TalkBack. La tab
attiva resta in `rememberSaveable`: sopravvive alla rotazione e alla chiusura del
drawer. Con tre voci i segmenti sono più larghi e le etichette non si comprimono.

- **Agenti** — in testa, con una sessione aperta, la riga **Agente** con il nome di chi
  guida la sessione e la spunta verde (solo se è un agente primario); poi la card
  **modello** (agente e modello correnti, tap → picker). Seguono
  `catalog.agentsForDrawer` con descrizione su 2 righe e riferimento `provider/modello`,
  e la sezione **Subagent** in **sola lettura**: nessun tap, nessuna spunta, nessun
  spinner, righe attenuate (75%) e microcopy "richiamati automaticamente dall'agente
  primario". La delega la decide l'agente primario, come nella TUI: la lista serve solo
  a capire cosa può essere delegato e con quale modello, non a impostare l'agente della
  sessione. Tap su un primario → `switchAgent` con spinner e conferma dal GET
  successivo (il server è la verità), timeout di 9 s se non arriva nulla. Senza sessione
  aperta: hint "apri una sessione" e righe primarie disabilitate (55% di alpha).
- **Indicatore di lavoro** — accanto al nome di ogni agente (primari e subagent) un arco
  `success` da 12 dp ruota in 1,2 s quando esiste almeno un figlio in esecuzione con
  `agent == agent.id`; con più di un figlio attivo affianca il conteggio. La mappa
  agente → figli attivi si costruisce in presentazione da `childrenWithActivity`
  (nessuna chiamata in più). Lo stesso indicatore sostituisce il pallino statico nella
  card della sessione quando è attiva e nelle righe del foglio dei task quando il figlio
  sta girando. Con gli animatori di sistema spenti ("rimuovi animazioni") diventa un
  pallino fermo; la descrizione TalkBack dice "task in esecuzione" / "sessione in
  esecuzione".
- **Skill** — nome e descrizione su 2 righe; tap → `activateSkill` sulla sessione
  aperta, con conferma effimera. Stessa regola sulla sessione aperta.
- **MCP** — sola lettura: pallino verde `connected`, ambra per `needs_auth`/stato
  sconosciuto, rosso per `failed`, grigio per `disabled`, più l'etichetta italiana.

**Stati** — skeleton compatto durante il primo caricamento, errore con "Riprova" che
chiama `loadCatalog(force = true)`, catalogo vuoto con spiegazione e azione, errore di
azione dismissibile. Il TTL di 5 minuti è rispettato: aprire il drawer non forza il
refresh, il pulsante di refresh nel drawer sì (sempre visibile).

## Picker modello

Dialog a schermo intero (Material 3 non ha bisogno di altro): ricerca in testa
(nome, provider, `modelID`, famiglia), **90 modelli raggruppati per provider**, con
intestazione provider + conteggio, nome leggibile e `provider · modelID` in mono,
badge `in uso` sulla voce corrente. Nessuna lista piatta senza filtro. Conteggio
`N/totale` durante la ricerca, stati "nessun risultato" e "nessun modello" con azione.
La selezione chiama `switchModel(providerId, modelID, variant = null)` e chiude il
picker; la conferma arriva dal valore riletto nella sessione.

## Casi reali rispettati

- Una sessione **mai usata non ha `agent`/`model`**: la riga della sessione e i task
  mostrano `predefinito` (mai una riga vuota), la card modello mostra "predefinito ·
  modello predefinito" e il picker resta utilizzabile — non serve un modello corrente
  per sceglierne uno.
- `POST .../agent` e `POST .../model` rispondono 204 e il GET successivo riflette il
  valore: la UI non assume il successo locale, aspetta la sessione riletta dal
  ViewModel per confermare (e mostra l'errore se arriva).

## Palette, tipografia, accessibilità

Solo token TUI (`OpenColors` / `ColorScheme`): accento `#9D7CD8` per il nome progetto,
interattivo `#FAB283` per tab e voci toccabili, `success` per attivo/connesso,
`warning` per attesa, `error` per errore/disconnessione, `info` per i task figli e le
guide. Nessun colore fuori palette, nessun gradiente oltre all'alone d'accento
nell'header. Space Grotesk per i nomi, IBM Plex Sans per il testo, JetBrains Mono per
path, età e riferimenti modello. Ogni target è ≥48 dp (tab, badge, refresh, righe),
le icone hanno content description, il segmented control espone ruolo e stato.

## Modifiche di codice

- Nuovi: `OmoDrawer.kt` (sheet, tab, righe, stati), `ModelPicker.kt` (picker + ricerca),
  `docs/UI-M3.md`.
- `Theme.kt`: quattro glifi disegnati (`GlyphSidebar`, `GlyphClose`, `GlyphSearch`,
  `GlyphCheck`), stesso tratto degli altri, più `WorkingIndicator` (arco animato 1,2 s,
  fermo se `ValueAnimator.areAnimatorsEnabled()` è falso).
- `UiFormat.kt`: `drawerSessionMeta` (fallback "predefinito") e `modelRefLabel`.
- `AppViewModel.kt`: **solo helper di presentazione dichiarati** — `openSessionRow`
  (apre una sessione nota al drawer, es. un task figlio, senza perderne il titolo),
  `UiState.childrenWithActivity` / `runningChildren` (ricongiungono i figli con
  l'attività osservata sulle sessioni: `GET ?parentID=` non porta uno stato live, gli
  eventi SSE aggiornano `sessions`). Nessuna logica o chiamata di rete nuova.
- `MainScreen.kt`: contenitore con drawer + rilevatore di bordo; `ChatScreen` e
  `SessionListScreen` ricevono `onOpenDrawer` e mostrano l'icona in top bar. L'overflow
  della lista (Log SSE, Disconnetti) è stato spostato nel footer del drawer.
- Revisione `0.5.2-m4`: rimossi `SessionsTab`, `SessionFilter`, il filtro e le righe
  delle sessioni recenti; aggiunti `OpenSessionCard`, `TasksSheet` (`ModalBottomSheet`),
  `TaskRow` (chip agente + stato live), `SessionAgentRow` e `AgentChip`. Subagent in
  sola lettura, indicatore di lavoro mappato sui figli attivi. Il drawer non riceve più
  `onRefreshSessions` (la lista resta nella schermata principale).
- Version `0.4.0-m3` / `4`; revisione drawer **`0.5.2-m4` / `7`** (3 tab, riga sessione,
  bottom sheet dei task). Nessuna modifica a `net/*`, DTO, manifest o API docs; nessun
  commit git.
