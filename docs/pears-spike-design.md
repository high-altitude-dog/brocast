# Pears spike: design note

Written 7 Nov 2026, for the user's OK before any code. Scope of building: step 0 (laptop only). Everything else here is design.
Package versions were checked on npm on 7 Nov 2026 and get pinned exactly at install.

## 1. Stack

| Piece | Version | Role |
|---|---|---|
| Hypercore | 11.40 | One signed, append-only log per set. Every block is checked against the station's signed Merkle tree on arrival, so a peer can't inject audio. |
| Corestore | 7.16 | Holds every core in one RocksDB store (hypercore-storage 3). One `replicate()` per connection carries every core both sides have open. |
| Hyperswarm / HyperDHT | 4.17 / 6.36 | Finds peers by topic and connects by public key, with hole punching and blind relays. HyperDHT is already proven in `server/dht/`. |
| Protomux | 3.13 | Extra channels on the same connection: the listener-count sketch and the policy hints (§6). |
| Bare | 1.36 | The runtime on the phone. Engine code is written once, Bare-compatible, and runs under Node on the laptop for step 0. |
| bare-kit | GitHub release (not on npm) | Embeds Bare in Android as a worklet. Bundled with `bare-pack`; Kotlin talks to it over `bare-rpc` (1.5). |

Not used: **Hyperdrive** for audio (see §2), **Autobase** (there's one writer, the station), **Pear runtime** (it's a desktop app shell; the phone needs only bare-kit).

**On Android (step 1 onwards, not built now):** the worklet starts inside `SessionService`, so it lives while the screen is off, on its own thread at low priority. A new `MeshSupply : BlockSupply` (next to `LocalSupply`) asks the worklet for block n over RPC and feeds `StreamClient`'s ring. Nothing touches `Playout` or `Renderer`. Mesh work can only make a block late, and a late block already plays silent and stays in sync.

## 2. Library mapping

- **One Hypercore per set (a "set core").** Block i is second i of the set, in today's `opus-20ms-1s` format (50 packets, each with a 2-byte length). At 160 kbps that's about 20 KB a block and 72 MB an hour. So "block" means the same thing on the timeline, on the Buffer screen and on the wire.
- **One catalog core**, signed by the station. Each entry is a library edition: today's `library.json` plus each item's set-core key and block count. The newest entry wins. Phones watch it for appends. This gives the spec its signed schedule and signed file records, and later its waveform summary and cue list.
- **Why not Hyperdrive:** Hyperblobs cuts files into 64 KiB pieces, not seconds. Fetching by deadline would then mean mapping byte ranges back to seconds, and the Buffer screen couldn't show real blocks.
- **Ingest:** the station splits `set.opus` into 1 s blocks with the same logic as `OpusBlocks`, then appends them. A set core is complete once written. Live shows later reuse the same model, at one append a second.
- **Fetch order:** a deadline scheduler, the same idea as `FetchPlan`. In order: a hole in the next 10 s; the playhead up to the low watermark, in order; then the rest of the horizon. A block that's late gets asked of two peers at once.
- **Storage:** played blocks outside Keep are freed with `core.clear()`. Keep marks a set core so that it's never cleared. The Archive limit is the eviction budget.
- **Block length:** 1 s now. Step 0 also measures 2 s and 4 s for proof overhead and request count.

## 3. Identity

- **Station:** one 32-byte seed in `IDENTITY_DIR` (the same pattern as `gateway.js`), never in the repo. Two keys are derived from it: the HyperDHT gateway keypair (exists) and the Corestore primary key. Set cores and the catalog come from the primary key by name. The app ships one public key, the catalog's; the catalog lists the station's seed-server keys.
- **Listener:** an ed25519 keypair made on the phone and kept in the app's private files. It is both the Hyperswarm identity (peers see it as `remotePublicKey`) and the base of the phone's Corestore.
- **Nym:** `BLAKE2b(publicKey ‖ shuffle as u32)` picks one word each from fixed colour, fruit and animal lists (64 × 128 × 256, so about 2 million names). Shuffle adds 1 to the counter and keeps the keypair. Peers get the counter during the connection handshake, so everyone works out the same nym. It's a pure function in a plain-Kotlin `Nym.kt`, tested in `simtest/`. The key is the identity; the nym is a label, and two listeners showing the same nym also show 4 hex digits of their key.

