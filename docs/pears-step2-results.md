# Pears step 2: results, for the main session

8 Nov 2026, branch `pears-step2` (not pushed). One run so far (run 1): the laptop's step 0 station on the **public** HyperDHT from the home network, **Galaxy on mobile data (LTE, Wi-Fi off)**, **Pixel on the home Wi-Fi**. Neither phone was given a bootstrap, and the LAN shortcut was off (`localConnection: false`). Same 18.7 MB test library, 4 s blocks. Nothing played. The test server VM wasn't used. The run with both phones on mobile is **not done**: the Pixel has no mobile data (user, 8 Nov).

## Works (run 1)

| | Pixel 10 Pro (Wi-Fi) | Galaxy A16 (mobile) |
|---|---|---|
| Catalog found from the station key alone | 7.7 s | 7.7 s |
| First block / all 273 blocks | 8.0 s / 24.3 s | 8.4 s / 28.9 s |
| Per block, p50 / p95 / max | 171 / 485 / 783 ms | 251 / 515 / 843 ms |
| From the other phone | 0% | 51% |
| Wire overhead (rx over audio) | **3.2%** | **60.8%** |
| Data messages per block held | 1.011 | 1.568 (170 cancels) |
| Uploaded | 16.3 MB (to the Galaxy) | 0.5 MB |
| CPU to done (whole app) | 10.6 s in 24.5 s | 14.4 s in 29.8 s |

- **Files match:** all 3 sets on both phones are byte-identical to the station's rebuild, and BLAKE2b matches the signed catalog (`STATION=net-station tools/verify.js`).
- **Finding the catalog is the slow part:** about 7.5 s, against 0.6 to 1 s on the LAN DHT. Most of that is the public DHT: about 3.5 s to the station's mutable record, then about 4 s to the catalog. After that the fetch speed is close to step 1 (16 to 21 s for 273 blocks).
- **Mobile data really used** (Galaxy `rmnet0`, before and after): **33.5 MB** (32.3 down, 1.2 up) for 18.7 MB of audio. The quoted estimate was 25–35 MB.
- **Duplicates, unchanged from step 1:** with two sources (station and Pixel), the Galaxy received 428 data messages for 273 blocks. On a metered phone that is 60% more paid data. **The duplicate-block fix matters more on mobile than it did on the LAN.** Still deferred to before step 4.
- Station egress log: no alerts at start or stop. It uploaded 34.9 MB from the home line and was stopped by hand after the run (it would have stopped at 10 min).

## Connection types

| Connection | Opened in | Path |
|---|---|---|
| Galaxy (carrier) → station (home) | 0.76 s | hole-punched, round 1 |
| Galaxy (carrier) → Pixel (home) | 0.56 s | hole-punched, round 1 |
| Pixel (home) → station (home) | 0.77 s | through the home router's public address (hairpin NAT), not the LAN |

- **No failures, no relay needed.** HyperDHT saw both NATs as firewalled but *consistent* (not randomized): the home router's and the mobile carrier's (LTE). Relay counters stayed at 0 everywhere.
- **Round-1 punches move no counter** (confirmed by reading hyperdht 6.36's `connect.js` and `holepuncher.js`): `stats.punches.consistent/random` count only round-2 probes. So `firewalled` with no counter moved means a round-1 punch. The classifier in `engine/mesh.js` now says so. Run 1's log predates the fix, so `phone-report.js` labels its lines "punched (round 1; old log)".
- **Inferred:** the Pixel ↔ station path worked only because the home router does NAT hairpinning. It proves nothing about two homes.
- **Relays, if punching ever fails** (Pears docs, checked 8 Nov; Hyperswarm 4.17.2 code): Hyperswarm's `relayThrough` takes a blind relay's key and uses it only after a punch fails (`HOLEPUNCH_ABORTED`, `HOLEPUNCH_DOUBLE_RANDOMIZED_NATS`, `CANNOT_HOLEPUNCH`, `REMOTE_NOT_HOLEPUNCHABLE`) or when this node's own NAT is randomized. It's wired in now (`phone.sh` `RELAY=<key>`, `relayThrough` in `start`) but not tested. Both peers must name the same relay, and it must sit outside both peers' networks. The Pears CLI ships one (`pear blind-relay start`, Pear ≥ 3.4.0, per the docs). Blind peers (storage) weren't looked at.

## Not done or unproven

- **Both phones on mobile data**, the brief's first run: blocked, as the Pixel has no mobile data.
- Two carriers or two homes, and a randomized (symmetric) NAT. That case is where relays come in, and it isn't tested.
- Only one run. No battery figure (both phones were on USB power).

## Decisions

- User approved: Galaxy on mobile and Pixel on Wi-Fi as the run (8 Nov), its data estimate, and turning the Galaxy's Wi-Fi off over adb (`svc wifi disable`; **still off**).
- Mine: the LAN shortcut off for public-DHT runs, so a same-house path has to go through the router. Dependencies are hard links to step 1's `node_modules`, because a symlink made bare-pack embed step 1's paths and the worklet crashed at start.

## Files

- `engine/mesh.js`: `lan`, `relayThrough` and `onconn` options; a record for each connection (`conns`) and `nat()`.
- `android-test/js/worklet.js`: `start` without `bootstrap` uses the public DHT; logs `conn` lines with `lan`, `relayThrough` and `network`. `MainActivity.kt`: a blank or `-` bootstrap means the public DHT; reports Wi-Fi or mobile; `metered` is true on mobile. `phone.sh start KEY - SERIAL...`.
- `tools/net.js` (public-DHT station, 10 min auto-stop, egress log read at start and stop). `station.js` prints `CONN` and `NAT`. `verify.js` takes `STATION=`. `phone-report.js` prints the connection lines. `build.gradle.kts`: the `fetch_from_zip.py` path is fixed.

## Open questions

1. For the Pears developers: is a round-1 punch meant to leave `stats.punches` untouched? A per-connection "how it connected" field would help.
2. For the Pears developers: catalog discovery over the public DHT takes about 7.5 s. Would `mutableGet` with `latest: false`, or caching the record, cut that? It matters for a listener's first start.
3. For the user: a run with both phones on mobile needs a second SIM with data, or a hotspot from a third phone. That's still one carrier NAT; two carriers would be better.
