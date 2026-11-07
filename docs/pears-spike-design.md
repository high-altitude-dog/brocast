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
- **Block length:** the block on the wire (the transport block) and the block on the timeline are separate. The timeline, the ring and the Buffer screen stay at 1 s. Each set's transport block length is a field in the catalog (`block_seconds`), so the station can change it without an app update, and the phone splits each transport block back into 1 s blocks. Step 0 measured 1, 2, 4 and 8 s: see `pears-spike-results.md`.
- **Finished files (user, 7 Nov):** a set is built as an Ogg Opus file in `music/.building/<id>.opus`, block by block in timeline order. The layout is canonical, so the station publishes the finished file's BLAKE2b-256 in the catalog (inside its Ed25519-signed log). Once whole, the file is checked against that hash, synced to disk and renamed into `music/<id>.opus`; a rename on the same disk is atomic. So `music/` only ever holds whole, verified files. The existing player (`OpusFile`, `LocalSupply`) plays them unchanged. They are plain files, so they survive any engine update or rollback. A leftover in `.building/` after a crash is rebuilt from the store.

## 3. Identity

- **Station:** one 32-byte seed in `IDENTITY_DIR` (the same pattern as `gateway.js`), never in the repo. Two keys are derived from it: the HyperDHT gateway keypair (exists) and the Corestore primary key. Set cores and the catalog come from the primary key by name. The app ships one public key, the catalog's; the catalog lists the station's seed-server keys.
- **Listener:** an ed25519 keypair made on the phone and kept in the app's private files. It is both the Hyperswarm identity (peers see it as `remotePublicKey`) and the base of the phone's Corestore.
- **Nym:** the listener's own deterministic Ed25519 signature of `brocast/nym/v1 ‖ shuffle` picks one word each from fixed colour, fruit and animal lists (64 × 64 × 256, so about a million names). Peers verify it against the key. The engine computes it; the host only shows it. Full spec and lists: `docs/nym.md`.
- **Cryptography (user, 7 Nov: favour Ed25519 and X25519 over SHA-256):**
  - Ed25519 for every identity and signature.
  - X25519 sealed boxes, using the X25519 form of the same Ed25519 key, for anything encrypted to a key later (social features, private results). One keypair does both jobs.
  - BLAKE2b, the stack's own hash, where a digest is needed, always under an Ed25519 signature.
  - No SHA-256 in the engine.
  - Checked in the installed code: HyperDHT's Noise IK transport already does its key exchange directly on Ed25519 keys (`noise-curve-ed`).
  - The old HTTP path (`library.json` on the server) keeps SHA-256 until it's retired. Shuffle adds 1 to the counter and keeps the keypair. Peers get the counter during the connection handshake, so everyone works out the same nym. It's a pure function in a plain-Kotlin `Nym.kt`, tested in `simtest/`. The key is the identity; the nym is a label, and two listeners showing the same nym also show 4 hex digits of their key.

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

**Receiving only (sharing paused, or on mobile data):** Hypercore 11 always tells peers it is uploading and serves any block it holds. Its wire protocol already has an `uploading` flag that peers obey, but no public API sets it. Until it does, a phone that isn't sharing copies each verified block into its `.building` file and clears it from the core, so peers find nothing to ask for. Step 0 showed this works: 0 bytes uploaded, while most of its blocks came from listeners. Better: ask the Pears developers for a public `setUploading(false)`.

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
- **Auto mode (the default; user, 7 Nov: sensible defaults the listener can adjust).** Rules in order, first match wins (`auto()` in `pears/engine/policy.js`):

  | Phone state | Profile | Reason shown |
  |---|---|---|
  | Android Battery Saver on | Light | `Battery Saver on` |
  | Thermal at or above the hot level (default severe) | Light | `phone hot` |
  | Not charging, battery at or under the low line (default 20%) | Light | `battery 18%` |
  | Mobile data with Data Saver on | Light | `Data Saver on` |
  | Wi-Fi, charging, thermal below moderate | Generous | `on Wi-Fi and charging` |
  | Anything else | Balanced | `on battery`, `on mobile data` or `phone warm` |

  - **No flapping:** a change towards less use happens at once. A change towards more use waits 5 minutes after the last change. A charging change counts only after 30 s, to allow for loose cables. Light for low battery is left only at 10 points above the low line.
  - **The listener can adjust:**
    - the low-battery line (10 to 50%);
    - whether Auto may pick Generous;
    - whether Generous needs charging;
    - the hot level (moderate or severe);
    - or turn Auto off and pin a preset.
  - **Not adjustable**, because they are safety rules that sit outside Auto: no sharing on mobile data unless opted in, Data Saver obeyed, and the floors.
  - **On screen:** `MESH · <PROFILE> · <reason>`, plus the time of the last change.
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

