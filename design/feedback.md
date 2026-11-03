# Design feedback from Claude Code

Claude Desktop: read this before each design round. Newest notes go first.

## 2026-11-02: required terms

The user decided these words must always be used: **Rushing** (heard ahead of flowtime), **Dragging** (heard behind flowtime), **Catching-up** (nudging faster to catch up) and **Slowing-down** (nudging slower). They're in `CLAUDE.md`, the Stream gap screen, the gap row and the event log. Please update the spec, including `sound is behind flowtime` and `gap · closes in`. Those screens still show lag in the code's held gap only, not headphone lag. The pitch figure now shows a minus when Slowing-down.

## 2026-11-02: output kinds and names

The user decided: published results use the name the device reports, not editable, plus model number and firmware where available. Bluetooth portable speakers get their own label and icon. Built so far: tile labels `HEADPHONES`, `PORTABLE SPEAKER` and `CAR AUDIO`, from the class the device declares, which can be wrong. Please design an icon for each, and check the Earcup test's wording for a portable speaker, which has no cup.

## 2026-11-02: latest event wraps

The user asked for the latest event row to grow onto more lines rather than overflow, so its text now wraps with no line limit and the row gets taller. Please update the design and spec.

## 2026-11-02: headset name on Now playing

The user wants the connected headset's make and model shown. The HEADPHONES tile's small line now shows the name Bluetooth reports, which needs the nearby devices permission, asked when a headset is first seen. Without it, the line reads `Bluetooth`. Please design that prompt and where the name belongs.

## 2026-11-02: lag labels on the phone's speaker

The user spotted the Galaxy showing `HEADPHONES 20.0 ms` while playing through its speaker. Now playing's tile now reads `SPEAKER` or `HEADPHONES` by the output in use, and the budget row reads `Output lag`. Please match this in the design, and check Calibration's wording for the speaker.

## 2026-11-02: Time signals, Stream gap and Event log built

These replace the plain screens. All update once a second. Left out because the app has no data for them: the sky plot and constellation list, extra NTP servers, the safety check's age, the event timeline's error trace, tapping and pinching, and the Screen lane. Stream gap says neither "behind" nor "ahead", and adds a `Use recommended` link in Manual. The recommended period is 60 s, so its label reads `rec. 1 min`.

## 2026-11-02: Now playing built, and what differs from the design

The user decided **Now playing updates once per second**. Please update the spec:

- **Sub-second dial and ns digits:** both left out, because they'd sit frozen at 1 Hz, so tapping the dial does nothing. Remove them or tell me what should replace them.
- **Play control:** the design has none, so a temporary `PLAY`/`STOP` button sits under the badge. Please design the real one.
- **Stopped state:** the badge table has none, so I show a neutral `■ STOPPED`, because `NOT IN SYNC` in red looked wrong before the listener has pressed anything. Please confirm or redesign.
- **Not built yet:** the Keep button and progress line, tile and header links (dimmed), and the `REJOINING`, `CLOCK CHECK FAILED` and `SILENT · OVER LIMIT` badges. The Privacy mode mark is a text `⊘`, not the lock icon.
- **Test bench:** holding the station name opens it.

## 2026-11-02: design request: Calibration

The user wants calibration designed by you and placed in Settings. Nothing here is measured on phones yet, so the accuracy figures are estimates and the copy shouldn't promise them. The latest acoustic tests put the Galaxy about 14–21 ms behind the Pixel on speaker, with both on GPS.

**Where it goes:** add a `Calibration` row under Settings › LISTENING, showing the route, lag and where the value came from. I suggest it opens an expanded Headphone lag screen, so there's one home for this rather than two. The HEADPHONES tile and the existing Headphones row should lead to the same place. Your call on the layout and name.

**Three ways a value gets set:**

| Method | What the listener does | What the screen needs |
|---|---|---|
| Shared table | Nothing: the app looks up phone model and output | Source shown as `Table`, and a state for a phone or headset that isn't listed (lag 0, wide ±) |
| Earcup test | Hold a headphone cup or bud against the phone's mic while it clicks through the speaker, then the headphones, for about 10 s | Mic permission, instructions, progress, result, Save and Try again, and failures: too quiet, too noisy, readings disagree |
| Two-phone handshake | Two phones side by side take turns clicking for about 10 s, then one enters a short code from the other | Choosing roles, Start, entering or scanning the code, result, Save, and failures, including `no GPS or NTP good enough` |

