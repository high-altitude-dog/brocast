# Hand-off 04 (2 Nov 2026): track and set names, and answers to your notes

The user asked for the name of the set, track or tune to be shown. Music now takes the words and sync takes the picture.

## What changed

| Item | Change | Needs code? |
|---|---|---|
| `screens/now-playing` | A track block under the header: title, `artist · set`, and a progress line. The eyebrow names the station or event. The stream gap card is now a single nudge row (the trace is gone, to make room). | yes |
| `screens/now-playing-minimal` | The track and `artist · set` under the clock | yes |
| `screens/notification-shade`, `lock-screen` | Title = the track; artist = `artist · set`; the badge text moves to the album slot. The artwork still shows the sync state. | yes |
| `screens/test-bench-states` | The rate nudge row is redesigned (value, then two small lines) with Auto, Manual-paused and no-gap examples. The error row is one tap target at least 44 px tall. | yes |
| `ui-spec.md` | New rule: Track names. Changed: Tightening; Now playing (Now playing, Gap row); Notification (Title, Subtitle, Album, Redraw rule); Test bench states (Rate nudge row, Error lines). | — |

**Data this needs:** each track's title, artist, and set or show, stamped by sample number, so the name changes when the music does, D after ingest. The prototype can show the manifest's `source` until the stream carries real track data.

## Answers to `feedback.md` (2 Nov)

| Your note | Answer |
|---|---|
| Clipped pitch in the nudge row | Redesigned: the gap value, then two small lines. `0.20 ms`, `1.40 s` and `+0.014%` all fit. |
| How long the tightening line stays | Your behaviour is adopted: it appears after 10 s of improvement and stays for 10 s. |
| Tightening units | Your rule is adopted: one unit when both figures share it, both units otherwise |
| 22 px error line under the 44 px target | Make the **whole status row** the tap target, at least 44 px tall; the error stays one text line. |
| Event log lane for errors | Your mapping is adopted: OUTPUT opens AUDIO; NTP and STREAM open NET. If the error text changes while it continues, log another line (`still failing: …`), and keep the row showing the latest text. |
| Nudge row with no gap | The value reads `in step` in `muted`, with no small lines, and the row still opens Nudge control |
