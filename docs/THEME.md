# M2 theme — OpenCode 2.1.4 palette

Date: Oct 23 2026. Source: builtin **`opencode`** theme, **dark** variant, extracted
from the OpenCode **2.1.4** binary; the TUI runs with `theme.mode=system` and active
theme `opencode`. The values below are the TUI ones, not a reinterpretation: the app
uses them with the same semantics (heading, warning, interactive, diff, syntax).

Implementation: `Theme.kt` (`OpenPalette` + `LocalOpenPalette`), exposed to the composable
via `OpenColors.current`. The dark theme stays the default (`AppTheme()`), the light theme is
complete and obtained with `AppTheme(darkTheme = false)`. No new font.

## Token → hex (dark)

| Token | Hex | Use in the app |
|---|---|---|
| background | `#0a0a0a` | screen background, input field |
| raised | `#141414` | assistant bubble, setup card, streaming bubble |
| high | `#1e1e1e` | user bubble, pill, menu, skeleton |
| max | `#4c4c4c` | maximum surface (scrollbar/extremes) |
| formfield | `#0a0a0a` | setup fields, composer, code blocks |
| text.base | `#eeeeee` | primary text, streaming reasoning title |
| text.muted | `#808080` | reasoning body, secondary |
| lineNumber | `#8f8f8f` | metadata, timestamps, secondary labels |
| border | `#484848` | borders, dividers |
| scrollbar | `#606060` | outline, indicators |
| error | `#e06c75` | error, denied tool, error log |
| warning | `#f5a742` | reasoning, interrupted, reconnection, tool in progress |
| success | `#7fd88f` | active/connected, tool OK, SSE live |
| info | `#56b6c2` | "queued", linkText, system events |
| accent (purple) | `#9d7cd8` | heading/keyword, screen titles, session events |
| interactive (orange) | `#fab283` | links, tool names, actions, cursor |
| diff added | `#4fd6be` / `#20303b` | text / background |
| diff removed | `#c53b53` / `#37222c` | text / background |
| diff context | `#828bb8` / `#141414` | text / background |
| diff highlight | `#b8db87` added, `#e26a75` removed | highlighting |
| syntax comment | `#808080` | comments |
| syntax keyword | `#9d7cd8` | keywords, JSON keys |
| syntax function | `#fab283` | functions, value of `command` |
| syntax variable | `#e06c75` | variables |
| syntax string | `#7fd88f` | strings, inline code/markdown |
| syntax number | `#f5a742` | numbers |
| syntax type | `#e5c07b` | types, markdown quotes/emphasis |
| syntax operator | `#56b6c2` | operators, JSON `:` and `,` |
| syntax punctuation | `#eeeeee` | punctuation |

Markdown: heading `#9d7cd8`, link `#fab283`, linkText `#56b6c2`, code `#7fd88f`
(on surface `#141414`), blockQuote/emphasis `#e5c07b`, strong `#f5a742`, hr `#808080`.

## Token → hex (light)

Derived with the same surface steps as the dark (`background → raised → high →
max`), descending from white. The feedback colors are darkened to stay AA on a white
background; `warning` and `muted` stay the requested tokens, the legible ink is `warningInk` /
`mutedText`.

| Token | Hex | Note |
|---|---|---|
| background | `#ffffff` | |
| raised | `#f5f5f5` | |
| high | `#ebebeb` | |
| max | `#c3c3c3` | |
| formfield | `#ffffff` | |
| text.base | `#1a1a1a` | |
| text.muted | `#8a8a8a` | reference token |
| mutedText | `#5f5f5f` | AA ink for the secondary |
| border | `#b8b8b8` | |
| warning | `#d68c27` | reference token |
| warningInk | `#8a5a12` | AA ink for warning text/icons |
| error | `#b23b47` | |
| success | `#1f7a3d` | |
| info | `#0e6f7d` | |
| accent | `#6b4bb5` | |
| interactive | `#b45309` | |

## Semantic mapping

1. **Reasoning** — chevron chip + "Reasoning": full amber if expanded; amber 60%
   if collapsed and concluded (dark only, TUI parity); label `text.base` during
   streaming, with a pulsing amber dot. Body on `formfield`, text `text.muted`.
2. **Tool** — name `interactive`; state OK `success`, in progress `warning`, error/denied
   `error`; output mono with syntax: keys `keyword`, strings `string`, numbers `number`,
   `true/false/null` `type`, `:`/`,` `operator`, value of `command/path/pattern` `function`.
3. **Markdown** — heading `accent`, strong `warning`, inline code `success` on `raised`,
   link `interactive`, linkText `info`, quotes `type`, hr `muted`. Minimal
   internal parser (no dependency): titles, strong, italic, inline code, blocks, quotes,
   links, hr.
4. **Bubbles** — user on `high` with `border`; assistant on `raised` with a
   subdued border; no gradient.
5. **States** — active/connected `success`; interrupted/reconnecting `warning`; error
   `error`; queued `info`; SSE live `success`.
6. **Setup / list / log** — stepped surfaces, cards and console on `raised`/`background`,
   borders `border`, secondary `muted`; SSE log colored per event type (error, system,
   tool, session, message).
7. **Typography** — unchanged: Space Grotesk (display), IBM Plex Sans (text),
   JetBrains Mono (technical data). All sizes stay in `sp`; the targets stay ≥48 dp.

## Contrast (WCAG AA)

Pairs used, measured ratio:

| Pair | Dark | Light |
|---|---|---|
| text.base / background | 17.0 | 17.4 |
| text.base / raised | 15.9 | 16.0 |
| text.muted / background (reasoning body) | 5.01 | 6.39 (`mutedText`) |
| lineNumber / high | 5.15 | 5.04 |
| accent / raised | 5.53 | 5.84 |
| interactive / raised | 10.3 | 4.61 |
| success / background | 11.4 | 5.37 |
| warningInk / raised | 9.20 | 5.42 |
| error / raised | 5.76 | 5.34 |
| info / background | 8.36 | 5.85 |

Parity note: in the dark the collapsed and concluded reasoning chip uses amber at
60% as in the TUI (`#f5a742` @ 0.6 on `#141414` → ~4.0:1). It is the only case deliberately
below 4.5, required for fidelity to the TUI; the state is still conveyed by the chevron,
the line count and "open". In the light the ink stays full (`warningInk`) because
`#8a5a12` at 60% would drop to ~2.5:1. The rest of the text pairs are ≥4.5:1.

## Presentation only

Changed `Theme.kt` and the four screens (`ChatScreen`, `SessionListScreen`,
`SetupScreen`, `EventLogScreen`), plus `colors.xml` to avoid the flash of the old
background before Compose. Not touched `net/*`, `AppViewModel.kt`, manifest, permissions,
network or state logic; no new dependency and no git commit.
