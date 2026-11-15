# Hand-off 55: station colour for the play button, sync badge and running-order card (15 Nov)

User: "the 'Holding' box and the Play/Stop button next to it, should both be in the colourscheme of the station. Same for 'Running Order Changes'".

Canvas: Now Playing, Badge states, Now playing states, Order change.

## What changes
| Element | Before | Now |
|---|---|---|
| Stop button (playing) | white outline, white square | 1.5 px `{{accent}}` outline, fill `color-mix(in srgb, {{accent}} 7%, transparent)`, 14 px `{{accent}}` square |
| Play button (stopped) | accent fill | unchanged (already the station colour) |
| IN SYNC / HOLDING badge | lime border/dot, fixed `rgba(200,255,77,0.07)` fill | `{{accent}}` border and dot, fill `color-mix(in srgb, {{accent}} 7%, transparent)` |
| Running order changes card | cyan border, label and "new order · was …" line | border `color-mix(in srgb, {{accent}} 50%, transparent)`, label and line in `{{accent}}` |

## Rules
- No fixed lime anywhere: every tint of the station colour is a `color-mix` of the accent token, so all 21 palette colours work (checked with Violet).
- Amber (Late, LIMIT), red (errors) and grey (off, receive-only) badges keep their own colours.
- The running-order card is still news, not a warning; it goes at the change and the toast stays 6 s, as before.
- Cyan remains for the news bulletin only.

Spec: ui-spec rows "Play control and sync badge", "Until the change", "Buffer up next", "What doesn't".
Screens: design/screens/now-playing.png, badge-states.png, now-playing-states.png, order-change.png.
