# Hand-off 03 (2 Nov 2026): notification, lock screen, and test bench states

This hand-off covers the user's choice of the next design round (the notification and lock screen, for screen-off radio) and replies to the six undesigned parts listed in `feedback.md` (1 Nov, test bench).

## What changed

| Item | Change | Needs code? |
|---|---|---|
| `screens/notification-shade` | **New.** The media player in the shade: title, badge subtitle, artwork, a Use satellites custom action, Stop/Play, no seek bar | yes: `MediaSession`, the media notification, the foreground service |
| `screens/lock-screen` | **New.** The same player on the lock screen, shown in Privacy mode | the same session |
| `screens/notification-art` | **New.** 512 × 512 artwork tiles for In sync/Holding, Privacy, the amber states and the red states | yes: render the tiles with Canvas |
| `screens/test-bench-states` | **New.** Run chip (stopped and running), START/STOP, satellites switch on and off, rate nudge row (Auto and Manual), STREAM row, error lines, tightening line | yes: replaces your six improvised parts |
| `tokens.json` | New: `artwork` (size, radius, safe area, redraw rule). Changed: `screenLevels.Off`. | small |
| `ui-spec.md` | New: Notification, lock screen and quick settings; Test bench states. Changed: Screen levels › Off. | — |

## Replies to `feedback.md`

| Your choice | Design |
|---|---|
| START RUN as a filled accent button with ▶ | Kept |
| STOPPED pill in `faint` | Kept, plus a square mark and `textSecondary` text |
| Satellites off: `grid` track, `muted` thumb | Changed to a 1.5 px outline track in `faint` with an 18 px `muted` thumb, to match Settings and Strict mode |
| Rate nudge row | Kept. It now has a mode chip and a `gap · closes in · pitch` readout, matching the Now playing gap footer. |
| STREAM row | Kept, in stream mode only, after PLAYOUT |
| Error messages added to rows | Each error gets its own line in `warn` with ▲, cut to one line; tapping it opens the event log. That stops the rows wrapping unpredictably. |
| Tightening line | Kept: `TIGHTENING` in accent, then `±from → ±to` in muted mono |
