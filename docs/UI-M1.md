# UI M1 — design notes

Date: Oct 23 2026. Product reference: `docs/DESIGN.md` §5–6, data: `docs/API-M1.md`.

## Direction

Control tool for a developer, evening and one-handed use. Near-black graphite,
stepped surfaces (`surfaceContainer*`) for depth. From the M2 theme the accent is
no longer the petrol-green: the app uses the **real tokens of the builtin `opencode`** theme of the TUI
2.1.4 (dark), with a purple accent for heading/keyword and orange for the interactive. The only
colored halo is the setup backdrop, just barely perceptible. **Dark theme by default**
(`AppTheme()`); the light theme is complete and enabled with `AppTheme(darkTheme = false)`.

## Palette

Full token → hex table, semantic mapping and contrasts: `docs/THEME.md`.
In short (dark): background `#0a0a0a`, raised `#141414`, high `#1e1e1e`, text
`#eeeeee`, muted `#808080`, border `#484848`; feedback warning `#f5a742` (reasoning),
error `#e06c75`, success `#7fd88f`, info `#56b6c2`; heading/keyword `#9d7cd8`,
interactive `#fab283`. User bubble on `high`, assistant on `raised`.

## Typography

- **Space Grotesk** — titles, session names, display labels.
- **IBM Plex Sans** — reading text, meta, controls.
- **JetBrains Mono** — log, tool output, times, counts.

The three families are included in `res/font/` (SIL OFL license, static files from Google
Fonts). No dependency on `material-icons-extended`: the glyphs (chevron, refresh,
lock, eye, warning, overflow) are drawn in Compose, with a coherent stroke.

## Spacing and layout

Scale 4/8/12/16/24/32 (`Spacing`). Maximum widths: setup 480 dp, list 680 dp,
chat 920 dp with bubbles at 85% of the usable width, log 960 dp. On the Fold inner
the list stays legible and the chat uses the width without long bubbles.
All interactive targets are ≥48 dp; the texts use only `sp` and wrap.

## Choices per screen

- **Setup** — eyebrow + host, hero title, Tailscale explanation; a single card with the three
  fields, eye toggle on the password, full "connect" at 54 dp; error in a distinct banner
  (`errorContainer` + border) and a "credentials in memory only" note; IME handled with
  `WindowInsets.safeDrawing`, Done that connects.
- **SessionList** — compact header with host/version and overflow (SSE log, Disconnect);
  list rows with a 1–2 line title, meta `agent · model · cost`, age and a badge on
  the right; thin dividers; refresh both pull-to-refresh and a bottom button (thumb);
  fixed-size pulsing skeleton; empty state with action; error in a banner.
- **Chat** — user bubbles on the right on `high` (`#1e1e1e`) with border `#484848`, assistant on
  the left on `raised` (`#141414`); collapsible reasoning with a chevron, line count
  and `stateDescription`, amber (warning) chip and muted body; compact tool chip with the name in
  `interactive`, success/error state and mono output with syntax; minimal markdown (heading,
  strong, inline code, links); "load previous" is a pill
  control, not a bubble; discreet mono timestamps; auto-scroll to the last and anchoring
  when loading previous pages; meta/status as a thin divider.
- **EventLog** — mono console with a colored time/type prefix, live/stop state,
  Start/Stop at 48 dp, empty "terminal", auto-scroll if at the bottom.
- **MainScreen** — routing unchanged, wrapped in `AppTheme`; system back unchanged.

## Declared deviations

- Added the fonts in `res/font/` (assets, not libraries) and updated `themes.xml` /
  `colors.xml` to avoid the white flash before Compose. No change to
  manifest, permissions, network, `net/*` or `AppViewModel` logic (only a new presentation
  signature: `ChatScreen.onRetry`).
- No navigation or iconography library added; no git commit.
