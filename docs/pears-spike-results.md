# Pears spike: results and design state, for the main session

7 Nov 2026, branch `pears-spike` (commits `a821556` to `392ae1b` on `6a0d9d9`, not pushed). Step 0 ran on the laptop only, with a local HyperDHT testnet: no phones, no server, no sound. Then the user moved to **design work only**. Testing uses tokens too fast, so step 1 (phones) is on the backburner.

## Measured in step 0 (18.7 MB test library; ranges over two runs; re-measure on Wi-Fi)

- **Fetch by key works:** every block is verified against the station's signed tree. First block in 0.8 to 3 s; the whole library in 3.6 to 12.8 s.
- **Four listeners joining 4 s apart:** the later ones got 44 to 73% of their blocks from other listeners.
- **Receive-only mode** uploaded 0 bytes. **Privacy mode** used one connection, to the station only.
- **Listener estimate:** the gossiped sketch read exactly 4 with 4 listeners.
- **Finished files:** sets are built in `music/.building/` and moved to `music/` once whole and BLAKE2b-checked: 0 mismatches, and ffmpeg decodes them cleanly.
- **Unplug demo:** the station was killed at 25 s. A newcomer knowing only the station key found the catalog through a DHT record other readers kept alive, took 100% of its blocks from listeners, and built every set in 18.4 s.
- **Block length:** 4 s blocks need 4× fewer requests than 1 s, use about 25% less CPU and send fewer duplicates. 4 s is now the default; the timeline stays at 1 s, and `block_seconds` is set per set in the catalog.
- **Cloud egress guard:** with a 10 MB test cap it tripped at 9.52 MB and refused to restart. An earlier version that checked only once a second overshot to 12.3 MB.
- **Fixed:** a burst off-by-one (Light trickled one block at a time), retransmission floods with large blocks, and builder races.
- **Unexplained:** swarm wire overhead of 14 to 58%. Duplicate blocks are part of it; this is the first thing to measure on real Wi-Fi.

## Approved by the user

- **Sharing:**
  - on while playing by default;
  - only ever opt-in on mobile data (any metered network), with its own cap; generous on Wi-Fi.
- **Resource profiles:**
  - Light, Balanced and Generous, plus an Auto mode with adjustable thresholds;
  - limits are assessed from the device, never fixed.
- **Listener count:** an estimate shown with `≈`.
- **Branding:** no Pears branding or implied endorsement. The user is now in touch with the Pears developers.
- **Use every Pears node,** not only Brocast users. **Stations are global:** reachable by anyone who selects them, with no local-only mode.
- **Engine updates:** the engine is an updatable, roll-back-able module. No Google Play, so updates come over the mesh.
- **Seeders:**
  - The desktop seeder is a Pear app plus a headless mode. Station seed servers are that headless seeder and hold no secret key.
  - A browser page on a local machine is the seeder's control panel, because the current Hypercore can't run in a browser.
- **Seeding cost:** seeding goes cheapest first. Cloud seeds are a last resort, with a **1 GB a month cap: shut down and alert at 95%, and stay down until the user acts**.
- **Crypto:**
  - Ed25519 and X25519 preferred over SHA-256: the nym is the listener's Ed25519 signature, and file checks use BLAKE2b inside the signed catalog;
  - identity seeds mix several machine sources and **every phone sensor**, through keyed BLAKE2b, with a first-run wizard;
  - the seed is never clamped, and clamping libsodium calls are avoided.
- **Backlog:** security and key management, until there are other users (`docs/work-queue.md`).

## Docs and code to read (all in this worktree)

- **Docs:**
  - `docs/pears-spike-design.md`: the design note, §1 to §13;
  - `docs/engine-api.md`: the host–engine contract;
  - `docs/desktop-seeder.md`, `docs/keys.md`, `docs/nym.md`, `docs/kademlia-research.md`;
  - `design/feedback.md`: about 9 new entries for Claude Desktop, which the user says it already has.
- **Engine and tools:**
  - `pears/engine/`: blocks, builder, mesh, policy (presets, sharing, `auto()`), sketch, nym, entropy, and `index.js` (version boundary, sharded topics, DHT record);
  - `pears/seeder/`: `assess.js` (machine-assessed limits) and `egress-guard.js`;
  - `pears/tools/`: station, listener, runner and report. Run with `cd pears && npm install && node tools/run.js`.

## What the main app needs to adopt it

- A bare-kit worklet in `SessionService` running the `pears/engine` bundle, and `PearsEngine.kt` speaking `docs/engine-api.md` over `bare-rpc`, with the health gate and version slots (design §11).
- `MeshSupply : BlockSupply` beside `LocalSupply`, splitting 4 s blocks into 1 s blocks for `StreamClient`'s ring. Built sets play from `music/` through `LocalSupply`, unchanged.
- The first-run wizard (`makeSeed`), with the seed in the Android Keystore. `DemoMesh` values map to fields in the status snapshot (API §6). No `Nym.kt` is needed.

## Designed, not built (don't mistake these for working code)

- **On the phone, the whole engine host:** the bare-kit worklet, `PearsEngine.kt`, `MeshSupply`, the wizard and Keystore storage. Nothing in `android/` has changed on this branch.
- **Topic sharding:** only the topic format exists, with one shard. Not built: the shard count growing with the audience, the census, and the change-over (design §12).
- **Auto mode:** `auto()` exists as a pure function only. Nothing feeds it phone state yet.
- **Engine updates:** the version boundary is declared in `index.js`, but there are no slots, health gate, release core or rollback.
- **Seeder:**
  - no seeder app, Pear packaging or browser control page;
  - `assess.js` probes and computes limits, but nothing enforces them;
  - the egress guard is wired into the step 0 station only.
- **Seeding cost classes:** the cost order (cloud seeds only under deadline pressure, seeding the seeders first, super-seeding) is design only.
- **Not started:** headset delays (design §7), blind peers, relays, the desktop seeder's shard bridging, and the station directory.

## Open questions

1. **Backup format:** 24 recovery words with a BLAKE2b checksum? (BIP-39 would bring back SHA-256.)
2. How should a tripped egress cap reach the user: an ntfy webhook, email, or the dashboard only?
3. **For the Pears developers:** a public `setUploading(false)`, a per-request peer priority and upload rate limit, blind-peer and relay use for showcase apps, and the duplicate-block behaviour.
4. A station directory, so listeners can choose a station, isn't designed yet.
