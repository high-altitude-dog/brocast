# UI spec (hand-off 01)

Field names come from Claude Code's "Fields that exist today" table in `feedback.md`, as of 1 Nov 2026. If one has been renamed since, use the new name.

**Status of each element:**

- **now:** the data exists today.
- **later:** the element needs data or a feature that's listed in `feedback.md` under "Not in the app yet". Lay out its space and show `—`.

**Rules for every screen:**

- **Units:** every duration uses adaptive units, like `Session.fmtNs` (ns, µs, ms), at three significant figures. Every live number is in JetBrains Mono.
- **Sync error:** the figure shown to the listener is `errAt(boot)` plus the absolute held gap (`NudgeControl.gapAt`). It will add the headphone-lag uncertainty **later**. Every ± in a badge is this number.
- **Flowtime error and stream gap are separate.** Never add them together on screen, except in the badge figure above.
- **Times** come from flowtime, never the system clock. Show `--:--:--` before there is a time source. The zone label is an open question (see the hand-off).
- **Tightening** (see Badge states) appears only when `EventDetector.tighteningFromNs` reports an improvement of at least 20% sustained for 10 s. Smaller changes update quietly, with no animation.
- **Screen levels** (Full, Reduced, Minimal, Off): see the last section. Every screen follows them.
- **Accessibility:** use words and a mark, never colour alone. Every tappable item is at least 44 × 44. Respect reduced motion: no sweep and no glow pulse. Screen-reader labels match the visible words.

---

## Now playing

![](screens/now-playing.png)

| Element | Shows | Source | States and taps | Status |
|---|---|---|---|---|
| Header | Event or station name, `Live · D 60 s` | `StreamClient.manifest.dMs`; the name is **later** | Icon buttons (44 × 44) for Time signals and Headphone lag | now |
| Brocast clock | Flowtime, with HH:MM:SS large and the fraction to the ns. Digits finer than the error are drawn in `faded`. | `FlowTime.fit().utcAt(boot)` at the frame's presentation time | Screenlag correction is **later**; use the frame time until then | now (basic) |
| Second sweep | An arc that fills once per flowtime second | The fraction of the current second | Off at Reduced level and with reduced motion | now |
| Precision ring | A glow whose width shows the sync error on the budget's log scale, labelled `ring ±x` | Sync error | | now |
| Sync badge | Words, the ± figure and the source | See Badge states | Tapping opens the error budget detail | now |
| Latest event | Time, lane mark and name, and one line of text, **directly under the badge** | `EventLog.latest()` | Tapping opens the Event log. A banner elsewhere links to its own event. | now |
| Sync error budget | Bars on a log scale from 1 µs to 10 ms: flowtime, headphone lag, total, screenlag | `errAt` now; headphone-lag uncertainty and screenlag **later** | | partly |
| Stream gap card | Header: the gap now and whether it's closing. Trace: the last 60 s of gap. | `NudgeControl.gapAt`; the trace needs history, which is **later** | | partly |
| Gap footer | Mode chip (AUTO or MANUAL), time left and pitch change | `NudgeControl.mode`, `secondsLeft`, `state.rate` | Tapping opens Nudge control | now |
| Tiles | SOURCE, HEADPHONES, BUFFER (seconds in hand out of D) | `FlowTime.chosen`, `GpsTime`, `Settings.lagMs`, `StreamClient.buffered` | SOURCE opens Time signals; HEADPHONES opens Headphone lag | now |
| Use satellites | A switch, never locked, one tap from the main screen. Shows `Stays on phone`. | `GpsTime.active` | Off: the badge becomes Privacy mode. On: asks for location permission if needed; if refused, it returns to off. | now |
| Strict mode | A switch and its limit | Not built | See the open questions in the hand-off | later |

## Badge states

![](screens/badge-states.png)

| Badge | Mark and colour | When |
|---|---|---|
| IN SYNC ± | ● accent, glowing | GPS is in charge and the sync error is 5 ms or less (amber above) |
| HOLDING ± | ○ accent | The holdover clock is in charge. Turns amber by itself above 5 ms. |
| PRIVACY MODE ± | lock icon, neutral (`textSecondary`) | Satellites are off. Neutral at **any** error, with no fault words, no GPS banners and no prompts to turn satellites on. |
| INTERNET TIME ± | ▲ amber | Satellites are on but there's no GPS time yet |
| REJOINING | ▲ amber | A data gap longer than D, or a jump of more than 20 ms |
| SILENT · OVER LIMIT ± | ▲ amber, with offers | Over the event's limit. Offer what could help: **Sync from nearby phones** (**later**), and **Use satellites** if they're off. GPS is never required. |
| CLOCK CHECK FAILED | ✕ red (`danger`) | GPS and NTP differ by more than 50 ms |
| NOT IN SYNC | ✕ red | No time source, or no stream |
| TIGHTENING chip | accent text | Under the badge, only when the tightening rule above holds |