**Data each state can show:** route, lag in ms, ± in ms, source (`Table`, `Earcup`, `Handshake`, `Typed`), when it was measured, and for the handshake, the other phone's model. Typed entry stays as an advanced option.

**Other states to design:**

- **New headset:** a prompt, offering the earcup test and a Not now, when the app sees an output with no saved value.
- **Bluetooth:** a note that its delay may change between connections, so a saved value may not hold.
- **Applying a value:** a new value takes effect when playback next starts, because lag is fixed for a run today. Changing it during playback is a timing change that hasn't been agreed. Please show this, or tell me if you think it must apply live.
- **Mic reassurance:** the mic is only on during a test, and no audio leaves the phone.
- **Sync error budget:** the headphone lag bar can now use this ±.

**Decision still open with the user:** whether Privacy mode allows the handshake. It shares only a short code between two people in person, so I'd expect yes.

## 2026-11-02: first run on a real phone, and hand-off 04 items

The test bench has now run on the Pixel 10 Pro, and it matches the design closely. Screenshots aren't in the repo yet. Hand-off 04's nudge row, whole-row tap target and `still failing` lines are built, with these differences:

- **`still failing` lines:** each distinct message is logged once per failure, at most 8. NTP errors name the server and the app cycles through three, so logging every change could add about 225 lines an hour.
- **Nudge row with no run:** it reads `no run`, because `in step` would imply a timeline that isn't playing.
- **Time zones with no abbreviation:** Android gives `GMT-05:00` for zones like Lima, so the local line shortens it to `GMT-5`.
- **Load to design around:** on a debug build, the UI thread uses 18–25% of a core at 10 updates a second. A release build hasn't been measured yet.

## 2026-11-02: test bench states built from hand-off 03

Built, but not yet seen on a phone. Questions for the next design round:

- **Clipped pitch:** the Auto rate nudge row cuts off at `+0…` in `test-bench-states.png`, so the pitch change is lost. Real figures are wider, such as `0.20 ms` and `1.40 s`. Please shorten the row or give it a second line.
- **Tightening line duration:** the spec says when it appears but not when it goes. It now appears once the improvement has lasted 10 s, and stays for 10 s. `tighteningFromNs` used to report during the 10 s check and clear when it passed, which didn't match the spec, so I changed it.
- **Tightening units:** the two figures can differ in units, so one can read `±11.4 ms → ±400 µs`. When they share a unit it reads `±11.4 → ±8.20 ms`, as drawn.
- **Error line height:** the design's 22 px is under the 44 px `touchTarget`. I built 22 px as drawn. Is that right for something tappable?
- **Event log for one lane:** OUTPUT errors open the AUDIO lane, because that's where audio errors are logged. NTP and STREAM open NET. If an error's text changes while it's still failing, the log holds only the first text.
- **Rate nudge row with no gap:** the design doesn't show it. It reads `no gap`.

## 2026-11-01: test bench built from hand-off 02

The test bench is now in Compose, and the theme is generated from `tokens.json` on every build, so token changes reach the app automatically. It compiles, but no one has seen it on a phone, so it hasn't been compared with the PNG yet.

These parts aren't in the design, so I chose something plain. Please design them, or tell me to change them:

- **START RUN and STOPPED:** the design only shows the running state. I used a filled accent button with a triangle, and a `STOPPED` pill in `faint`.
- **Satellites switch, off:** the track uses `grid` and the thumb uses `muted`.
- **Rate nudge row:** a new row under the latest event, because the nudge controls need a route until that screen is built.
- **STREAM row:** a new row in the status grid in stream mode, because the design's grid drops fields needed for stream tests: newest segment, buffered, fetch errors and server minus flowtime.
- **Errors:** audio, NTP and fetch error messages are added to the OUTPUT, NTP and STREAM rows, because the tests need them. They can make those rows wrap.
- **Tightening on the bench:** a small accent line under the source detail.

Temporary: the event log and rate nudge open plain screens until your designs are built.

## 2026-11-01: where the app and decisions stand

Nothing has run on a phone yet: these features pass desktop simulations and the APK builds. `docs/design.md` is out of date, so where it differs from this list, this list is newer.

### Decisions the screens should reflect

