# Hand-off 66: web player page redesign (18 Nov)

Brief: design/feedback.md "18 Nov: web player page redesign" (user: "do a redesign of the page to make it look great").
Canvas: **Web player** (phone, playing), **Web player · every state** (20 states at 360 px), **Web player · desktop** (playing, off air at 1440 × 900).
Screens: design/screens/web-player.png, web-player-states.png, web-player-desktop.png. Fonts: design/web-fonts/.

## The idea
The page is a live clock with a button in it. Station time is the hero; the seconds are in the station colour, and the same second lights one of 60 ticks around the Play button. A soft ring leaves the button once a second. Within a second of opening, a first-time visitor sees something ticking in step, a big button, and what's on air.

## Layout, phone (320–1023 px), top to bottom
1. **Top bar** (44 px): mark (circle + dot, accent) and `BROCAST` (13 px, 700, letter-spacing 0.28em); link pill on the right.
2. **Clock**: label `STATION TIME · TEDDINGTON · GREENWICH · BST` (10.5 px, 0.16em, T3, one line, ellipsis); time `22:43:51` in JetBrains Mono 600, `font-size: clamp(44px, 15.5vw, 60px)`, tabular numbers, `HH:MM` in T1 and `:SS` in accent; local line `23:43:51 CEST · your time` (mono 13 px, T3) only when the zone differs. Unknown: `--:--:--` in T4 and `Getting the time…`.
3. Hairline `#1A1F26`.
4. **Now playing**: kicker `● ON AIR NOW` (10.5 px, 700, 0.16em, accent; dot pulses each second) when linked with a set; `OFF AIR` in T3 when the link is closed or nothing is scheduled; no kicker otherwise. Title 26 px 700 (`Brocast` before the catalog; `ID` + `UNNAMED` tag, 10 px outlined T4). Artist 14 px T2. *Optional:* `Next · Overpass at 00:35` (12 px) from the running order.
5. **Dial**: `width = clamp(220px, 68vw, 256px)`; 60 ticks (every 5th longer), past seconds in this minute accent 32%, current second accent full and thicker, future `#2A323C`. Centre button = 46% of the dial (112–118 px). See Play states below.
6. **Caption** under the dial when stopped/joining (14 px 600 + 12 px T3).
7. **Sync badge** card (below).
8. *Playing only:* `Screen stays on while playing · keep this page open` (11.5 px T3, phone icon).
9. *Tap to start only, optional:* `Satellite time, ±0.4 ms, is in the Android app`.
10. Flexible space, then **Headphone lag** row pinned to the bottom (hairline above).
Background: `#0A0C0F` with a static `radial-gradient(90% 70% at 50% 0%, accent 7%, transparent 70%)` over the top 340 px (no blur filters).

## Play button (centre of the dial)
| State | Look |
|---|---|
| Play | filled accent, dark triangle, soft accent shadow (0 10 30, 22%). One 2 px accent ring scales 1 → 1.32 and fades, once per station second. |
| Stop (playing) | 2 px accent ring, fill `color-mix(accent 9%, #0A0C0F)`, accent rounded square (22% of the button). Same once-a-second ring. |
| Joining | Stop look + a rotating accent arc around it (1 s/turn). |
| Waiting (no time yet) | disabled: 2 px dashed T4 circle, T4 triangle, rotating T3 arc. Ticks all T4. |
| Can't play (notice) | dial dims (ticks T4), dial shrinks to 52% of width, centre is a dashed T4 circle with an icon (✕, signal, or tab), the notice card sits under it. |
Sound still starts only from the Play tap.

## Sync badge (honest)
Card: radius 14, padding 11/14. Row 1: mark (13 px, as SVG, not a font glyph), words (14 px 700, 0.08em), ± figure on the right (mono 17 px 600, **T1**, so it always reads). Row 2: detail (12 px T2, indented 23 px).
| State | Mark/words | Colour | Card | Detail (new wording) |
|---|---|---|---|---|
| Stopped | ■ STOPPED, ± once known | T2 | `#12161B`, border `#222932` | `Getting time` / `Clock ready` |
| Starting | ▲ REJOINING | amber `#FFB547` | border amber 38%, fill amber 6% | `Getting the set due now` |
| Playing | ▲ INTERNET TIME | amber | as above | `Synced over the internet · stratum 2 · 20 s buffered · headphones not calibrated` (or `· headphone lag 182 ms`) |
| Reconnecting | ▲ INTERNET TIME | amber | as above | `Playing from the buffer · 14 s left` |
| No time | ✕ NOT IN SYNC | red `#FF6B6B` | border red 38%, fill red 6% | `No time source. Check your connection.` |
| No audio | ✕ NOT IN SYNC | red | as above | `No stream. Trying again; you'll rejoin in sync.` |
The station colour is never used on the badge: on the web there is no success state, so warn stays amber and nothing looks like "all good".

## Link pill
30 px tall, radius 999, 12 px. Dot 7 px. `Reaching the station` (T3 dot, pulsing), `Station linked` (accent, border accent 40%, pulsing once a second), `Reconnecting` (amber, pulsing), `Off air · link closed` (T3, still), `Not connected` (T3, still).

