# Blaze glass design system (v0.31.0, phase 1)

Greyscale frosted glass on a near-black ground, taken from the app icon: translucent white fill (6–14 % alpha),
a 1 dp rim bright at the top-left (white 30 % → 5 %), a faint specular sheen in the top-left corner, a soft shadow.
Accents are white / greys. Colour only for errors / warnings, desaturated (`GlassTokens.error`, `.warning`, `.success`).

Code: `app/src/main/java/com/blaze/assistant/ui/GlassKit.kt` (components), `Theme.kt` (palette + Material scheme),
`BlazeGlass.kt` (`Glass` object + `GlassIconButton`), `StreamOrb.kt` (water droplet), `RichPreview.kt` (detached
rich-block windows).

## Tokens
| Token | Use |
|---|---|
| `GlassTokens.bg`, `.bgTop` | screen ground (dark gradient) |
| `GlassTokens.ink / body / sub / weak` | text levels |
| `GlassTokens.accent / onAccent` | monochrome accent (focus, on states) and the ink on it |
| `GlassTokens.error / warning / success` | desaturated semantic colours |
| `GlassTokens.cardShape (22dp) / tileShape (16dp) / sheetShape / pillShape` | shapes |
| `GlassIntensity.Low / Medium / High` | frost strength: rows & inputs / cards / sheets, dialogs, primary buttons |
| `LocalBlaze.current` (`BlazeColors`) | unchanged API; dark palette now = glass palette |

`ThemeMode` "system" now means dark (the look is dark-first); light still works when picked. Material3's
`colorScheme` maps to the same greys, so stock Material components pick the look up automatically.

## Components
```kotlin
Modifier.glass(shape = GlassTokens.cardShape, intensity = GlassIntensity.Medium, shadow = true, sheen = true)
Modifier.glassBlur(24.dp)            // API 31+ RenderEffect blur of a decorative layer (no-op < 31 / low-RAM)
Modifier.glassBackground(dark)       // the screen ground with two soft grey glows

GlassSurface(modifier, shape, intensity, onClick = null) { /* BoxScope */ }
GlassCard(modifier, intensity, shape, padding = PaddingValues(16.dp), onClick = null) { /* ColumnScope */ }
GlassPill(modifier, intensity = Low, onClick = null) { /* RowScope */ }
GlassButton("Save", onClick = { }, primary = true)          // text button; danger = true for destructive
GlassIconButton(Icons.Rounded.Close, "Close") { }           // round frosted icon button
GlassTopBar("Settings", navigation = { BackButton(onBack) }, actions = { … })
GlassSheet { … }                                            // bottom sheet surface with grab handle
GlassViewer(title, onDismiss, subtitle) { /* BoxScope */ }  // full-screen glass window (Dialog) with ×
GlassDivider()
```
Existing kit pieces were restyled in place and keep their names: `BlazeButton` / `KitButton` (glass pills),
`BlazeCard`, `Rows`, `BlazeDialog(Card)`, `BlazeMenu(Card)`, `PillButton`, `DialogColors`, `BlazeColors.raised /
hair2 / field / fieldLine / backFill` (translucent white on dark). `BlazeBrush.primary` is now a bright frost
that white text reads on; `BlazeBrush.blue / orange` are greys / a desaturated warm.

Rules for phase 2: no raw `background(Color(0xFF…))` slabs; put surfaces on `Modifier.glass`; no blue; inside
another glass surface pass `shadow = false`; dialogs and menus over content add `background(DialogColors.surface)`
under the glass so text stays readable.

Blur note: Compose can't blur what is *behind* a layer, only a layer's own content, so the frost is drawn
(fill + sheen + rim); use `glassBlur` on backgrounds where a real blur helps.

## Motion
`MotionTokens` (Motion.kt) as before: 150 / 220 / 320 ms, springs at dampingRatio ~0.8 for cards entering,
everything snaps / fades only when the phone's animator scale is 0.

## Chat (phase 1)
* Borderless thread: Blaze's text full width on the ground; your text right-aligned, medium weight, 78 % ink. No bubbles.
* Composer: glass pill. Work / confirm / question / secure-form cards: glass.
* Streaming: `LlmClient.readStream` emits ~every 16 ms → `StreamGate` (agent) applies the newest snapshot at most once
  per frame on Main → `Session.msgs` / `Agent.messages` + `Agent.streamingId` → `MessageList` → `AnswerBody(live)`.
  The newest ~28 characters fade in; the water droplet (`OrbLayer`) rides the text end.