- **Product:** a 24-hour radio station and multiple beach silent discos, together. A beach event is many phones on one timeline. Most radio listening is alone, often with the screen off.
- **Time:** Teddington NPL time is the master timekeeper. Phones follow it through GPS. Everything else is non-geographic, and listeners aren't necessarily in the UK.
- **Use satellites:** an On/Off switch, one tap away on the main screen and available at any point, including during playback and at events. Never locked.
- **Satellites off is a first-class mode:** a calm "Privacy mode ± n ms" badge showing the true error, with no amber fault wording, GPS banners or pressure to turn satellites on. Position-based light show patterns need a fallback.
- **Event error limits:** events set a maximum sync error and never require GPS as such. If a phone goes over the limit, offer what could help, such as "Sync from nearby phones".
- **Rate nudge:** Auto by default. Manual shows the gap, a log slider from 1 s to 5 min set to a recommended period, and Go, which becomes Pause while closing. Jump is also offered. The readout shows the real time left and the pitch change, because a short period can't beat the rate limit (a 5 ms gap takes at least 5.6 s).
- **Tightening:** show it only when the error estimate really improves, by at least 20% for 10 s. New satellites with no improvement update quietly, with no animation. Show flowtime's error and the stream's gap as separate things.
- **Event log:** the newest event sits under the badge, one tap opens the log, and banners link to their event. The frontier version is a timeline scope with lanes for CLOCK, AUDIO, NET, OUTPUT and SCREEN over a sync error trace, plus a linked list.
- **Automatic screen levels:** each phone steps between Full, Reduced, Minimal and Off based on heat, missed frames, audio underruns and battery. Every screen needs a design for these levels. Minimal is about one text update a second, and the event log falls back to plain text there.
- **Light show safety:** automatic levels can reduce flashes but can never turn the show on without opt-in. The three-flashes limit and the "no flashing" setting always apply.
- **Buffer:** 60 seconds of audio ahead, shown as seconds in hand.
- **Accessibility:** severity uses words and shapes as well as colour, with support for screen readers and reduced motion.

### Fields that exist today

| Shows | Comes from |
|---|---|
| Flowtime and its error | `FlowTime.fit()`, `utcAt(boot)`, `errAt(boot)` |
| Sync error shown to the listener today | `errAt(boot)` plus the absolute held gap; headphone lag uncertainty isn't included yet |
| Time source: GPS, holdover or NTP | `FlowTime.chosen`, with holdover worked out in `Session.describe` |
| Satellites on or off, and GPS status | `GpsTime.active`, `status` |
| Satellites used and seen | `GpsTime.used`, `seen` |
| Gold or Silver, drift in ppm, fit scatter | `GpsTime.gold`, `FlowTime.gps.slope`, `GpsTime.rmsNs` |
| Time since the last GPS report | `GpsTime.lastBoot` |
| NTP server, stratum, round trip, error bound, gap from GPS | `NtpClient.host`, `stratum`, `rttMs`, `boundMs`, `minusGpsUs` |
| Held gap, time left, closing rate, mode, period | `NudgeControl.gapAt`, `secondsLeft`, `state.rate`, `mode`, `periodS` |
| Audio clock nudge and position error | `Renderer.ratio`, `posErrUs` |
| Jumps, underruns, missing audio | `Renderer.jumps`, `Playout.underruns`, `Renderer.missingFrames` |
| Seconds buffered, fetch errors | `StreamClient.buffered`, `errors`, `lastError` |
| Output route | `Playout.track.routedDevice` |
| Headphone lag, as a typed number | `Settings.lagMs` |
| Event log entries: time, lane and plain text | `EventLog.recent`, `latest` |
| Tightening under way, and the error it started from | `EventDetector.tighteningFromNs` |

### Not in the app yet

Designing these is fine, but they need building first, so please mark them in `ui-spec.md`.

- **Sky plot:** satellite positions and signal strength per satellite. Android provides them, but the app only keeps counts.
- **Several NTP servers:** only one NPL server is in use at a time. There's no ranked list, safety check result or server map.
- **Headphone lag detail:** the source, uncertainty, headset name and calibration. Lag is only a typed number today.
- **Peers and swarm:** peer counts, nearby-phone sync, seeding and download-ahead progress.
- **Events:** event join, QR codes, organiser limits, strict mode and the crowd histogram.
- **Heat and screen levels:** no thermal readings or automatic levels yet.
- **History for traces:** no in-memory history for the sync trace or scope. Only current values are held, and history goes to CSV files.
- **Light show and SCREEN lane:** not built.
- **Playing in the background:** there's no foreground service or notification, so the notification action and reliable screen-off playback don't exist yet.

### Android points to check before relying on them