## Stream gap · nudge control

![](screens/nudge-control.png)

| Element | Shows | Source | States and taps | Status |
|---|---|---|---|---|
| Held gap | The gap, large; why it exists (the event that moved flowtime); and flowtime's own error, labelled as separate | `NudgeControl.gapAt`, `EventLog` | | now |
| Auto / Manual | Segmented control | `NudgeControl.mode` | Auto by default | now |
| Period slider | Log scale from 1 s to 5 min. A marker shows the recommended period. The span below the rate limit is hatched. | `NudgeControl.periodS`, plus the recommended period | Manual only. Use a real slider with a value text for screen readers. | now |
| Time left, pitch change | Real time left, and the pitch change in % and cents | `secondsLeft`, `state.rate` | Updates while the gap closes | now |
| Preview | The gap falling to zero over the chosen period | Derived from the values above | | now |
| Go / Pause | Go starts closing the gap; it becomes Pause while closing | `NudgeControl` | | now |
| Jump now | Fixes the gap at once, with a short fade | `NudgeControl` | Secondary style | now |

## Event log

![](screens/event-log.png)

| Element | Shows | Source | States and taps | Status |
|---|---|---|---|---|
| Copy | The log as plain text, for support | `EventLog.recent(n)` | | now |
| Lane filter | All, Clock, Audio, Net, Output, Screen, each with its mark | `Lane` | Scrolls sideways; one chip selected at a time | now (Screen lane later) |
| Timeline scope | Five lanes of event marks over the sync-error trace, with a 5 ms line, for the last 15 min | `EventLog`, plus error history (**later**) | Tap a mark to select its row; pinch to zoom | later (marks now) |
| List | Time, lane tag, plain text; newest first; the selected row highlighted | `Event.utc`, `lane`, `text` | Text wraps; `--:--:--` with no time source | now |

Lane marks: clock ● accent, audio ■ #FFB547, net ▲ #5CE1FF, output ○ #B79CFF, screen ◆ #FF8FB1.

## Time signals

![](screens/time-signals.png)

| Element | Shows | Source | Status |
|---|---|---|---|
| Sources field | GPS, HOLD and the NTP nodes sized by error, with the one in charge lit | `FlowTime.gps/ntp`, `chosen` | partly (one NTP server today) |
| Use satellites | The same switch as Now playing, with `Position stays on this phone · Off = Privacy mode` | `GpsTime.active` | now |
| Sky plot and legend | Satellite positions, constellation, signal strength | Counts only today | later (counts now) |
| NTP servers | Host, stratum, reference, round trip, offset from GPS | `NtpClient` (one server) | partly |
| Safety check | GPS vs NTP, and its age; PASSED or FAILED | `NtpClient.minusGpsUs` | now |

## Headphone lag

![](screens/headphone-lag.png)

Unchanged. Everything except the typed value (`Settings.lagMs`) is **later**.

## Test bench (prototype)

![](screens/test-bench.png)

This screen restyles today's test controls with the same data.

- **Locked while running:** Mode, Time source, Stream channel, Server, Tick pitch and Headphone lag.
- **Never locked:** Use satellites.
- **Status grid:**
  - OUTPUT: `Playout`
  - PLAYOUT: `Renderer`
  - GAP: `NudgeControl`
  - NTP: `NtpClient`
  - GPS: `GpsTime`, `FlowTime.gps.slope`
  - LOG: `Session.name`

---

## Screen levels

Each phone moves between levels automatically, based on heat, missed frames, underruns and battery. The level is always named on screen (`SCREEN · MINIMAL` in the screen's corner), along with the reason. Levels never change the audio.

| Level | Every screen |
|---|---|
| Full | As drawn |
| Reduced | No glows, no sweep, and no animated hand-overs. Clocks update to 0.1 s at 4 Hz, traces at 1 Hz. |
| Minimal | Pure black, text only, one update a second, no animation. The clock shows whole seconds, the badge is words, the event log is plain text, and the satellites switch stays. See `screens/now-playing-minimal.png`. |
| Off | Screen off (the notification design is **later**) |

The light show follows its own rules. A screen level can reduce flashes but can never start the show without the listener's opt-in. The three-flashes limit and the "no flashing" setting always apply.