## 4. Real values behind `DemoMesh`

| Demo item | Real source |
|---|---|
| Nym | §3 |
| Swarm: peers, direct or relayed, rates each way | `swarm.connections` for the key and the relay flag (exact field confirmed in step 0); bytes from each core's `download` and `upload` events, which name the peer; station vs listener by matching the catalog's seed keys; found but not connected = `swarm.peers` minus connections |
| Buffer: frontier, holes, rate, source per block | `core.has(i)` from the playhead, and `download` events (index, bytes, peer) into a per-block source map |
| Passed on today | The sum of `upload` bytes, reset at local midnight |
| Listener count | See below |

**Listener count at millions of listeners:** nobody can list every peer, so each phone keeps a **HyperLogLog sketch**. It has 1024 registers (about 768 B) and is accurate to about ±3%. Each phone adds `hash(its key ‖ hour)` and merges with its peers' sketches over a Protomux channel every 60 s; merging takes the larger value of each register. Counts spread across the whole mesh in a few rounds, and the hourly salt lets departed listeners age out. Shown as `≈ 1.2 M listening` (estimate approved by the user, 7 Nov). The risk: a hostile peer can inflate the count. To limit that, seed servers cap what they pass on; the station signing the count is a later option. Phones in Privacy mode don't take part.

## 5. Privacy mode

Turning it on, at once: leave every topic, close all listener connections, stop the sketch and stop serving blocks. Missing blocks come only from the station's seed servers, dialled by key, so only the station sees the phone's address. Already-held blocks keep playing in sync. Turning it off restores the saved choices.

**Unproven:** whether Hypercore 11 can refuse uploads on a connection that stays open. If it can't, "receiving only" (sharing paused) also limits itself to seed servers, and listeners can't send to it.

## 6. Resource profile (new: your 7 Nov request)

The listener chooses how much data, battery, memory and storage the mesh may use. **No setting ever changes the timing path:** the policy only changes when and how blocks are fetched and shared.

**What costs what on a phone:**

- **Battery:** mostly the radio, not the CPU. After each transfer, a cellular radio stays at high power for about 5 to 10 s, so a block a second keeps it awake all the time. **Fetching in bursts** between two watermarks lets the radio sleep for minutes, which is the largest battery saving available. Other costs: DHT announces, connection keepalives, and uploads.
- **Data:** the horizon (how far ahead to download), uploads, and DHT traffic.
- **Memory:** open connections and their buffers, open cores, the RocksDB cache, and requests in flight.
- **Storage:** the horizon, the sharing allowance and Keep.

**Engine settings:**

**Mobile data and Wi-Fi are treated differently (user, 7 Nov).** Mobile data is the listener's paid allowance, so the phone never shares on it unless the listener opts in, in any preset. Wi-Fi usually costs nothing, so sharing there is generous. "Mobile" means any network Android reports as metered, which includes phone hotspots and paid Wi-Fi. Android Data Saver always counts as no sharing on mobile data.

| Setting | Range | Light | Balanced (default) | Generous |
|---|---|---|---|---|
| Low / high watermark (refill when below low, up to high) | 30 s to 24 h | 2 m / 15 m | 5 m / 2 h | 30 m / 24 h |
| **On Wi-Fi:** share | off, while charging, unless battery under 30%, always | while charging | unless battery under 30% | always |
| **On Wi-Fi:** upload rate cap | 0 to unlimited | 1 Mbps | 4 Mbps | unlimited |
| **On mobile data:** download cap per day | 0 to unlimited | 100 MB | 500 MB | unlimited |
| **On mobile data:** share | off, or on with a daily cap | off | off | off |
| **On mobile data:** sharing cap per day (when opted in) | 10 MB to 1 GB | | | |
| Share while playing (user, 7 Nov) | on or off | on | on | on |
| Max connections | 2 to 32 | 4 | 8 | 24 |
| Announce on the DHT | client only, or announce | client only | announce while sharing | announce |
| RAM budget (cache and in-flight requests) | 16 to 256 MB | 24 MB | 64 MB | 192 MB |
| Sharing allowance (storage) | 0 to 20 GB | 1 GB | 4 GB | 10 GB |