* Status words ("Thinking…", "Thought it through", the empty "Working" card) are replaced by the droplet (`ThinkingOrb`).
* Rich blocks (diagram, image(s), chart, table, compare, mini tool) are detached glass preview windows
  (`RichPreviewCard`); the droplet falls onto them with a ripple; tap opens `RichBlockViewer` (zoom/pan for
  diagrams and pictures).

## Phase 2 (Home + remaining views, light mode)
* Home: default background is `"glass"` (dark glass ground via `glassBackground`, `DarkColors.mono` palette whatever the app
  theme). `HomeBoard.normBackground` maps the retired `"sky"` / blank to `"glass"`; a one-time migration
  (`home_look_glass_v31`) also moves Solid-with-default-swatch users to glass. Solid / Gradient / Image stay as opt-in.
  Cards, stacks, chips, top buttons, add button (High glass), sheets (`homeSheet`), menu and toast are GlassKit glass.
* Light theme = the same glass inverted: fills are near-black ink 6–10 % (`GlassTokens.lightInk`), rim ink 15 % → 5 %,
  primary actions solid ink with white text (`Modifier.glassPrimary`, `GlassTokens.primaryFill / onPrimary`; disabled =
  ink 25 % fill, ink 50 % text). `BlazeButton(PRIMARY)`, `GlassButton(primary)`, Confirm, Send use it.
* Charts / mini tools: `chartColors` is monochrome first then desaturated tints; control accent is theme-aware.

## v0.31.1: smooth streaming, floating windows, HTML

**Streaming performance.** Only the reply that is streaming recomposes per frame:
* `ChatMessage` and `RichReplies.Block` are `@Immutable` (compared by value); `MessageList` hands unchanged rows their
  previous `Item` instance (`stabilize`), and `jumpTo` is one stable lambda, so finished rows and their preview cards skip.
* Following the stream is one `scrollBy` per new text length, only while you follow and the list can scroll (it was
  `scrollToItem` + `scrollBy`, i.e. two list layouts, every frame).
* `Md` regexes are compiled once (they were compiled per line per frame).
* The particle overlay's clock stops while a reply streams.
* At send, the tool list, skill / specialist / plan lookups, the system prompt + memory block, the request JSON and
  `ToolCalls.clean` run on `Dispatchers.Default` / `IO` instead of the main thread.
* `app/src/main/baseline-prof.txt` AOT-compiles the chat path at install (release build = R8, not debuggable).

**Floating glass window** (`GlassFloat.kt`): `GlassFloatingWindow(title, onDismiss, subtitle, fillHeight) { … }` — a centred
glass card over a dimmed (API 31+: blurred) scrim; grab handle; × / Back / scrim tap / swipe-down closes it. Its content
gets bounded constraints (`BoxWithConstraintsScope`). `RichBlockViewer` uses it: diagrams and pictures fit the window then
pinch-zoom 1–8×, pan, double-tap; tables get a sticky header and scroll both ways (`TableWindow`); charts, compare cards
and mini tools take the window width and scroll. Preview cards in the thread show a real miniature (the picture itself,
or the block rendered at 320 dp and scaled down) and never take taps themselves.

**Tables everywhere.** `TableSniff`: pipe tables with or without outer pipes and alignment colons, the same inside a
```/```md/```markdown/```text/```table fence, ASCII `+---+` and box-drawing grids → Table preview card + window.

**HTML in replies** (`HtmlLite.kt` + `Md`): `<b> <strong> <i> <em> <u> <s> <del> <code> <pre> <a href> <br> <p> <h1>-<h4>
<ul> <ol> <li> <blockquote> <hr> <span style="color"> <font color> <sub> <sup> <mark> <small> <kbd>`; `<table>` → table
card, `<img src>` → image card. Colours are toned to the monochrome look. Scripts, styles, iframes, forms and comments
are removed; other known tags are stripped (text kept); only http(s)/mailto links are live. No WebView.
