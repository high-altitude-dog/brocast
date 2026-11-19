# Hand-off 67: web player v2, photo-led (18 Nov)

User on v1 (hand-off 66): "Not very good. how about artist photo etc etc???"
This **replaces the layout** in hand-off 66. Still valid from 66: sync badge (states, colours, honesty rule), link pill, all notice wording, motion rules, tokens, fonts (design/web-fonts), R2/R3 answers. The dial of ticks is dropped.
Canvas: Web player (phone), Web player · every state (22 states incl. 320 px and no-photo fallback), Web player · desktop. Screens: design/screens/web-player*.png (photos replaced by a placeholder in the exports; the canvas shows real ones).

## Phone (320–1023 px)
1. **Artist hero**, full width, 50% of the viewport height (42% when a notice shows). The artist photo, `object-fit: cover` using the photo's focal point. Overlay: `linear-gradient(180deg, rgba(10,12,15,.62) 0%, transparent 22%, transparent 42%, rgba(10,12,15,.82) 78%, #0A0C0F 100%)` (no blur).
   - Top, over the photo: mark + `BROCAST` (12.5 px 700, 0.28em); link pill (on `rgba(10,12,15,.62)`); under it a **station chip** (16 px station tile `BR` in the station colour + `Brocast Radio`).
   - Bottom, over the photo: `● ON AIR NOW` (accent, pulses each second) / `OFF AIR`; **set title** 34 px 700 (30 px under 380 px); **artist** 16 px with flag before it; *track line* `Track 9 of 14 · [Track title]` 12 px; **progress through the set**: 4 px bar (white 14% track, accent fill) with a 10 px knob, `1:12:40` elapsed and `ends 18:46` (mono 11 px).
   - No photo (artist has none, unnamed set, before the catalog, off air): **station poster**, a radial station-colour glow with concentric rings and a dot (static SVG). Title over it as usual.
2. **Play + clock row**: Play 84 px (76 px under 340 px) with the once-a-second halo; next to it `STATION TIME · BST`, time mono 34 px (`:SS` in accent), local line. Play states as hand-off 66 (filled / stop / joining arc / waiting dashed).
3. Caption only when stopped: `Tap Play to listen in sync with everyone on this link`.
4. **Sync badge** (as 66, slightly tighter: padding 9/12, ± 15 px).
5. **Up next** card: 48 px photo, `UP NEXT · 18:46`, set title 15 px 700, flag + artist; right: three overlapping 30 px photos of later sets + `later today ›` (opens a sheet with the day's running order).
6. Bottom row: **Headphone lag** (label, one-line hint, 60 × 44 mono field, `ms`) and a **Share** button (44 px; Web Share API, falls back to copying the link).
Notices: the Play row becomes the clock alone, and the notice card (dashed icon circle + title + body + at most one filled white action) replaces badge and up next.

## Desktop (R6), from 1024 px
Two columns: left = the artist photo full height (fades into the page on the right and bottom), with BROCAST + station chip at the top and, at the bottom, ON AIR NOW, title 72 px, flag + artist 22 px, track line, progress (max 560 px). Right column 520 px: link pill, clock 64 px, Play 96 px + `Listening in sync` / `Keep this tab open while you listen`, badge, **LATER TODAY** list (time, 40 px photo, set, flag + artist; next one bold), headphone lag + share at the bottom. Off air: poster on the left, notice on the right.

## Data
| Item | Status |
|---|---|
| Set title, artist, start and length (progress, `ends`), next and later sets | in the running order the page already has |
| Station name, tile, colour | station catalog |
| **Artist photo** (+ focal point) | NEW: serve each artist's photo from the station at runtime. Never in the public repo. Fallback: station poster |
| Artist country (flag) | NEW for the web: artist `countries` (hand-off 56). No flag when empty |
| Track within the set | NEW, optional: from the set's track list. Hide the line when absent |
| Share | Web Share API; no data |

Build the first version with the poster fallback and without the track line; photos switch on when served.
