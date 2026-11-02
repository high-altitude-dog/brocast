# Beach Sync prototype

Beach Sync is a phone-only silent disco. Groups of about 100 people, on many beaches, hear one live stream at exactly the same instant. It runs on Android phones and their GPS time, with no local hardware. This repo is the first prototype: it tests how closely two phones agree. The full design is in `docs/design.md`, a snapshot of the living design doc (<https://claude.ai/artifact/1qiZnSEZMoea8ch2Uhe8MV>). The snapshot has no diagrams.

## The one rule

The whole point is to drive the spread of when listeners hear each sound to near zero. Keep the timing path lean:

- **No allocation, locks, logging, I/O or binder-heavy calls** inside `Playout.run()`'s loop or anywhere in `Renderer`.
- **`Flow.kt` and `Renderer.kt` stay plain Kotlin,** with no `android.*` imports, so `simtest/` can test them on a desktop.
- **Flag any change that could add jitter or bias to heard time** before making it.

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
| `android/` | The app: Kotlin, no AndroidX, programmatic UI, package `org.beachsync.proto`. AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1, compileSdk/targetSdk 35, minSdk 31. |
| `android/.../Flow.kt` | `ClockFit`, `FlowTime` (source choice, with 20%-better-for-10-s hysteresis), line fits, `GpsFitter`, `NtpFitter` |
| `android/.../Renderer.kt` | Tick and stream rendering, and the PI rate nudge (±0.1% at most, jump if more than 20 ms out) |
| `android/.../Playout.kt` | The AudioTrack thread: output time stamps → flowtime at the ear → Renderer |
| `android/.../GpsTime.kt` | GnssClock → UTC−boot samples, with full tracking on |
| `android/.../NtpClient.kt` | SNTP to `ntp1`–`ntp3.npl.co.uk` every 16 s; `parseNtp` is pure |
| `android/.../StreamClient.kt` | Manifest and segment fetch into a lock-free ring |
| `android/.../Session.kt` | One test run: wiring, the status panel, CSV logs |
| `android/.../Events.kt` | The plain event log: detects changes in the app's counters on a low-priority thread, never the audio thread. Plain Kotlin, tested in `simtest/`. |
| `android/.../MainActivity.kt` | The screen |
| `server/beachsync_server.py` | The stream server (stdlib only). `--clicks` or `--source FILE` (needs ffmpeg). Serves `/manifest.json` and `/seg/<n>.pcm`. |
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

What's never been done:

- **A Gradle/AGP build.** The original environment couldn't reach Google Maven.
- **A run on a real phone.**

Expect small build fixes. Don't change timing behaviour to make a build pass.

## This machine

- It runs Debian 13. Its JDK is probably 21, which Gradle 8.11.1 and AGP 8.7.3 both accept. Debian 13 doesn't ship openjdk-17.
- `~/Android` and `~/.android` exist, so the SDK is probably at `~/Android/Sdk`. Write `sdk.dir=<path>` into `android/local.properties`.
- File copying may have dropped the executable bit, so run `chmod +x android/gradlew` first.
- Build with `cd android && ./gradlew assembleDebug`. The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.
- The user's UK Debian VM on Google Cloud can host the server for tests over mobile data.

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
