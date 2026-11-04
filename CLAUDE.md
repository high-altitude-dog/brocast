# Beach Sync prototype

Beach Sync is a 24-hour radio station and silent discos on many beaches, on one system. It is no longer simply a streaming app: it plays pre-downloaded sets and tracks in sync with flowtime, with the files meant to reach phones over a Pears mesh of local-network, phone-to-phone and internet peers (not built yet; today they come from a server). Every listener hears the same audio at exactly the same instant, on Android phones with no local hardware. Teddington NPL time is the master timekeeper, reached through each phone's GPS, and everything else is non-geographic. The target is millions of listeners with no per-listener traffic cost to the host. This repo is the first prototype: it tests how closely two phones agree. The full design is in `docs/design.md`, a snapshot of the living design doc (<https://claude.ai/artifact/1qiZnSEZMoea8ch2Uhe8MV>). The snapshot has no diagrams.

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
- **Share every design decision or change** with the other side as soon as it's made. If two conflict, the most recent wins. Either side can ask for clarity at any time.

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
| Rushing | The audio is heard by the user ahead of flowtime. Always use this word. |
| Dragging | The audio is heard by the user behind flowtime. Always use this word. |
| Catching-up | The audio is being nudged faster to catch up. Always use this word. |
| Slowing-down | The audio is being nudged slower. Always use this word. |

## Sign conventions

- `ClockFit` offset means UTC minus boot clock, in ns.
- The measure script's gap is phone B's time minus phone A's. Positive means phone B is later.
- Stream sample n is heard when flowtime at the ear equals `start_utc_ns + n / 48000 s + D`.
- A larger headphone lag makes the app output earlier.

## Layout

| Path | What it is |
|---|---|
| `android/` | The app: Kotlin, package `org.beachsync.proto`. AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1, compileSdk/targetSdk 35, minSdk 31. Jetpack Compose (and the AndroidX it needs) is allowed for screens only; the rest stays free of AndroidX. |
| `android/.../ui/` | Compose screens. `Theme.kt` uses `Tokens`, generated from `design/tokens.json` by the `generateTokens` Gradle task on every build; never copy token values by hand. The app opens on `NowPlaying.kt`; holding the station name, or a launch from the test scripts, opens `TestBench.kt`. `SettingsPage.kt` and `DetailScreens.kt` (Time signals, Stream gap, Event log) are built from the design, with parts that have no data left out. |
| `android/.../Flow.kt` | `ClockFit`, `FlowTime` (source choice, with 20%-better-for-10-s hysteresis), line fits, `GpsFitter`, `NtpFitter` |
| `android/.../Renderer.kt` | Tick and stream rendering, and the PI rate nudge that tracks the audio clock (±0.1% at most, jump if more than 20 ms out) |
| `android/.../Nudge.kt` | How a gap closes when flowtime moves: Auto, Manual, Go and the period. The audio thread only reads one immutable `NudgeState`. Plain Kotlin, tested in `simtest/`. |
| `android/.../Playout.kt` | The AudioTrack thread: output time stamps → flowtime at the ear → Renderer |
| `android/.../GpsTime.kt` | GnssClock → UTC−boot samples, with full tracking on |
| `android/.../NtpClient.kt` | SNTP to `ntp1`–`ntp3.npl.co.uk` every 16 s; `parseNtp` is pure |
| `android/.../StreamClient.kt` | Manifest and segment fetch into a lock-free ring |
| `android/.../OpusDecoder.kt`, `OpusBlock.kt` | Decode the 1 s Opus blocks on the stream thread, with Android's software decoder, before they reach the ring. `OpusBlock.kt` is plain Kotlin, tested in `simtest/`. |
| `android/.../LibraryPlan.kt`, `LibraryStore.kt` | The beta's library: one shared looping timeline, each file downloaded once, played from the phone through `StreamClient`'s ring. `LibraryPlan.kt` is plain Kotlin, tested in `simtest/`. |
| `android/.../FetchPlan.kt` | Which segment to fetch next, skipping any that can't arrive in time. Plain Kotlin, tested in `simtest/`. |
| `android/.../Calibration.kt` | The arithmetic for the latency table, earcup test and two-phone handshake. Plain Kotlin, tested in `simtest/`. |
| `android/.../EarcupTest.kt` | The earcup test's audio and the output's name, on its own threads and audio objects, only when no run is active. Results go to `earcup-results.csv` in the app's files. |
| `android/.../Session.kt` | One test run: wiring, the status panel, CSV logs |
| `android/.../SessionService.kt` | The foreground service that keeps a run alive off screen, with a plain notice and a Stop button. Never touches the audio thread. The designed media notification isn't built yet. |
| `android/.../Events.kt` | The plain event log: detects changes in the app's counters on a low-priority thread, never the audio thread. Plain Kotlin, tested in `simtest/`. |
| `android/.../MainActivity.kt` | The screen |
| `server/beachsync_server.py` | The stream server (stdlib only). `--clicks` or `--source FILE` (needs ffmpeg). `--library DIR` plays what the upload page has put on air. Serves `/manifest.json`, `/seg/<n>.opus`, `/library.json`, `/file/<id>.opus` and `/on_air.opus`. Every transfer is compressed: no raw audio ever crosses a network. |
| `server/brocast_admin.py` | The upload page for test sets (stdlib, needs ffmpeg). No password, so only ever behind an SSH tunnel. `server/test_library.py` tests it with the stream server. |
| `server/dht/`, `server/Dockerfile.dht` | Reach the server by its ed25519 public key over HyperDHT: `gateway.js` on the server, `connect.js` as a test client. The key locates the server, so it stays out of this repo, like its address. |
| `measure/beachsync_measure.py` | Analyses a WAV recording (numpy). Modes: `ticks-stereo`, `ticks-tones`, `music-stereo`. |
| `measure/quicktest.py` | The acoustic two-phone test as one command: use this first. Phones stay silent until the test, then tick for about 10 s at five pitches, giving the gap at each. It logs to `measure/results.csv`, which git ignores. `measure/test_pattern.py` tests its analysis. |
| `measure/earcup_run.py` | Runs the earcup test over adb (10 s countdown, then about 15 s of clicks) and logs each result with the Bluetooth codec, adaptive-bitrate state and the headset's reported delay, to `measure/earcup-results.csv`, which git ignores. |
| `android/.../TickWindowReceiver.kt` | Starts and stops the tick pattern's sound over adb, for `quicktest.py`. Only adb can send it. |
| `simtest/SimTest.kt` | Desktop tests of the pure timing code. The commands are in its header. |
| `docs/prototype-test-guide.md` | The human test guide, from build to results |

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
- Real music ran from the test server to the Pixel for 36 minutes on 2 Nov 2026, before the foreground service existed. Leaving the app froze it, and walking out of Wi-Fi range caused two silences of about 2 minutes.