- **Moving between networks:** the moment the phone goes from Wi-Fi to metered, uploads stop, DHT announces stop, and the phone keeps only enough connections to download. Back on Wi-Fi, sharing resumes on its own. Every byte is counted against the network it went over, so the figures can say `Shared 1.2 GB on Wi-Fi · 0 MB on mobile data`.
- **Ephemeral DHT node always:** a phone never routes other peers' DHT traffic.
- **Hard floors:** a low watermark of at least 30 s, at least 1 connection, and seed-server fallback, so no setting can starve playback.
- **Auto mode (the frontier part):** the policy reacts to the phone's state, the way screen levels do. Inputs are Wi-Fi or metered, charging, battery %, thermal headroom, Android Data Saver (always obeyed) and Doze. For example, at under 20% battery and not charging, the phone drops to Light and says why (`MESH · LIGHT · battery 18%`). On Wi-Fi and charging, it opens up to Generous.
- **Measured, not guessed:** the settings page shows real figures for this hour and today: MB down (mobile and Wi-Fi), MB shared, connections, and the battery cost of the mesh. The battery cost is estimated from `BatteryManager`'s charge counter against a short baseline with the mesh suspended. Each preset also shows an estimate before it's chosen, calibrated from the phone's own history.
- **Suspend and resume:** when nothing needs fetching and sharing is paused, the worklet suspends its swarm, so the radio and CPU sleep. It wakes at the low watermark.
- **Sharing while playing (user's decision, 7 Nov):** sharing carries on while music plays, unless the listener turns `Share while playing` off. This drops "not playing" from the spec's sharing conditions. The upload runs on the worklet's low-priority thread and never touches the audio thread.
- **Relation to the current spec:** this extends Settings › DELIVERY: Download ahead on mobile data, Share, Only during global parties, and Sharing allowance all become settings in this table. The screens belong to Claude Desktop. After your OK I'll send them the settings, ranges, defaults and the live figures the app can supply, through `design/feedback.md`.

## 7. Headset delays (designed now, built later)

- Each listener has a Hyperdrive whose key is derived from their keypair. A passed test writes `/headsets/<model>/<firmware>/<time>.json`, containing the raw difference from the speaker, scatter, codec, the device-reported name, model and firmware, and the app version. Only passes are written.
- A station indexer follows the drives it learns of from the swarm. It builds a Hyperbee2 keyed by model, firmware and codec, with the median, spread and count, and drops outliers. Phones read only that index. "Indexed by public key" is the drive key; the index never stores nyms.
- `Share this result` is on by default and locked off in Privacy mode. Turning it off later clears the listener's drive and stops seeding it.

## 8. Step 0 plan (laptop only, no sound, no server)

- `pears/` in this worktree: `engine/` (catalog, set cores, scheduler, policy, stats, sketch), `tools/seed.js`, `tools/fetch.js` and `tools/report.js`. Runs on a local HyperDHT testnet, so nothing reaches the public DHT.
- **Source:** a synthetic Opus file made locally with ffmpeg. Only Opus blocks cross the swarm, even over loopback.
- **Runs:** 1 seed and 1 fetcher, then 1 seed and 4 fetchers that also re-share, each at block lengths of 1, 2 and 4 s, and under the Light, Balanced and Generous profiles.
- **Measured:** time to first block; time to fill 5 min and 2 h; per-block latency (p50, p95, max); the fraction of blocks from listeners; wire bytes over audio bytes (proof overhead); RSS and CPU per process; and whether a phone can refuse uploads (§5).
- Load `usb-cooldown` before `npm install`. Results go in `docs/pears-spike-results.md`.

## 9. Decisions and open questions

**Decided by the user, 7 Nov:**

- Sharing also runs while playing, on by default, and the listener can turn it off (§6).
- The listener count is an estimate, shown with `≈` (§4).
- The rule of no Pears branding and nothing that implies endorsement stays until the user says otherwise. The Pears.com submission doesn't need either inside the app.

**Still open:**

1. **Where the code lives:** the brief says `pears/` in this worktree, but the user started this session in an empty `~/Documents/pears-engine`. The recommendation is the worktree, as briefed, kept as a standalone package so it can move later.
2. Are the **Light, Balanced and Generous** presets right, with Balanced as the default, plus Auto mode?
