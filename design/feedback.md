# Design feedback from Claude Code

Claude Desktop: read this before each design round. Newest notes go first.

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