- An acoustic tick test on 2 Nov 2026, through both speakers beside a laptop microphone, on GPS at ±0.2 ms with no lag set, found the Galaxy about 19 ms behind the Pixel and steady to about 0.15 ms. Swapping the pitches flipped the sign. The speaker path is the likely cause, but that isn't proven yet.

What's never been done:

- **A two-phone sync measurement on the rig.** The app has run on the Pixel 10 Pro alone (2 Nov 2026, tick mode, speaker, 11 minutes): Gold GPS, no underruns or jumps. The rate nudge has closed one real gap. The satellites switch and Silver link have only run in simulation.

Don't change timing behaviour to make a build pass.

## This machine

- It runs Debian 13. Its JDK is probably 21, which Gradle 8.11.1 and AGP 8.7.3 both accept. Debian 13 doesn't ship openjdk-17.
- `~/Android` and `~/.android` exist, so the SDK is probably at `~/Android/Sdk`. Write `sdk.dir=<path>` into `android/local.properties`.
- File copying may have dropped the executable bit, so run `chmod +x android/gradlew` first.
- Build with `cd android && ./gradlew assembleDebug`. The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.
- For tests over mobile data, the server runs as the `brocast-test` and `brocast-admin` containers on a test VM (`http://<server-ip>:8765`). That VM also runs other services, so keep the limits in `docs/prototype-test-guide.md`, stop `brocast-test` between tests, never publish the upload page's port, and change nothing else there. This repo is public, so the server's address, login and key names stay out of it.

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
3. Walk through the tests in `docs/prototype-test-guide.md` §5, then run the measure script.
4. Read the phones' logs (`adb pull /sdcard/Android/data/org.beachsync.proto/files/`) with the measure output, and explain the gaps:
   - The mean is a steady offset.
   - The standard deviation is jitter.
   - Check against `status.csv`: source, error, stamp jitter, position error and nudge.

If a result suggests a design change, say so plainly, so the design doc can be updated to match.