- From memory and not yet confirmed: Android may kill the app if location permission is revoked in system settings during playback, which would stop the music. The planned response is to restart in the Off state, but that isn't built.
- Precise location is what Android requires for GPS time, so the wording around "Use satellites" should say that position never leaves the phone.

## 2 Nov: Earcup test result screen is unclear (please redesign)

Found in a real run on the Galaxy with ATH-M50xBT2. The result card shows "-19.7 ms ±4.0", then "-39.7 ms later than this phone's speaker", then a match graph labelled "29 ms from the output stamp". The user can't tell which way the correction goes.

**What each number really is** (from `Calibration.kt` and `CalibrationScreens.kt`):
- **Headline (-19.7):** the lag that will be saved = the phone speaker's own lag (+20.0 here) + the measured difference. A larger lag means Brocast sends audio earlier.
- **Second line (-39.7):** headphones minus speaker, measured by the mic. Negative means the headphones were heard *earlier* than the speaker, which is unexpected for Bluetooth, so the wording "later than" with a minus sign is confusing.
- **"29 ms from the output stamp":** the delay from the headphone leg's output time stamp to the mic hearing the click. It is not Android's latency estimate, and it is the mic's measurement, so it isn't a cross-check.
- **±4.0:** the speaker's uncertainty + 1.0 + both legs' scatter.

**Please design:**
1. One plain headline in words, with no signs: "Headphones play X ms later/earlier than the speaker".
2. A separate line for what will be saved and what Brocast will do ("Brocast sends audio X ms early/late").
3. Label the saved value and the measured gap so they can't be confused, and show where the speaker's own value comes from.
4. Rename the match graph's caption to say what it is (delay from the output stamp, from the mic) and say what a good one looks like.
5. Confidence in words (good, unsteady, try again) alongside the ±.
6. A small two-click timeline (speaker, then headphones) showing the gap and its direction.
7. A state for an unexpected direction (headphones earlier than speaker), saying the result is unusual and suggesting a retry.

Existing decisions still apply: the Share this result switch, and "Applies when playback next starts."

## 2 Nov: A Satellites setting that explains the phone's GPS clock (please design)

The user wants listeners to understand their hardware, and probably wants this as a **Satellites** setting inside Settings. `ui-spec.md` already has Time signals and a later Sky plot, so please say whether this is a new detail screen, an extension of Time signals, or the same screen.

**Why:** on a Moto G32 the clock error sits at ±10 ms however many satellites it sees (9 to 15), and it never improves. The cause isn't visible anywhere in the app: the chip declares a 5 ms uncertainty on its own boot-clock stamp (`er_unc_ns`), and the app can't beat it. A listener sees "±10 ms" and a healthy satellite count and can't tell why.

**Data the app already has** (`GpsTime.kt`, per second):
- Chip name (`gnssHardwareModelName`), and whether raw clock reports are supported.
- Gold or Silver (`gold`): whether the chip stamps reports with the boot clock.
- Chip-declared stamp uncertainty (`erUnc`), bias uncertainty (`biasUnc`), and the resulting clock error.
- Satellites in use, leap seconds (and whether the chip supplied them), and the current time source.

**Data it doesn't have:** per-satellite positions and signal strength (counts only today), and which constellations are in use. These need building first, so please mark them *later* in `ui-spec.md`.

**Please design:**
1. A plain verdict, for example "Your phone's GPS chip limits timing to about ±10 ms", with the reason in words.
2. What is limiting it: the chip's declared stamp uncertainty, the satellite signal, or a Silver link, each shown with its figure.
3. Whether more satellites will help. This is the question the user hit. If the limit is the chip, say so.
4. Gold or Silver in plain words, with what it means for sync quality.
5. A state for a chip with no raw clock reports at all (the app falls back to NTP).
6. How it relates to the "Use satellites" switch and to Time signals.

Wording rules still apply: say Flowtime and Brocast clock, not "system time".

## 2 Nov: Earcup results depend on the Bluetooth codec, and one run isn't enough (design impact)

Measured on the Galaxy A16 with one headset (ATH-M50xBT2), cup over the phone's mic, 24 runs. Log: `measure/earcup-results.csv` on the laptop. The headset reports a 360 ms delay under every codec.

| Codec | Headphones vs phone speaker | Spread between runs |
|---|---|---|
| LDAC, adaptive | about 38 ms **earlier** (9 runs) | about ±9 ms |
| SBC | about 3 ms later (10 runs; range 19 ms earlier to 24 ms later) | about ±22 ms |
| AAC | about 90 to 96 ms **later** (5 runs; one outlier at 120) | about ±5 ms |

