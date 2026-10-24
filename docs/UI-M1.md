# UI M1 — note di design

Data: 23 ottobre 2026. Riferimento di prodotto: `docs/DESIGN.md` §5–6, dati: `docs/API-M1.md`.

## Direzione

Strumento di controllo per sviluppatore, uso serale e a una mano. Grafite quasi nera,
superfici a gradini (`surfaceContainer*`) per la profondità. Dal tema M2 l'accento non è
più il verde-petrolio: l'app usa i **token reali del tema builtin `opencode`** del TUI
2.1.4 (dark), con accento viola per heading/keyword e arancio per l'interattivo. L'unico
alone colorato è il backdrop del setup, appena percettibile. Tema **scuro di default**
(`AppTheme()`); il tema chiaro è completo e si attiva con `AppTheme(darkTheme = false)`.

## Palette

Tabella completa token → hex, mappatura semantica e contrasti: `docs/THEME.md`.
In sintesi (dark): background `#0a0a0a`, raised `#141414`, high `#1e1e1e`, testo
`#eeeeee`, muted `#808080`, bordo `#484848`; feedback warning `#f5a742` (ragionamento),
error `#e06c75`, success `#7fd88f`, info `#56b6c2`; heading/keyword `#9d7cd8`,
interattivo `#fab283`. Bolla utente su `high`, assistente su `raised`.

## Tipografia

- **Space Grotesk** — titoli, nomi sessione, etichette display.
- **IBM Plex Sans** — testo di lettura, meta, controlli.
- **JetBrains Mono** — log, output tool, orari, conteggi.

Le tre famiglie sono incluse in `res/font/` (licenza SIL OFL, file statici da Google
Fonts). Nessuna dipendenza da `material-icons-extended`: i glifi (chevron, refresh,
lucchetto, occhio, warning, overflow) sono disegnati in Compose, con tratto coerente.

## Spaziatura e layout

Scala 4/8/12/16/24/32 (`Spacing`). Larghezze massime: setup 480 dp, lista 680 dp,
chat 920 dp con bolle all'85% della larghezza utile, log 960 dp. Sul Fold interno la
lista resta leggibile e la chat sfrutta la larghezza senza bolle lunghe.
Tutti i target interattivi sono ≥48 dp; i testi usano solo `sp` e vanno a capo.

## Scelte per schermata

- **Setup** — eyebrow + host, titolo hero, spiegazione Tailscale; card unica con i tre
  campi, toggle occhio sulla password, Connetti pieno a 54 dp; errore in banner distinto
  (`errorContainer` + bordo) e nota "credenziali solo in memoria"; IME gestito con
  `WindowInsets.safeDrawing`, Done che connette.
- **SessionList** — header compatto con host/versione e overflow (Log SSE, Disconnetti);
  righe da lista con titolo 1–2 righe, meta `agente · modello · costo`, età e badge a
  destra; divisori sottili; refresh sia pull-to-refresh sia pulsante in basso (pollice);
  skeleton pulsante a dimensioni fisse; empty state con azione; errore in banner.
- **Chat** — bolle utente a destra su `high` (`#1e1e1e`) con bordo `#484848`, assistente a
  sinistra su `raised` (`#141414`); reasoning collassabile con chevron, conteggio righe e
  `stateDescription`, chip ambra (warning) e corpo muted; tool chip compatti con nome in
  `interactive`, stato success/error e output mono con syntax; markdown minimale (heading,
  strong, inline code, link); "Carica precedenti" è un controllo
  a pillola, non una bolla; timestamp mono discreti; autoscroll all'ultimo e ancoraggio
  quando si caricano pagine precedenti; meta/status come divisore sottile.
- **EventLog** — console in mono con prefisso ora/​tipo colorato, stato live/fermo,
  Avvia/Ferma a 48 dp, empty "terminale", autoscroll se si è in fondo.
- **MainScreen** — routing invariato, avvolto in `AppTheme`; back di sistema invariato.

## Scostamenti dichiarati

- Aggiunti i font in `res/font/` (asset, non librerie) e aggiornati `themes.xml` /
  `colors.xml` per evitare il flash bianco prima di Compose. Nessuna modifica a
  manifest, permessi, rete, `net/*` o logica di `AppViewModel` (solo nuove firme di
  presentazione: `ChatScreen.onRetry`).
- Nessuna libreria di navigazione o iconografia aggiunta; nessun commit git.