## 10. Using every Pears node, not just Brocast listeners (user, 7 Nov)

Other Pears apps' users don't hold our audio, so they can't carry its bandwidth unless they choose to. They can help with everything else:

1. **The public HyperDHT** is run by every Pears app's long-lived nodes. It already finds our peers and coordinates hole punching. Phones stay ephemeral; desktop seeders run as full DHT nodes, so Brocast gives back.
2. **DHT records:** the station signs a small mutable record holding the newest catalog length and the seed keys, stored on the DHT. Phones then learn about new editions even with the station offline, which is the unplug demo. **Proven on the laptop, 7 Nov:** HyperDHT's `mutableGet` has a `refresh` option that re-puts the record it reads, signature included, so every reader keeps it alive without the station's key. Mutable records live 48 h. The app ships only the station's public key and finds the catalog through this record.
3. **Blind relays:** when hole punching fails (carrier-grade NAT on both phones), traffic goes through a blind relay, which can't read it. Holepunch runs relays for its own apps, and using them needs their OK. Any Brocast desktop seeder can also act as a relay.
4. **Blind peers** (`blind-peering`): always-on nodes that keep cores available, as Keet uses. This is the most direct way existing Pears infrastructure could carry our library. Ask whether a pool exists for showcase apps.
5. **A desktop seeder for any Pears user:** `pear run pear://<key>`, one command, the same engine and resource profile, seeding the library from a home computer. This is how Pears users who aren't phone listeners can carry the bandwidth.

## 11. An engine the app can update and roll back (user, 7 Nov)

Pears modules change often, and peers on different versions can fail to talk, as seen with Keet. So the engine is a separate module the app can swap.

- **Two layers:** a stable host in the APK (Kotlin, bare-kit and the native add-ons: udx, RocksDB, sodium) and a **JS engine bundle** (made with `bare-pack`) that runs in the worklet. Only the bundle updates over the air. A native change ships through the store.
- **A narrow, versioned contract:** the host speaks engine API vN over `bare-rpc`. The full contract, with commands, events, the status snapshot and errors, is in `docs/engine-api.md`. Each bundle declares the API versions, native add-on versions, wire protocol and storage format it supports. The host refuses a bundle that doesn't match.
- **Releases come over the mesh:** a release core signed by a separate release key, kept offline. Each record holds the version, bundle hash and size, API, native add-ons, wire and storage versions, and a rollout percentage. HTTPS is the fallback.
- **Slots:** current, previous (last known good) and candidate are all kept on the phone.
- **Health gate:** the candidate must boot within 5 s, complete the API handshake, read the catalog, verify a set number of blocks, and run 10 minutes of playback without a crash. If it passes, it becomes current. If it fails, the app rolls back to the last good version at once and blocks that version on this phone.
- **Never mid-music:** the switch happens at Stop or when idle. Playback never depends on the engine anyway, because finished files play from `music/`.
- **Listener control:** Settings › Advanced › Engine shows the current version and lets the listener roll back, pin a version, or turn auto-update off.
- **Storage is never migrated in place:** the identity seed belongs to the host, not the engine, and finished music is plain files. Each storage format gets its own store (`store-v<N>/`). An engine with a new format starts a fresh store and re-fetches what it needs. The old store stays until the new one has been healthy for 24 h, so a rollback is instant.
- **Mixed versions on the wire:** the swarm topic includes the wire protocol's major version (see §12). An engine that speaks two versions joins both topics during a transition, and seed servers run one process per supported major version. Peers swap engine versions in a hello message, so the Swarm screen can show the mix of versions.
- **Staged rollout:** a phone takes a release only if `hash(its key)` falls under the rollout percentage.
- **Distribution (user, 7 Nov):** the app won't ship through Google Play, so Play's rule against downloaded code doesn't apply. Engine bundles update over the mesh as above. A native change ships as a new APK, which can itself come over the mesh, signed by the release key.