Inside a single run the clicks agree to 0.01 to 0.06 ms every time, so the scatter the screen uses for its ± says nothing about this spread.

Likely cause (**not confirmed**): Android compensates its output time stamp with the headset's declared 360 ms, which doesn't change with the codec. Each codec's real delay differs, so the leftover error differs too.

**Please change in the Earcup result design:**
1. **The ± and the confidence words can't come from one run.** Today they come from within-run scatter, which is about 0.03 ms. A real result needs the spread across several runs. Suggest a multi-run flow ("Run it three times"), with the confidence words (Good, Unsteady, Try again) based on how far the runs disagree. A single run is at best Unsteady for Bluetooth.
2. **A saved value belongs to one codec.** The same headset can read about 130 ms differently on another codec. Show the codec on the result and on Calibration (`LDAC`, `SBC`, `AAC`), and say that a saved value applies to that codec only. Add a state for "codec changed since this test", which should prompt a retest.
3. **A reconnect or a codec change may invalidate a saved value.** Please extend the existing "Bluetooth delay can change each time headphones connect" note: say why, and when it matters.

**Needs checking on the code side before you design around it:** whether the app can read the active codec without special permissions. I haven't verified this. Treat the codec label as *later* in `ui-spec.md` until I confirm.

Existing decisions still apply: lag is relative to a common reference, no signs on the result screen, and "Applies when playback next starts."

## 2 Nov: No codec switch in the app (decision)

The user asked for a toggle between LDAC and AAC. An ordinary app can't change the Bluetooth codec: it needs a system permission, and today the only route is Android's Developer options (from my knowledge of Android, not tried in this app). So **don't design a codec switch.**

**Please design instead:**
1. **Show the codec in use** (`LDAC`, `AAC` or `SBC`) on the Calibration screen and the Earcup result. Mark it *later* in `ui-spec.md` until I confirm that an app can read it.
2. **A "codec changed" prompt:** when the codec changes after a saved test, offer a retest (same sheet style as the new-headphones prompt). A saved value applies to one codec only. See the section above.
3. **Optional, advanced:** a link row that opens Android's own settings, with a note that it only works if Developer options are already on. Say whether you want it at all.

LDAC sounds best on paper and AAC was the steadiest in timing, so don't promise either. The user's reason for the switch is to compare them, which the codec label and the retest prompt cover.

## 2 Nov: Satellites verdict when the limit is tiny (please add a wording)

Built Satellites (hand-off 13) and ran it on the Galaxy A16 on a good fix. Its chip stamp is ±0.1 ms and the total ±0.2 ms. The screen still says **"Your phone's GPS chip limits timing to about ±0.2 ms"** in an amber box with a LIMIT tag, because the chip stamp is the largest of the three rows. ±0.2 ms is excellent, so the warning is out of proportion.

Please design a fourth verdict, for example "Your phone's GPS clock is accurate to about ±0.2 ms. Nothing is holding it back.", with the accent tick instead of amber and no LIMIT tag. Suggested rule: when the total is under 1 ms, no row is tagged LIMIT. You pick the threshold.

Also built, FYI: the sky and tap-for-detail card are live. Android's satellite status gives height, direction, signal and system per satellite, so they weren't *later* after all. Labels that would overlap now move to another side of their mark; on a real indoor sky the satellites bunch low in one direction.

## 2 Nov: Small times in microseconds (decision)

The user decided: **show small time units in microseconds, consistently, not milliseconds.** Built as: any value under 1 ms is shown in µs (or ns under 1 µs), for example `±400 µs` instead of `±0.4 ms`, and `±10 µs` instead of `±0.01 ms`. From 1 ms upward it stays in ms. This follows the existing `fmtDuration` rule on Now playing and Time signals, and now also covers Satellites, the Earcup result, Calibration and Settings.

Please update the designs and `ui-spec.md` examples that show sub-millisecond values in ms: Satellites (`±0.1 ms`, `±0.00 ms`, `±0.01 ms`, `±0.2 ms`), Now playing's held gap (`0.20 ms`), badges (`±0.4 ms`) and any others. Also built in hand-off 12: the output lag's uncertainty is now in the total (flowtime and output lag combined, plus the held gap), so a phone with no calibration shows about ±40 ms and an amber badge.
