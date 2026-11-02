# Hand-off 01 (1 Nov 2026)

This hand-off is the first merged design. It combines the original canvas mock-ups with the UI Claude Code built on `prototype-queue` (event log, Use satellites, stream channel, D = 60 s) and the decisions in `feedback.md` of 1 Nov. None of Claude Code's files were changed.

## Screens

| Screen | File | New or changed |
|---|---|---|
| Now playing | `now-playing` | Latest event under the badge; Use satellites one tap away; the stream gap is its own card, linking to nudge control; D 60 s |
| Badge states | `badge-states` | **New.** Every badge state, including Privacy mode, Silent over the limit with its offers, and the tightening chip |
| Stream gap · nudge control | `nudge-control` | **New.** Auto/Manual, a log slider from 1 s to 5 min with the recommended period, time left, pitch change, Go/Pause and Jump |
| Now playing · Minimal | `now-playing-minimal` | **New.** The Minimal screen level |
| Event log | `event-log` | **New.** Five lanes including Screen, a timeline scope over the error trace, and a linked list |
| Time signals | `time-signals` | Use satellites in the sky card, with privacy wording |
| Test bench | `test-bench` | **New.** Today's prototype controls restyled; satellites never locked; a GAP row |
| Headphone lag | `headphone-lag` | Unchanged apart from a clipping fix |

## Replies to `feedback.md` (1 Nov)

| Decision | How the design reflects it |
|---|---|
| Use satellites, one tap from the main screen, never locked | It's a switch on Now playing, on Time signals and on the Test bench, plus the Minimal level, and is never greyed out |
| Satellites off is first-class | The Privacy mode badge has a lock mark, a neutral colour at any error, and no fault words, banners or nagging. The wording says position stays on the phone. |
| Event limits, never requiring GPS | Silent · over limit offers Sync from nearby phones first and Use satellites second |
| Rate nudge: Auto, Manual, Go/Pause, Jump | The nudge control screen. The slider hatches the range the rate limit can't reach (5 ms needs at least 5.6 s). |
| Tightening only on a real gain | The tightening chip and the rule in the spec, driven by `tighteningFromNs` |
| Flowtime error and stream gap kept separate | The budget card covers flowtime's error and the stream gap card covers the gap; nudge control labels them separately |
| Newest event under the badge, frontier timeline | Done on Now playing; the timeline scope has five lanes, with marks now and the trace later |
| Automatic screen levels | Rules for every screen are in `ui-spec.md`. Only Now playing has a Minimal design; the rest are next round. |
| Light show safety | Restated in the spec; there's no light show design yet |
| Buffer 60 s, accessibility | Done on every screen |

## Changes from the first draft

- **Removed the "jump 0.5 ms" marker** from the old trace. Gaps under 20 ms are nudged, never jumped.
- **Event error figures should use adaptive units.** `EventDetector` writes `±%.1f ms`, so GPS shows `±0.0 ms`.
- **Fixed clipping** on Headphone lag and Time signals.

## Open questions for the user

1. **Clock zone.** Listeners aren't necessarily in the UK, but the brocast clock says `GMT`. Should it show local time, UTC, or UK time? It's still UK time until someone decides.
2. **Strict mode.** It's still on Now playing beside Use satellites. With event limits, is a listener's own strict mode still wanted?
3. **Radio, screen off.** Most radio listening happens with the screen off, so the notification and lock-screen design matter most there. Should the next design round be the notification, or the Reduced and Minimal designs for the other screens?

## Suggested build order

1. **Test bench:** everything on it exists today.
2. **Event log:** list and filter now; the scope's marks now and its trace later.
3. **Now playing:** badge, latest event, Use satellites, tiles, gap footer.
4. **Badge states and nudge control.**
5. **Minimal level for Now playing.**
