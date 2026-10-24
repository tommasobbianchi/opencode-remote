# UI M4 — approvazioni

Data: 24 ottobre 2026. Riferimenti: `docs/API-M4.md`, `docs/DESIGN.md` §9,
`docs/THEME.md`, `docs/UI-M1.md`, `docs/UI-M3.md`.

Dati: `UiState.approvals`, `approvalsLoading`, `approvalsError`,
`pendingApprovals`, `replyApproval()`, `refreshApprovals()`. Il backend è
invariato: eventi SSE, `GET …/permission`, risposta `decision` e 404
`PermissionNotFoundError` restano come da contratto.

## Pannello in chat

Il pannello vive **sopra il composer**, dentro la colonna della chat: non è un
dialog e non blocca la lettura della conversazione. Compare con una transizione
breve (fade + espansione) solo quando `approvals` non è vuota e sparisce appena
l'ultima richiesta viene risolta.

- **Titolo** — azione + prima risorsa, in mono: `read perm-probe.env`,
  `bash rm -rf /tmp/x`. Se ci sono altre risorse, un chip `+N risorse`; se il
  server indica regole da salvare, un chip `save +N`. Il conteggio `1/2` è
  accanto al titolo.
- **Riga secondaria** — origine discreta in mono, `origine tool · per_3f9a…`.
  `ApprovalUi` espone `sourceLabel` (il tipo, es. `tool`) e il `requestID`: il
  vecchio campo `source.id` non è nel modello UI, quindi l'id troncato mostrato
  è quello della richiesta, non del tool. Se `message` è presente, compare
  sotto in `bodySmall` muted, massimo 3 righe.
- **Azioni** — *Consenti una volta* è l'unica azione piena (interactive
  `#FAB283`, testo scuro), *Nega* è un outlined neutro, *Consenti sempre* è un
  text button ambra (`warningInk`, nel dark `#F5A742`) separato sulla riga
  sotto, con la nota "vale per sempre sul server". Nessun default su "sempre".
- **Pila** — con più richieste la selezionata è espansa e le altre restano in
  righe compatte da 48 dp (massimo 3, poi `+N altre richieste`). Si cambia con
  swipe orizzontale sulla scheda (soglia 64 dp), con le frecce nella testata o
  toccando una riga. Durante l'invio la navigazione è disabilitata, così lo
  spinner resta sulla richiesta premuta.
- **Invio** — lo stato è locale al pannello: spinner nell'azione premuta e
  tutte le azioni disabilitate. Il pulsante di refresh nella testata mostra uno
  spinner mentre `approvalsLoading` è vero.
- **404** — `richiesta non più valida` diventa una nota informativa (`info`,
  non `error`): "Richiesta non più attiva: era già stata risolta. La lista è
  stata aggiornata." Il ViewModel ha già rifatto la GET. Anche il banner
  generale della chat usa la stessa nota discreta con "Chiudi" invece del
  banner rosso.

## Badge

- **Top bar della chat** — badge compatto ambra con il numero, accanto allo
  stato della sessione. Non è rosso: una richiesta da approvare non è un guasto.
- **Lista sessioni** — badge nella colonna di destra della riga, sopra età e
  stato.
- **Drawer** — badge sulla card della sessione aperta e su ogni riga dei task nel
  foglio dei task. La vecchia tab Sessioni con il pallino ambra è stata rimossa in
  `0.5.2-m4` (vedi `docs/UI-M3.md`): il drawer non duplica più la lista. La
  descrizione per TalkBack dice quante approvazioni sono in attesa.

I badge usano `UiState.pendingApprovals`, una mappa `sessione → requestID`
alimentata dagli eventi `permission.asked` / `permission.replied` e dalla GET
della sessione aperta. È **solo presentazione**: nessuna nuova chiamata di rete,
nessuna seconda fonte di verità. Una richiesta risolta da app o notifica esce
subito dalla mappa; per le altre il badge resta finché una GET della sessione
non le smentisce.

## Permesso notifiche

Al primo ingresso nella superficie connessa (quindi dopo un `connect()`
riuscito) l'app mostra una riga informativa non modale — "Per approvare anche da
fuori dall'app, consenti le notifiche." — e apre una sola volta il dialogo di
sistema `POST_NOTIFICATIONS` su Android 13+. L'esito viene ricordato in
preferenze private (`NotificationOptInStore`): agli avvii successivi non viene
richiesto di nuovo, né dopo un consenso né dopo un rifiuto. Se il permesso è
negato, l'avviso diventa "Notifiche non attive: le approvazioni restano qui in
chat" con azione **Impostazioni** (`ACTION_APP_NOTIFICATION_SETTINGS`) e
**Chiudi**. Il pannello in-app continua a funzionare in ogni caso.

## Copy del setup

La vecchia nota "Le credenziali restano solo in memoria" non era più vera. Ora
il setup dice: **"Credenziali cifrate sul dispositivo con il Keystore.
«Disconnetti» le cancella."** Il pulsante resta "Connetti".

## Notifiche

In `ApprovalNotifier` sono cambiate solo le stringhe, non la dedupe, gli id o
l'annullamento:

- titolo: `Approvazione richiesta · OpenCode`;
- corpo: azione + prima risorsa (`read perm-probe.env`), eventuale `+N`,
  `message` e, se presente, `Sempre salverebbe: …`;
- azioni: **Consenti**, **Nega**, **Sempre…** (quest'ultima apre l'app: la
  conferma esplicita resta in-app come da DESIGN §9).

Il nome del progetto/agente non è disponibile in `ApprovalUi` e non si è
voluto leggere il token nel notifier solo per comporre il titolo: "OpenCode" è
il contesto stabile e comprensibile.

## Disconnessione

"Disconnetti" nel footer del drawer ora apre una conferma esplicita: il token
salvato e le credenziali cifrate vengono cancellati, la connessione in
background e le notifiche si fermano. È un modale usato solo per un'azione
distruttiva, non per le approvazioni.

## Palette, accessibilità, misure

Solo token TUI: `warningInk` per le approvazioni e i badge, `interactive` per
l'azione primaria e i link, `info` per la nota 404 e l'avviso notifiche,
`error` solo per la disconnessione e gli errori veri. Nessun colore fuori
palette. Ogni azione e ogni freccia ha un target di 48 dp, le descrizioni
TalkBack chiariscono numero e posizione della richiesta; i testi vanno a capo e
il pannello non copre la conversazione.

## Modifiche di codice

- Nuovi: `ApprovalPanel.kt` (pannello, pila, conferma "sempre", nota 404),
  `NotificationOptIn.kt` (avviso non modale + memoria del primo ask),
  `docs/UI-M4.md`.
- `ChatScreen.kt`: pannello sopra il composer, badge in top bar, avviso
  notifiche, nota 404 al posto del banner rosso.
- `SessionListScreen.kt` / `OmoDrawer.kt`: badge per riga, pallino sulla tab
  Sessioni, conferma di disconnessione.
- `Theme.kt`: `ApprovalBadge` condiviso (ambra, descrizione TalkBack).
- `SetupScreen.kt`: nuova nota su Keystore e Disconnetti.
- `ApprovalNotifier.kt`: solo i testi di titolo, corpo e azioni.
- `AppViewModel.kt`: **solo presentazione** — mappa `pendingApprovals` derivata
  dagli eventi e dalla GET già esistenti, helper `approvalCount` /
  `approvalCountTotal`, azzeramento dell'errore precedente all'invio. Nessuna
  modifica a `net/*`, `security/TokenStore.kt`, `ApprovalService` o receiver.
- Version `0.5.0-m4` / `5`. Nessun commit git.
