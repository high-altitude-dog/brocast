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
- **Duplicates, unchanged from step 1:** with two sources (station and Pixel), the Galaxy received 428 data messages for 273 blocks. On a metered phone that is 60% more paid data. **The duplicate-block fix matters more on mobile than it did on the LAN.** The cause is now found and fixed on the laptop; see the next section.
- Station egress log: no alerts at start or stop. It uploaded 34.9 MB from the home line and was stopped by hand after the run (it would have stopped at 10 min).

## Duplicate blocks: cause and fix (laptop, local testnet)

- **Cause (confirmed by counting):** Hypercore's *hotswap*. Every block in flight is a candidate, and any peer with fewer than `max(16, maxInflight/2)` requests in flight re-requests it. Our fetch keeps about 4 blocks in flight, so the second peer is never "busy". `replicator.stats.hotswaps` matches the extra data: 106 hotswaps gave 75 extra messages, and 146 gave 100. A cancel can't beat a 72 KB block that is already on the wire.
- **`tools/dup.js`:** station plus two listeners using the phone's fetch loop, 2 runs per mode. Duplicate data: as shipped, 27.5% and 36.6% extra on whichever listener had two sources. With hotswap off, 1.1%. Delayed (only blocks in flight > 1 s), 1.1%. Sharing still works: with hotswap off, B got about 50% of its blocks from A.
- **Stalled peer** (A frozen for 10 s while B was fetching from it; one run per mode): with hotswap off, B's slowest block took 10.0 s. Delayed: 1.25 s, with 14 re-requests to the station. So **delayed** is the fix.
- **Built:** `engine/hedge.js` (`hedge(core, { mode, afterMs })`, by default `delayed`, 1000 ms). It replaces the private `replicator._updateHotswap` and runs a 250 ms check. The worklet calls it for every set core, and `start` takes `hedge` and `hedgeMs`. `hotswaps` is in the status and the report. **Not yet run on the phones.** It patches private methods, so pin Hypercore 11.40.x until there is a supported option.

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

0. For the Pears developers: see the draft message below.
1. For the Pears developers: is a round-1 punch meant to leave `stats.punches` untouched? A per-connection "how it connected" field would help.
2. For the Pears developers: catalog discovery over the public DHT takes about 7.5 s. Would `mutableGet` with `latest: false`, or caching the record, cut that? It matters for a listener's first start.
3. For the user: a run with both phones on mobile needs a second SIM with data, or a hotspot from a third phone. That's still one carrier NAT; two carriers would be better.

## Draft message to the Pears developers (not sent)

> Hi, we're building a P2P music app on Hypercore, Hyperswarm and bare-kit-kotlin 0.2.2 (Android). It fetches pre-recorded sets by key from a station and from other phones. Versions: hypercore 11.40.3, hyperswarm 4.17.2, hyperdht 6.36.0, corestore from npm on 7 Nov. Four questions, with measurements:
>
> 1. **Duplicate blocks from hotswap.** We fetch with `core.get(i)` and keep about 4 blocks (~300 KB) in flight. With two sources, 27–68% of the data received is duplicate blocks: 55–68% on phones over Wi-Fi and LTE, 27–37% on a laptop testnet. `stats.hotswaps` matches the extra exactly. Our reading: a peer with fewer than `max(16, maxInflight/2)` requests in flight hotswaps every in-flight block, so with a small window the second peer always re-requests, and the cancel arrives after a 72 KB block is already sent. We now replace `replicator._updateHotswap` so a block is hedged only after it has been in flight for 1 s. Duplicates drop to 1.1%, and a frozen peer still loses its blocks within about 1.25 s. Is there, or could there be, a supported option for this, such as a hotswap delay, a per-core off switch, or a threshold based on actual inflight? On metered mobile data the duplicates cost users money.
> 2. **Connection stats.** A connection that hole-punches in round 1 (both NATs consistent) leaves `dht.stats.punches` untouched. Only round-2 probes count. Could the stream say how it connected (direct, punched, relayed, LAN)?
> 3. **First lookup.** On the public DHT, `mutableGet` on the station key and then finding the catalog core's peers takes about 7.5 s on a phone, against under 1 s on a LAN testnet. Any recommended settings or caching to cut a listener's first start?
> 4. **Relays and Android packaging.** Are there blind relays you recommend for a mobile-heavy app, or should we always run our own? And could bare-kit-kotlin ship `libc++_shared.so`, which udx-native's prebuild needs, or should add-ons link libc++ statically?
>
> Happy to share the test scripts. Thanks!
