# Tema M2 — palette OpenCode 2.1.4

Data: 23 ottobre 2026. Fonte: tema builtin **`opencode`**, variante **dark**, estratta
dal binario OpenCode **2.1.4**; il TUI gira con `theme.mode=system` e tema attivo
`opencode`. I valori qui sotto sono quelli del TUI, non una reinterpretazione: l'app
li usa con la stessa semantica (heading, warning, interactive, diff, syntax).

Implementazione: `Theme.kt` (`OpenPalette` + `LocalOpenPalette`), esposta ai composable
da `OpenColors.current`. Il tema scuro resta il default (`AppTheme()`), il chiaro è
completo e si ottiene con `AppTheme(darkTheme = false)`. Nessun nuovo font.

## Token → hex (dark)

| Token | Hex | Uso nell'app |
|---|---|---|
| background | `#0a0a0a` | sfondo schermate, campo di input |
| raised | `#141414` | bolla assistente, card setup, bolla streaming |
| high | `#1e1e1e` | bolla utente, pill, menu, skeleton |
| max | `#4c4c4c` | superficie massima (scrollbar/estremi) |
| formfield | `#0a0a0a` | campi setup, composer, blocchi codice |
| text.base | `#eeeeee` | testo primario, titolo ragionamento in streaming |
| text.muted | `#808080` | corpo del ragionamento, secondaria |
| lineNumber | `#8f8f8f` | metadati, timestamp, label secondarie |
| border | `#484848` | bordi, divisori |
| scrollbar | `#606060` | outline, indicatori |
| error | `#e06c75` | errore, tool negato, log error |
| warning | `#f5a742` | ragionamento, interrotto, riconnessione, tool in corso |
| success | `#7fd88f` | attiva/connessa, tool ok, SSE live |
| info | `#56b6c2` | "in coda", linkText, eventi system |
| accent (purple) | `#9d7cd8` | heading/keyword, titoli di schermata, eventi session |
| interactive (orange) | `#fab283` | link, nomi tool, azioni, cursore |
| diff added | `#4fd6be` / `#20303b` | testo / sfondo |
| diff removed | `#c53b53` / `#37222c` | testo / sfondo |
| diff context | `#828bb8` / `#141414` | testo / sfondo |
| diff highlight | `#b8db87` aggiunto, `#e26a75` rimosso | evidenziazione |
| syntax comment | `#808080` | commenti |
| syntax keyword | `#9d7cd8` | keyword, chiavi JSON |
| syntax function | `#fab283` | funzioni, valore di `command` |
| syntax variable | `#e06c75` | variabili |
| syntax string | `#7fd88f` | stringhe, codice inline/markdown |
| syntax number | `#f5a742` | numeri |
| syntax type | `#e5c07b` | tipi, citazioni/emphasis markdown |
| syntax operator | `#56b6c2` | operatori, `:` e `,` JSON |
| syntax punctuation | `#eeeeee` | punteggiatura |

Markdown: heading `#9d7cd8`, link `#fab283`, linkText `#56b6c2`, code `#7fd88f`
(su surface `#141414`), blockQuote/emphasis `#e5c07b`, strong `#f5a742`, hr `#808080`.

## Token → hex (light)

Derivato con gli stessi gradini di superficie del dark (`background → raised → high →
max`), in discesa dal bianco. I feedback sono scuriti per restare AA su fondo bianco;
`warning` e `muted` restano i token richiesti, l'inchiostro leggibile è `warningInk` /
`mutedText`.

| Token | Hex | Note |
|---|---|---|
| background | `#ffffff` | |
| raised | `#f5f5f5` | |
| high | `#ebebeb` | |
| max | `#c3c3c3` | |
| formfield | `#ffffff` | |
| text.base | `#1a1a1a` | |
| text.muted | `#8a8a8a` | token di riferimento |
| mutedText | `#5f5f5f` | inchiostro AA per la secondaria |
| border | `#b8b8b8` | |
| warning | `#d68c27` | token di riferimento |
| warningInk | `#8a5a12` | inchiostro AA per testi/icone warning |
| error | `#b23b47` | |
| success | `#1f7a3d` | |
| info | `#0e6f7d` | |
| accent | `#6b4bb5` | |
| interactive | `#b45309` | |

## Mappatura semantica

1. **Ragionamento** — chip chevron + "Ragionamento": ambra piena se espanso; ambra 60%
   se collassato e concluso (solo dark, parità TUI); etichetta `text.base` durante lo
   streaming, con punto ambra pulsante. Corpo su `formfield`, testo `text.muted`.
2. **Tool** — nome `interactive`; stato ok `success`, in corso `warning`, errore/negato
   `error`; output mono con syntax: chiavi `keyword`, stringhe `string`, numeri `number`,
   `true/false/null` `type`, `:`/`,` `operator`, valore di `command/path/pattern` `function`.
3. **Markdown** — heading `accent`, strong `warning`, inline code `success` su `raised`,
   link `interactive`, linkText `info`, citazioni `type`, hr `muted`. Parser minimale
   interno (nessuna dipendenza): titoli, strong, corsivo, inline code, blocchi, citazioni,
   link, hr.
4. **Bolle** — utente su `high` con bordo `border`; assistente su `raised` con bordo
   attenuato; nessun gradiente.
5. **Stati** — attiva/connessa `success`; interrotta/riconnessione `warning`; errore
   `error`; in coda `info`; SSE live `success`.
6. **Setup / lista / log** — superficie a gradini, card e console su `raised`/`background`,
   bordi `border`, secondaria `muted`; log SSE con colore per tipo evento (error, system,
   tool, session, message).
7. **Tipografia** — invariata: Space Grotesk (display), IBM Plex Sans (testo),
   JetBrains Mono (dati tecnici). Tutte le misure restano in `sp`; i target restano ≥48 dp.

## Contrasto (WCAG AA)

Coppie usate, rapporto misurato:

| Coppia | Dark | Light |
|---|---|---|
| text.base / background | 17.0 | 17.4 |
| text.base / raised | 15.9 | 16.0 |
| text.muted / background (corpo ragionamento) | 5.01 | 6.39 (`mutedText`) |
| lineNumber / high | 5.15 | 5.04 |
| accent / raised | 5.53 | 5.84 |
| interactive / raised | 10.3 | 4.61 |
| success / background | 11.4 | 5.37 |
| warningInk / raised | 9.20 | 5.42 |
| error / raised | 5.76 | 5.34 |
| info / background | 8.36 | 5.85 |

Nota di parità: nel dark il chip del ragionamento collassato e concluso usa l'ambra al
60% come nel TUI (`#f5a742` @ 0.6 su `#141414` → ~4.0:1). È l'unico caso volutamente
sotto 4.5, richiesto per fedeltà al TUI; lo stato è comunque veicolato da chevron,
conteggio righe e "apri". Nel chiaro l'inchiostro resta pieno (`warningInk`) perché
`#8a5a12` al 60% scenderebbe a ~2.5:1. Il resto delle coppie di testo è ≥4.5:1.

## Solo presentazione

Modificati `Theme.kt` e i quattro schermi (`ChatScreen`, `SessionListScreen`,
`SetupScreen`, `EventLogScreen`), più `colors.xml` per evitare il flash del vecchio
sfondo prima di Compose. Non toccati `net/*`, `AppViewModel.kt`, manifest, permessi,
logica di rete o di stato; nessuna nuova dipendenza e nessun commit git.