## 12. Topic sharding: finding peers at millions of listeners (user, 7 Nov)

Background is in `kademlia-research.md` §1. One topic is stored on the ~20 DHT nodes closest to its hash, and those nodes belong to other people's Pears apps. Announcements expire after 20 minutes, and a lookup returns at most 20 peers per node. With a million sharers on one topic, each of those nodes would get about 1,600 requests a second. Even then, a phone would see only a tiny, unfair sample of the swarm.

- **The topic:** `hash("brocast/topic" ‖ catalog key ‖ wire major ‖ shard)`, for shard 0 to S − 1. The wire major is the version boundary from §11.
- **Which shard:** S is a power of two. A listener's shard is the top log2(S) bits of `hash(its public key)`, so everyone works out the same shard for any key, and a listener can't pick a crowded or empty one.
- **Where S comes from:** a `shards` field in the catalog, and in the DHT pointer record (§10). Phones learn of a change without waiting for a new edition.
- **Who announces:** only phones that are sharing now (normally on Wi-Fi), each on its own shard. Receive-only phones, phones on mobile data and Privacy mode never announce, so they add no DHT load.
- **Who looks up:**
  - A sharer looks up its own shard, plus one random other shard at each refresh.
  - A receive-only phone looks up two random shards.
  - The random shard keeps the mesh one connected graph, not S islands. The sketch gossip then crosses shards.
- **Seed servers** are dialled directly by key (`joinPeer`), not found by topic, so every shard reaches them without the seeds announcing S times.
- **Sizing:** about 1,000 announcing sharers per shard. That's about 1.7 requests a second to each node holding a shard, which is polite to other apps' nodes.
  - S doubles when a shard averages over 2,000, and halves when one averages under 250.
  - It changes at most once a day, so shards don't flap.
- **Splitting cleanly:** when S doubles, shard i becomes shards 2i and 2i + 1. The extra key bit sends each listener to one of the two, so peers already connected mostly stay in the same shard.
- **Change-over:** for one record lifetime (20 minutes) after S changes, a sharer announces on both its old and its new shard, then drops the old one.
- **Slow growth:** S = 1 at launch, which is exactly what step 0 runs (one topic, the catalog's discovery key). Nothing changes until about 2,000 sharers.
- **A census to check the count:** a lookup returns at most 20 records, so a 1,000-peer shard can't be counted by looking it up. Instead:
  - A sharer also announces on one census topic, `hash("brocast/census" ‖ catalog key ‖ c)` for c in 0 to C − 1, but only if `hash(its key)` falls below a sampling fraction p.
  - The station sets p and C in the catalog so each census topic holds about 10 records, comfortably under the cap of 20.
  - Counting the records in a few random census topics, then multiplying by C / p, estimates the number of sharers.
  - The sketch carries a second set of registers for sharers only (another 768 B). The census is compared against that set: a hostile peer could inflate the sketch, but faking census records needs real reachable addresses.
  - Cost: p extra announces per sharer, for example 1% at a million listeners.
- **Privacy mode** leaves every shard and census topic at once, as in §5.

## 13. Decisions and open questions

**Decided by the user, 7 Nov:**

- Sharing also runs while playing, on by default, and the listener can turn it off (§6).
- The listener count is an estimate, shown with `≈` (§4).
- The rule of no Pears branding and nothing that implies endorsement stays until the user says otherwise. The Pears.com submission doesn't need either inside the app.

- The Light, Balanced and Generous presets, with Balanced as the default, plus Auto mode (approved for now).
- Partly built sets live in `music/.building/` and move to `music/` only when whole.
- Topic sharding goes in the design (§12). Not built yet; topics already carry the wire version and shard, with one shard.
- No Google Play: engine updates go over the mesh (§11).
- Phone testing (step 1) is on the backburner, because testing uses tokens too fast. The focus is design work.

**Still open:**

1. **Where the code lives:** the code is in `pears/` in this worktree, as the brief said. The user didn't answer.
