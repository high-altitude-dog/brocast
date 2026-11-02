# Beach Sync prototype

Beach Sync is a 24-hour radio station and silent discos on many beaches, on one system. Every listener hears the same stream at exactly the same instant, on Android phones with no local hardware. Teddington NPL time is the master timekeeper, reached through each phone's GPS, and everything else is non-geographic. The target is millions of listeners with no per-listener traffic cost to the host. This repo is the first prototype: it tests how closely two phones agree. The full design is in `docs/design.md`, a snapshot of the living design doc (<https://claude.ai/artifact/1qiZnSEZMoea8ch2Uhe8MV>). The snapshot has no diagrams.

## The one rule

The whole point is to drive the spread of when listeners hear each sound to near zero. Keep the timing path lean:

- **No allocation, locks, logging, I/O or binder-heavy calls** inside `Playout.run()`'s loop or anywhere in `Renderer`.
- **`Flow.kt` and `Renderer.kt` stay plain Kotlin,** with no `android.*` imports, so `simtest/` can test them on a desktop.
- **Flag any change that could add jitter or bias to heard time** before making it.

Compose screens follow the same rule. They run on the UI thread, read state the timing code already publishes, and never call into the audio thread.

## Design hand-off

UI and visual design happens in Claude Desktop. Code happens here. Work passes between them through `design/`:

| File | Written by | What it is |
|---|---|---|
| `design/tokens.json` | Claude Desktop | Colours, type, spacing and corner radii: the single source for the look. Turn it into the app's theme; don't copy values by hand. |
| `design/screens/` | Claude Desktop | Each screen as a PNG and standalone HTML, showing exactly what to build |
| `design/ui-spec.md` | Claude Desktop | What each element shows, which app field it comes from, its states, and what tapping it does |
| `design/feedback.md` | Claude Code | Designs that ask for data the app doesn't have or that Android can't do. Phone screenshots can go here too (`adb exec-out screencap -p`). |

- **Edit nothing in `design/` except `feedback.md`.**
- Each hand-off is committed, so `git diff` shows what changed in the design. Read that diff before building.
- Claude Desktop owns screens and visuals. Claude Code owns code and the written spec's engineering. Don't do each other's work.

## Live channel with Claude Desktop

Two append-only logs in `comms/`, which git ignores. Each side writes one and reads the other. This section is the protocol, so never put messages in this file.

| File | Written by | Read by |
|---|---|---|
| `comms/to-code.log` | Claude Desktop | Claude Code |
| `comms/to-desktop.log` | Claude Code | Claude Desktop |

