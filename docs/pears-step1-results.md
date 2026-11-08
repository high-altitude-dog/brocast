# Pears step 1: results, for the main session

7 Nov 2026, branch `pears-step1` (commits `6fec442`, `f82d574` and this file, on `d70d73d`; not pushed). Two runs on both phones (run 2 unplugged), same Wi-Fi as the laptop: the laptop's own DHT on UDP 49737 plus the step 0 station, 4 s blocks, test library of 3 pink-noise sets (18.7 MB Opus). Nothing played, no server used.

## Works (run 1, phones on USB; run 2 below)

| | Pixel 10 Pro | Galaxy A16 |
|---|---|---|
| Catalog found from the station key alone | 0.59 s | 1.03 s |
| First block / all 273 blocks | 0.71 s / 19.7 s | 1.46 s / 19.4 s |
| Per block, p50 / p95 / max | 204 / 585 / 1309 ms | 153 / 627 / 2299 ms |
| From the other phone | 0% (started first) | 39% |
| Wire overhead (rx over audio) | **3.2%** | **68.4%** |
| Data messages per block held | 1.011 | 1.553 (172 cancels) |
| CPU to done (whole app) | 9.2 s in 20 s | 11.0 s in 20 s |

- **Files match:** all 3 sets on both phones are byte-identical to the laptop's rebuild from the station's cores, and BLAKE2b matches the signed catalog (`tools/verify.js`).
- **Overhead explained:** it is duplicate blocks. With one source (Pixel) it is 3.2%. With two sources (Galaxy: station and Pixel) Hypercore sent 454 requests for 273 blocks, cancelled 172, and still received 151 extra data messages (blocks it already had). Framing, proofs and retransmissions are small. The Pixel retransmitted 1528 packets while uploading 13.7 MB to the Galaxy (Wi-Fi loss); the station 334.
- **Run 2, cables out, started by hand:** the Galaxy went first this time. It got 3.2% overhead, the Pixel (two sources) 55.5%, with 418 data messages for 273 blocks and 174 cancels: the same pattern, so the overhead finding holds. All blocks in 17.5 s (Galaxy) and 21.5 s (Pixel); first block 0.8 s and 2.5 s; CPU 10.8 s and 10.0 s to done.
- The station's egress log had no alerts at start or stop; it and the DHT were stopped by hand after the run.

## Not done or unproven

- **Battery, upper bound only:** in run 2 the charge counter didn't move between start and done (0 µAh) on either phone. Its step looks like 2.5 mAh on the Pixel and 4.5 mAh on the Galaxy (inferred from the deltas seen), so fetching 18.7 MB used less than one step: under 0.1% of either battery. The mean current includes time after plugging back in, so it isn't usable. For a real figure: integrate `CURRENT_NOW` samples over the fetch window only (the app keeps only the mean today), or fetch a much larger library.
- One run only; no repeat, no larger library, no screen-off or background run (the test app is an Activity, no foreground service).
- 10-minute auto-stop is built into the app and `tools/lan.js` but wasn't reached.

## Toolchain (confirmed from the projects' repos, releases and JitPack, 7 Nov)

- bare-kit 3.0.x is now C only. Android bindings: **bare-kit-kotlin v0.2.2** (JitPack, AAR 53.5 MB with `libbare-kit.so` for all ABIs; needs compileSdk 36, Kotlin stdlib 2.4.20). **bare-pack 3.0.2** `--preset android`, **bare-link 4.2.0** `--host android-arm64`, **bare-rpc 1.5.0**. Bare runtime inside bare-kit (v1.33.5 per its v3.0.0 notes; inferred for bare-kit-kotlin 0.2.2).
- Test app: AGP 8.7.3, Gradle 8.11.1, Kotlin 2.4.20, SDK platform 36 (installed, 146 MB), `android.suppressUnsupportedCompileSdk=36`. **No NDK** — but `udx-native`'s prebuild needs `libc++_shared.so`, which bare-kit doesn't ship. The build fetches that one file (1.8 MB) from the NDK r27c zip by HTTP range (`fetch_from_zip.py`). APK 62 MB (arm64 only; bare-kit 42 MB, rocksdb 13.5 MB).
- `bare-link` ignores import maps, so the engine now imports `#fs` and `#path` through `package.json` `imports` (bare → bare-fs/bare-path, default → Node). Step 0 tools still run under Node.

## Decisions

- User approved: the toolchain installs (7 Nov), with cooldowns. Not approved, my choice as recommended: the laptop's own DHT on the LAN rather than the public HyperDHT (user asked about disk instead of answering; nothing reached the internet).
- Mine: separate package `org.beachsync.pearstest`; fresh store and random seed every run; bare-rpc command numbers 1 hello, 2 start, 3 subscribe, 4 status, 5 stop, events 100 status, 101 arrivals, 102 built, 103 warning, 104 fatal; `start` takes extra `bootstrap` and `dirs.log` (step 1 only); status has a `step1` block with the wire counters.

## Files

- `pears/android-test/`: Gradle project, `js/worklet.js` (engine host), `MainActivity.kt` (screen, CPU/battery), `Rpc.kt` (bare-rpc framing in Kotlin), `phone.sh` (install, start, peek, pull).
- `pears/tools/lan.js` (DHT + station, 10 min auto-stop), `verify.js`, `phone-report.js`, `library.js`. `engine/mesh.js`: `wire()` adds UDX packets and retransmits.

## Next (step 2 and the main app)

- Step 2: the station on the public HyperDHT (`station.js` without `BOOTSTRAP`) and `start` without `bootstrap`, phone on mobile data; same `phone.sh` and `phone-report.js`.
- Main app: copy `Rpc.kt` and the worklet start from `MainActivity.begin()` into `PearsEngine.kt`; Gradle bits from `app/build.gradle.kts` (packEngine, linkAddons, fetchLibcxx, jniLibs dirs); the main app's Kotlin 2.0.21 must move to ≥ 2.3 for the AAR's metadata.
- Fix before relying on mesh share: duplicate fetching (limit one peer per block, or our own scheduler on top of `core.get`).

## Open questions

1. For the Pears developers: why does Hypercore 11 request one block from several peers on a LAN, and how do we stop cancelled data still arriving (55% extra on the receiver)? Can bare-kit-kotlin ship `libc++_shared.so`, or should addons link libc++ statically?
2. User: is the battery upper bound enough for step 1, or should the app integrate current over the fetch window and run again?
