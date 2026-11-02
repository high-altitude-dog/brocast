# UI spec (hand-off 07)

Field names come from Claude Code's "Fields that exist today" table in `feedback.md`, as of 1 Nov 2026. If one has been renamed since, use the new name.

**Status of each element:**

- **now:** the data exists today.
- **later:** the element needs data or a feature that's listed in `feedback.md` under "Not in the app yet". Lay out its space and show `—`.

**Rules for every screen:**

- **Units:** every duration uses adaptive units, like `Session.fmtNs` (ns, µs, ms), at three significant figures. Every live number is in JetBrains Mono.
- **Sync error:** the figure shown to the listener is `errAt(boot)` plus the absolute held gap (`NudgeControl.gapAt`). It will add the headphone-lag uncertainty **later**. Every ± in a badge is this number.
- **Flowtime error and stream gap are separate.** Never add them together on screen, except in the badge figure above.
- **Times** come from flowtime, never the system clock. Show `--:--:--` before there is a time source. The main clock and the event log show UK time (`Europe/London`, GMT or BST), branded **Teddington · Greenwich**. Local time sits beneath, using only the phone's time-zone setting, never its clock. Hide it when its offset equals UK time's. CSV logs stay in UTC ns.
- **Tightening** (see Badge states) appears once the error estimate has improved by at least 20% for 10 s, and stays for 10 s (Claude Code's `tighteningFromNs` behaviour, 2 Nov). Smaller changes update quietly, with no animation. When both figures share a unit, the unit is written once (`±11.4 → ±8.20 ms`); otherwise both are written out (`±11.4 ms → ±400 µs`).
- **Track names** (title, artist, set or show) come from the stream, stamped by sample number. They switch when the new track's first sample reaches the ear (start + n / 48 kHz + D), never when the name arrives, so the name changes with the music. With no track name, show the set or show; with neither, show the station or event, with `Live` as the second line. Show the progress line only when the stream gives the track's start and length.
- **Privacy mode** (satellites off) also turns off sharing, and any social feature added later (the user's decision, 2 Nov). It never changes the listener's saved choices: they come back when satellites go back on. Nothing in Privacy mode asks the listener to turn satellites on.
- **Screen levels** (Full, Reduced, Minimal, Off): see the last section. Every screen follows them.
- **Accessibility:** use words and a mark, never colour alone. Every tappable item is at least 44 × 44. Respect reduced motion: no sweep and no glow pulse. Screen-reader labels match the visible words.

---

## Now playing

![](screens/now-playing.png)

| Element | Shows | Source | States and taps | Status |
|---|---|---|---|---|
| Header | Station or event name (eyebrow), `Live · D 60 s` | `StreamClient.manifest.dMs`; the name is **later** | Icon buttons (44 × 44) for Time signals, Headphone lag, Archive and Settings | now |
| Now playing | Track title (20 px, bold, one line), `artist · set` (13 px, `textSecondary`), a **Keep** button, and a thin progress line with elapsed and remaining time | The signed file record and cue list (**later**; the prototype can show the manifest `source`) | Follows the Track names rule; long names end with an ellipsis. Keep pins the file being heard: `Keep` for a single track, `Keep set` inside a set, `Kept` (filled accent mark) once pinned. Hidden while the archive is Off. | later |
| Brocast clock | A dial. Along the top of the bezel, curved: `TEDDINGTON · GREENWICH`, with an accent meridian tick at 12 o'clock between the two names. Inside: the zone (GMT or BST), HH:MM:SS large, a **sub-second dial** (40 px: 10 tenth ticks with the 0 tick in accent, a faint accent arc and an accent hand), then local time beneath (`23:43:51 CET · local`). Along the bottom of the bezel, curved: `FLOWTIME ±error` in accent. Tapping the dial swaps the sub-dial for the digits to the ns (finer than the error in `faded`), for side-by-side photos; tapping again swaps back. | `FlowTime.fit().utcAt(boot)` at the frame's presentation time; `ZoneId.of("Europe/London")`; `ZoneId.systemDefault()` for the local line | Local line hidden when its offset equals UK time's. Screenlag correction is **later**. Draw the curved text with `drawTextOnPath`, or the Compose equivalent. | now |
| Seconds sweep | The outer accent arc fills once per flowtime **minute**, like a second hand | Seconds of the current minute | Steps once a second at Reduced level | now |
| Sub-second dial | The hand **steps in tenths** at 10 Hz, landing on the ticks, and the arc fills by tenths. A smooth sweep at frame rate comes only if a release build shows the UI thread can afford it (Claude Code, 2 Nov: 18–25% of a core at 10 Hz in a debug build). | The fraction of the current second | Hidden at Reduced and Minimal levels and with reduced motion | now |
| Precision ring | A glow whose width shows the sync error on the budget's log scale; its figure is on the bottom bezel | Sync error | | now |
| Sync badge | Words, the ± figure and the source | See Badge states | Tapping opens the error budget detail | now |
| Latest event | Time, lane mark and name, and one line of text, **directly under the badge** | `EventLog.latest()` | Tapping opens the Event log. A banner elsewhere links to its own event. | now |
| Sync error budget | Bars on a log scale from 1 µs to 10 ms: flowtime, headphone lag, total, screenlag | `errAt` now; headphone-lag uncertainty and screenlag **later** | | partly |
| Gap row | The rate nudge row: mode chip, the gap value, then two small lines (`gap · closes in 1.40 s` and `pitch +0.014%`) | `NudgeControl.gapAt`, `mode`, `secondsLeft`, `state.rate` | Opens Nudge control. The 60 s gap trace has left this screen to make room for the track; history belongs on Nudge control, **later**. | now |
| Tiles | SOURCE, HEADPHONES, AHEAD (how much is downloaded ahead, in hours and minutes) | `FlowTime.chosen`, `GpsTime`, `Settings.lagMs`, download state (**later**) | SOURCE opens Time signals; HEADPHONES opens Headphone lag | partly |
| Use satellites | A full-width row with a switch, never locked, one tap from the main screen. Shows `Position stays on this phone · off = Privacy mode`. | `GpsTime.active` | Off: the badge becomes Privacy mode. On: asks for location permission if needed; if refused, it returns to off. | now |

## Settings

![](screens/settings.png)

| Element | Shows | Source | States and taps | Status |
|---|---|---|---|---|
| LISTENING · Use satellites | The same switch as Now playing | `GpsTime.active` | Never locked | now |
| LISTENING · Headphones | Route and lag, as a link | `Playout.track.routedDevice`, `Settings.lagMs` | Opens Headphone lag | now |
| LISTENING · Local time under the clock | A switch, on by default | A new setting | Off hides the local line on every clock | now |
| SCREEN · No flashing | A switch, off by default | Not built (light show) | Always respected; screen levels never override it | later |
| SCREEN · Screen level | The current automatic level | Not built | Read-only | later |
| ARCHIVE · Keep | Segmented: Off, Kept only, Everything. Off by default (opt-in). | A new setting | See Archive rules | later |
| ARCHIVE · Size limit | Stepped slider: 1, 2, 5, 10, 20, 50 GB; 5 GB by default | A new setting | Disabled in Kept only and Off | later |
| ARCHIVE · Storage bar | Rolling (accent) out of the limit, then Kept (`text`) as extra | Archive store | | later |
| ARCHIVE · Open archive | A link | | Opens Archive | later |
| DELIVERY · Download ahead on mobile data | A switch, on by default, with `About 72 MB an hour of listening` | A new setting | Off: downloads only on Wi-Fi | later |
| DELIVERY · Share with other listeners | A switch, with `Only on Wi-Fi, while charging and not playing` | A new setting | On by default, set by the first-use prompt (see First use). Locked off in Privacy mode. Status line: see Sharing states. | later |
| DELIVERY · Only during global parties | A switch, off by default, with `Share only while a scheduled Brocast party is on` | A new setting; party windows from the signed schedule | Adds party times to the conditions. Dimmed while sharing is off or locked. Not on the first-use sheet. | later |
| DELIVERY · Sharing allowance | A storage figure, separate from the archive | A new setting | | later |
| ADVANCED · Strict mode | A switch tagged `EVENTS ONLY`, off by default, plus a `My limit` slider (1–50 ms, default 5), dimmed while off | Not built (needs events) | Applies only inside an event, and can only tighten the event's own limit. On the radio it does nothing, and the caption says so. | later |

## Archive

![](screens/archive.png)

**Archive rules (the user's decisions, 2 Nov):**

- **Keep modes:** Off (the default), Kept only, or Everything.
  - **Everything:** every file heard goes into a rolling archive capped by the size limit. When it's full, the oldest file that isn't kept is removed first (FIFO).
  - **Keep overrides the limit (confirmed):** a kept file is never removed and doesn't count toward the limit. It's shown as `extra`.
  - **Kept only:** only files the listener keeps are stored, and the limit is disabled.
- **Files, not cues:** what's kept is the published file, a single track or a whole set. Inside a set, Keep keeps the set.
- **Joined part-way:** the rest of the file downloads on Wi-Fi, shown as `Finishing on Wi-Fi · n%`.
- **Low phone storage:** when the phone's free space runs low, the rolling archive stops adding files and says so. Kept files are never touched.

| Element | Shows | Source | States and taps | Status |
|---|---|---|---|---|
| Header link | The current mode, for example `Keeping everything ›` | Archive setting | Opens Settings › Archive | later |
| Storage card | The two-part bar; phone free space | Archive store | | later |
| Filter | All, Kept, Finishing | | One at a time | later |
| Day groups | TODAY, then dates | File play times | Newest first | later |
| Rows | Replay button, title, `artist · single`, `set, n tracks` or `show`, then `length · size · time heard` in mono; the keep mark on the right | Signed file record | Status line: `Playing now · track 9 of 14` (accent), `Finishing on Wi-Fi · 62%` (#5CE1FF), or `Oldest unkept · goes next` (muted) | later |
| Replay bar | `REPLAY · NOT SYNCED` chip, title, play and pause, a scrubber with elapsed and remaining time | Offline playback | Replay can pause and seek, because it's off the timeline. Starting a replay stops live playback, and a later `Back to live` rejoins at the live point. Replay never touches flowtime. | later |

## First use: sharing

![](screens/first-use-sharing.png)

The user's decision (2 Nov): on first use the listener chooses whether to share downloads with other listeners, and the default is on.

| Element | Shows | States and taps |
|---|---|---|
| Sheet | A bottom sheet over Now playing, shown once at first launch, before any sharing starts | It must be answered: no swipe-away, and back doesn't dismiss it. Sharing never starts before the answer. **In Privacy mode it waits:** sharing stays off, and the sheet appears the first time Use satellites is turned on. |
| Picture | Your phone in accent, linked to four outlined phones | Decorative, hidden from screen readers |
| Title and body | `Pass the music on?`, then why it helps: everyone gets the music sooner, and the station needs fewer servers | |
| Conditions | Three rows: only on Wi-Fi; only while charging and not playing; up to the sharing allowance, separate from the archive | The allowance shows the real current value. The party-only option lives in Settings, not here. |
| Share with other listeners | Primary button: the default, with initial focus | Turns sharing on |
| Not now | Secondary button | Leaves sharing off. Nothing asks again; Settings › Delivery can turn it on. |
| Footnote | Only music files are shared; as with any peer-to-peer sharing, peers can see the phone's network address; how to change it later | This line must stay, because it's the honest part of the default |

Peers are found across the network, not by distance, so the wording says **other listeners**, never "nearby".

## Sharing states

![](screens/sharing-states.png)

The sharing row in Settings › Delivery. Sharing runs only on Wi-Fi, while charging and not playing. If the listener turns on **Only during global parties** (the user's decision, 2 Nov: a listener option, off by default), it also waits for a scheduled party.

| State | Switch | Status line (under the title) | When |
|---|---|---|---|
| On · sharing | on | ● accent dot, `Sharing now` | Every condition holds |
| On · waiting | on | ○ outline dot, `Waiting for the next global party` | Only with the party option on, outside party times. Checked first, so it wins over the paused reasons. |
| On · paused | on | ○ outline dot, `Paused · ` plus the first unmet condition, in this order: `not on Wi-Fi`, `not charging`, `playing` | A condition isn't met |
| Off | off | none; the conditions line stays | `Not now` on first use, or switched off |
| Privacy mode | locked: dashed outline with a lock, `aria-disabled` | lock icon, `Off in Privacy mode`; the title in `textSecondary` | Satellites off. Sharing stops at once. No button to turn satellites on. The saved choice returns when satellites go back on. |

In Settings the conditions line is the subtitle; in these states the status line takes its place while the switch is on or locked. Party windows need to come from the signed schedule (**later**); only the party option uses them.

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

## Notification, lock screen and quick settings

![](screens/notification-shade.png) ![](screens/lock-screen.png)

Android draws all three from one `MediaSession` and its media notification. The app supplies only the content below. Use Android's media style and don't design a custom notification layout. (The foreground service that keeps playback alive with the screen off is code; see `feedback.md`.)

| Slot | Content | Source | Updates |
|---|---|---|---|
| Title (`METADATA_KEY_TITLE`) | The track, for example `Sundown Tide`; falls back as in the Track names rule | Stream track metadata | At heard time (Track names rule) |
| Subtitle (`METADATA_KEY_ARTIST`) | `artist · set`, for example `Mara Okoye · Golden Hour Set` | Stream track metadata | At heard time |
| Album (`METADATA_KEY_ALBUM`) | Badge words, the ± figure and the source, for example `In sync ±3.2 ms · GPS`. Car and watch displays show it. | The badge state and sync error | Only under the redraw rule below |
| Artwork (`METADATA_KEY_ART`) | A 512 × 512 state tile, rendered by the app (`screens/notification-art.png`) | Badge state, ± figure | Only under the redraw rule |
| Playback state | `STATE_PLAYING` or `STATE_STOPPED`; no duration, so no seek bar | `Playout.state` | On change |
| Main button | Stop or Play. Play rejoins the live point at D; there's no pause. | | |
| Custom action 1 | **Use satellites:** icon on (Material Symbols `satellite_alt`) or off (the same icon crossed through). Label `Satellites on` or `Satellites off (Privacy mode)`. | `GpsTime.active` | On change |
| Content intent | Opens Now playing | | |

**Redraw rule:** update the album text and artwork only when the badge state changes, or when the ± figure moves a step (1, 2, 5, 10, 20 ms) and holds it for 10 s. Never update every second.

**Artwork tile:** a dark ground (`bg`), the tick ring, the meridian tick and the `TEDDINGTON · GREENWICH` bezel, a static arc, the state mark, the state word, and the ± figure. Use the state's colour from `tokens.color.status`. Keep everything that matters in the middle 60%, because Android crops it and lays a scrim over it. Privacy mode is neutral at any error. Not in sync has no arc.

**Lock screen:** no nudges to turn location on. If location isn't allowed yet, the satellites button opens the app after unlock to ask, and stays off until then.

## Test bench states

![](screens/test-bench-states.png)

| Part | Design |
|---|---|
| Run chip | STOPPED: an outline in `faint`, text in `textSecondary`, a square mark. RUNNING: an accent outline, a glowing dot, and elapsed `mm:ss`. |
| Start / Stop | START RUN: filled accent with dark text and a ▶. STOP RUN: a `text` outline with a ■. Both 52 high and full width. |
| Settings | 100% opacity and editable while stopped; 55% and locked while running. Use satellites is never locked. |
| Satellites switch, off | Track: a 1.5 px outline in `faint`; thumb: 18 px in `muted`, on the left. On: an accent track with a 22 px thumb in `bg`, on the right. The same on every screen. |
| Rate nudge row | A 48 px row: the mode chip (AUTO outlined in `faint`; MANUAL outlined and lettered in accent), the gap value in 14 px mono, two 11 px lines (`gap · closes in 1.40 s` over `pitch +0.014%`; Manual and paused: `gap · paused` over `period 20 s`), then a chevron. No gap: the value reads `in step` in `muted`, with no small lines. Opens Nudge control. The same row appears on Now playing. |
| STREAM row | Stream mode only, after PLAYOUT: newest segment, seconds buffered, fetch errors, server−flowtime |
| Error lines | One extra line under the row, in `warn` with ▲, cut to one line. The **whole status row** (label, value and error line) is the tap target, at least 44 px tall. It opens the event log at that row's lane: OUTPUT opens AUDIO, NTP and STREAM open NET. The row always shows the latest error text. If the text changes while the error continues, the log adds a line (`still failing: …`). |
| Tightening | One line under the source detail in the flowtime card: `TIGHTENING` in accent, then `±from → ±to` in `muted` mono. Only under the tightening rule. |

---

## Screen levels

Each phone moves between levels automatically, based on heat, missed frames, underruns and battery. The level is always named on screen (`SCREEN · MINIMAL` in the screen's corner), along with the reason. Levels never change the audio.

| Level | Every screen |
|---|---|
| Full | As drawn |
| Reduced | No glows, no sweep, and no animated hand-overs. Clocks update to 0.1 s at 4 Hz, traces at 1 Hz. |
| Minimal | Pure black, text only, one update a second, no animation. The clock shows whole seconds, the badge is words, the event log is plain text, and the satellites switch stays. See `screens/now-playing-minimal.png`. |
| Off | Screen off. The notification and lock-screen player carry the state (see Notification, lock screen and quick settings). |

The light show follows its own rules. A screen level can reduce flashes but can never start the show without the listener's opt-in. The three-flashes limit and the "no flashing" setting always apply.