Format, one line per message, under 200 characters: `HH:MM TAG text`. Tags: `BLOCK` (needs an answer before you can continue), `CLAIM` (starting this, so don't overlap), `DONE` (finished, with a path or commit), `ASK` (a question that can wait), `FYI`.

To keep tokens low:

- **Send only what changes the other side's work.** No greetings, acknowledgements, progress chatter or repeats.
- **Point, don't paste.** Give a file, commit or section name; long content goes in a file.
- **Read only new lines:** `tail -n +<next line>` or `tail -n 20`, never the whole log, and never re-read files just because a message arrived.
- **Wait without spending tokens.** Claude Code runs one background wait that exits when `to-code.log` grows, and restarts it after handling a message. No polling from the model.
- **Claim before starting** shared work, and check the other log first.
- **The user decides.** Disagreements go to the user in one line; don't debate in the logs.

## Terms (use these, as the design doc does)

| Term | Meaning |
|---|---|
| Flowtime | The app's best estimate of UTC: the boot clock plus a correction (a `ClockFit`) from the source with the smallest error estimate, either GPS or NTP |
| Boot clock | `SystemClock.elapsedRealtimeNanos()`. Never use "system time" (wall clock) for timing. |
| Holdover clock | The last GPS fit, carried on through GPS loss, with an error bound that grows |
| Android output time stamp | `AudioTrack.getTimestamp()`: frame F left the phone at time T, on the monotonic clock, converted to the boot clock with `monoToBootNs()` |
| Headphone lag | Ear time minus Android's output time stamp. Entered per phone, in ms. |
| Gold / Silver | Gold means the GNSS chip stamps its clock reports with the boot clock; Silver means a report is stamped when it arrives. These are labels only. |
| Brocast clock | Any on-screen display of flowtime |

## Sign conventions

- `ClockFit` offset means UTC minus boot clock, in ns.
- The measure script's gap is phone B's time minus phone A's. Positive means phone B is later.
- Stream sample n is heard when flowtime at the ear equals `start_utc_ns + n / 48000 s + D`.
- A larger headphone lag makes the app output earlier.

## Layout

| Path | What it is |
|---|---|
| `android/` | The app: Kotlin, package `org.beachsync.proto`. AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1, compileSdk/targetSdk 35, minSdk 31. Jetpack Compose (and the AndroidX it needs) is allowed for screens only; the rest stays free of AndroidX. |
| `android/.../ui/` | Compose screens. `Theme.kt` uses `Tokens`, generated from `design/tokens.json` by the `generateTokens` Gradle task on every build; never copy token values by hand. `TestBench.kt` is built from the design. `PlainScreens.kt` holds temporary event log and rate nudge screens until those designs are built. |
| `android/.../Flow.kt` | `ClockFit`, `FlowTime` (source choice, with 20%-better-for-10-s hysteresis), line fits, `GpsFitter`, `NtpFitter` |
| `android/.../Renderer.kt` | Tick and stream rendering, and the PI rate nudge that tracks the audio clock (±0.1% at most, jump if more than 20 ms out) |
| `android/.../Nudge.kt` | How a gap closes when flowtime moves: Auto, Manual, Go and the period. The audio thread only reads one immutable `NudgeState`. Plain Kotlin, tested in `simtest/`. |
| `android/.../Playout.kt` | The AudioTrack thread: output time stamps → flowtime at the ear → Renderer |
| `android/.../GpsTime.kt` | GnssClock → UTC−boot samples, with full tracking on |
| `android/.../NtpClient.kt` | SNTP to `ntp1`–`ntp3.npl.co.uk` every 16 s; `parseNtp` is pure |
| `android/.../StreamClient.kt` | Manifest and segment fetch into a lock-free ring |
| `android/.../Session.kt` | One test run: wiring, the status panel, CSV logs |
| `android/.../Events.kt` | The plain event log: detects changes in the app's counters on a low-priority thread, never the audio thread. Plain Kotlin, tested in `simtest/`. |
| `android/.../MainActivity.kt` | The screen |
| `server/beachsync_server.py` | The stream server (stdlib only). `--clicks` or `--source FILE` (needs ffmpeg). `--library DIR` plays what the upload page has put on air. Serves `/manifest.json` and `/seg/<n>.pcm`. |
| `server/brocast_admin.py` | The upload page for test sets (stdlib, needs ffmpeg). No password, so only ever behind an SSH tunnel. `server/test_library.py` tests it with the stream server. |
| `measure/beachsync_measure.py` | Analyses a WAV recording (numpy). Modes: `ticks-stereo`, `ticks-tones`, `music-stereo`. |
| `simtest/SimTest.kt` | Desktop tests of the pure timing code. The commands are in its header. |
| `README.md` | The human test guide, from build to results |

## Status

What's been verified, which happened before this repo reached this machine:

- All app sources compile with no warnings, using kotlinc 2.0.21 against `android-35.jar`.
- `simtest` passes:
  - NTP parsing is exact to 1 ns.
  - The GPS fit tracks to within 23 ns and recovers drift to within 0.0001 ppm.
  - The playout loop settles at 2–3 µs with a ±80 ppm audio clock.
- The server and the measure script work against synthetic recordings.

Since then, on this machine, on the `prototype-queue` branch:

- `./gradlew assembleDebug` builds, and `simtest` passes 99 checks. `simtest` needs `Events.kt` and `Nudge.kt` too; screens need Gradle, because of the Compose plugin.
- `simtest` now settles at about 2.5 µs with a 3 s tracking period, and about 7 µs rms with ±200 µs of stamp jitter.

What's never been done:

- **A two-phone sync measurement on the rig.** The app has run on the Pixel 10 Pro alone (2 Nov 2026, tick mode, speaker, 11 minutes): Gold GPS, no underruns or jumps. The rate nudge has closed one real gap. The satellites switch and Silver link have only run in simulation.

Don't change timing behaviour to make a build pass.

## This machine

- It runs Debian 13. Its JDK is probably 21, which Gradle 8.11.1 and AGP 8.7.3 both accept. Debian 13 doesn't ship openjdk-17.
- `~/Android` and `~/.android` exist, so the SDK is probably at `~/Android/Sdk`. Write `sdk.dir=<path>` into `android/local.properties`.
- File copying may have dropped the executable bit, so run `chmod +x android/gradlew` first.
- Build with `cd android && ./gradlew assembleDebug`. The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.
- For tests over mobile data, the server runs as the `brocast-test` and `brocast-admin` containers on a test VM (`http://<server-ip>:8765`). That VM also runs other services, so keep the limits in `README.md`, stop `brocast-test` between tests, never publish the upload page's port, and change nothing else there.

## The test rig

| Phone | Model | Connection |
|---|---|---|
| A | Galaxy A16 | Through a USB-C-to-3.5 mm adapter and cable, white plug into the interface's **left** line input |
| B | Pixel 10 Pro | The same way, white plug into the **right** input |

- Neither phone has a headphone jack.
- Both red plugs are left unplugged.
- The USB audio interface (for example a Behringer UCA222) connects to this machine. Record with `arecord -D plughw:<card>,0 -f S16_LE -r 48000 -c 2`.
- Installing the app uses each phone's USB-C port, so install before fitting the adapters.

## First jobs

1. Build the app and fix any build errors.
2. Help install it on both phones with `adb`.
3. Walk through the tests in `README.md` §5, then run the measure script.
4. Read the phones' logs (`adb pull /sdcard/Android/data/org.beachsync.proto/files/`) with the measure output, and explain the gaps:
   - The mean is a steady offset.
   - The standard deviation is jitter.
   - Check against `status.csv`: source, error, stamp jitter, position error and nudge.

If a result suggests a design change, say so plainly, so the design doc can be updated to match.