## Notices (final wording)
Card: `#12161B`, border `#2A323C` (Can't reach: amber 40%), radius 14, title 15 px 700, body 13 px T2; optional action = 44 px outlined pill.
| Was | Title | Body | Action |
|---|---|---|---|
| This browser can't play Brocast | This browser can't play Brocast | Open the link in Chrome on Android, or on a computer. | Copy link |
| This link is incomplete | This link is incomplete | Part of the address is missing. Ask whoever sent it for the whole link, or scan the station's code. | – |
| Already playing in another tab | Already playing in another tab | Brocast plays in one tab at a time. Close the other one, then reload this page. | Reload |
| Stored sets can't be read | Saved music can't be read | This browser's storage for Brocast is unreadable. Clear this site's data in browser settings, then reload. | Reload |
| Brocast couldn't start | Brocast couldn't start | Reload the page. If it happens again, try another browser. | Reload |
| This month's allowance is used up | This month's browser listening is used up | It comes back when the new month starts. Sets already saved here can still play. | – |
| Too many browsers are listening | Too many browsers are listening | This station has a limit for browser listeners. Try again in a few minutes. | Try again |
| Nothing is scheduled | Nothing is scheduled right now | The station has no set planned for this time. Leave the page open; it starts when one is. | – |
| Can't reach the station | Can't reach the station | Still trying. Check your connection. | – |
| Sound couldn't start | Sound didn't start | Tap Play again. If your phone is on silent, turn the volume up. | Play |
| *(new)* page left / hidden | Playing stopped when you left the page | Keep this page open and the screen on while you listen. Play rejoins in sync. | Play |
| *(new)* relay closed | Off air right now | This station broadcasts in sessions and its link is closed. Leave the page open: it reconnects by itself. | – |

## Motion (cheap, reduced-motion aware)
- All timing from station time: on each station second boundary set the lit tick and restart the halo/dot animations (toggle a class). No requestAnimationFrame loop, no canvas.
- `@keyframes halo { 0% {transform: scale(1); opacity: .55} 100% {transform: scale(1.32); opacity: 0} }` 1 s cubic-bezier(.2,.6,.3,1). Dot: box-shadow 0 → 7 px, 1 s. Arc: rotate 360°, 1–1.2 s linear.
- `prefers-reduced-motion: reduce`: no halo, pulse or arc; ticks and digits still update.
- Pause animations when the page is hidden.

## Desktop (R6), from 1024 px
Two columns inside 64 px side padding: left = clock (mono 128 px), hairline, now playing (title 44 px, artist 18 px, optional next); right = a 440 px panel (`#12161B` 80%, border `#222932`, radius 24, padding 28) with the dial (300 px), caption/notice, badge, keep-open line (`Keep this tab open while you listen`), headphone lag. Static radial glow at 20% 30%. Footer line `Brocast · open source · listening in sync, peer-to-peer` (12 px T4). 720–1023 px: the phone layout, centred, max 520 px.

## Tokens (web)
| Token | Value |
|---|---|
| bg | `#0A0C0F` |
| card | `#12161B` |
| line | `#222932`; input/notice border `#2A323C`; hairline `#1A1F26` |
| text T1 / T2 / T3 / T4 | `#ECEFF2` / `#C4CBD2` / `#8B95A1` / `#4A5562` |
| accent | station colour, default `#C8FF4D` (as the app; tints with `color-mix(in srgb, accent N%, transparent)`) |
| warn / danger | `#FFB547` / `#FF6B6B` |
| touch target | 44 px min; primary 112–118 px (dial centre); actions 44 px |
| radius | 14 cards, 10 inputs, 999 pills, 22 phone frame (mock only) |
| clock size | `clamp(44px, 15.5vw, 60px)` phone; 128 px desktop |
| dial size | `clamp(220px, 68vw, 256px)`; 52% width with a notice; 300 px desktop |

## Fonts (self-hosted, OFL 1.1)
`design/web-fonts/`: space-grotesk-latin-400/500/700-normal.woff2, jetbrains-mono-latin-400/600-normal.woff2, with OFL-SpaceGrotesk.txt and OFL-JetBrainsMono.txt (from the @fontsource packages). Draw ■ ▲ ✕ as inline SVG (not in these fonts).

## R1–R6
- **R1 tap-to-start:** the full page with the filled Play in the dial, the halo already ticking, caption `Tap to listen in sync` / `Everyone on this link hears the same second`, badge ■ STOPPED ± `Clock ready`. While time is unknown the button is disabled with an arc (`Getting the station's time` / `Play is ready in a moment`).
- **R2 time source wording:** `▲ INTERNET TIME ±17.3 ms`, detail `Synced over the internet · stratum 2 · 20 s buffered · …`. Amber on purpose; never accent.
- **R3 Use satellites row:** removed. In its place, only on tap-to-start and optional: `Satellite time, ±0.4 ms, is in the Android app`.
- **R4 plain failure states:** the dial dims and shows an icon; a notice card with at most one action replaces Play. Relay closed is a calm `Off air right now` with the clock still running, not a warning.
- **R5 lag field:** the bottom row, `Headphone lag` + hint + a 68 × 44 mono field (3 digits) + `ms`; accent border once a value is set. Visible but quiet.
- **R6 desktop:** two columns from 1024 px (above); centred phone layout 720–1023.

## Staying in front
Request a Screen Wake Lock while playing (Chrome Android supports it) and show `Screen stays on while playing · keep this page open`. If the page is hidden and audio stops, show the new notice on return.

## Optional (don't block the first build)
Next-up line, satellite-app line. No artwork, counts, or progress.
