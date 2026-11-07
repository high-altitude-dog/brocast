# Pears spike: step 0 results (laptop only)

7 Nov 2026. These are local processes on a local HyperDHT testnet: no phones, no server, no sound, no public DHT. The library was three 6-minute sets of stereo pink noise at the station's settings (Opus 160 kbps VBR, 20 ms frames): 18.7 MB, 1083 one-second blocks. Figures are ranges over two full runs, on a laptop busy with its own USB drive. Re-measure on Wi-Fi in step 1 before relying on them.

## What works

- **Fetch by key:** a listener given only the catalog key finds the station, reads the edition and fetches every set. Every block is verified against the station's signed tree. First block in 0.8 to 3 s from a cold process start; the whole library in 3.6 to 12.8 s.
- **Finished files:** each set is built in `music/.building/` and moved to `music/` when whole. Every set, on every node, matched the hash the station published (now BLAKE2b-256) (0 mismatches). A built file round-trips block for block and decodes cleanly in ffmpeg. The app's `OpusFile` reads the same layout.
- **Swarm:** with 4 listeners joining 4 s apart, the later ones took 44 to 73% of their blocks from other listeners. The station sent 50 to 66 MB instead of 75 MB.
- **Receive-only:** copying each block out and clearing it from the core gave 0 bytes uploaded, while 55 to 90% of that node's blocks came from listeners.
- **Privacy mode:** one connection (the station), 0% from listeners, and not counted in the listener estimate.
- **Listener estimate:** the gossiped sketch read exactly 4 with 4 listeners, and 3 when the Privacy-mode node was the fourth.
- **Watermark bursts:** at 20× speed with watermarks at a tenth, Light refilled in 12 or 13 bursts and Balanced in 2. Balanced and Generous had no late blocks after the first.

## Unplug demo (laptop, 4 s blocks)

Two listeners fetched the library and kept the station's DHT record alive with `mutableGet` refresh (8 refreshes each). The station was killed at 25 s. At 35 s a new listener started, knowing only the station's public key. It read the record, found the catalog and the listeners, and took 100% of its blocks from them. It built and verified all three sets in 18.4 s, the first block arriving after 9 s. Caveat: the station's own record would have lasted 48 h anyway, so this shows the refresh works, not that it was needed.

## Block length (your question)

| Transport block | Requests | Wire overhead | Listener CPU | Duplicates in a swarm |
|---|---|---|---|---|
| 1 s | 1083 | 2.3 to 2.8% | 1.7 to 2.3 s | 2 to 16% |
| 2 s | 543 | 2.1 to 2.6% | 1.2 to 1.9 s | - |
| 4 s | 273 | 1.6 to 2.4% | 1.2 to 1.7 s | 0 to 10% |
| 8 s | 138 | 1.5% | 1.0 to 1.7 s | - |

**Recommendation: 4 s blocks for pre-recorded sets.** While the audience grows slowly, few listeners are online together, so the station and always-on seeders carry most of the traffic. Small blocks only pay off when many peers each hold different parts. Per-request cost is what matters now: 4 s blocks need 4× fewer requests, use about 25% less CPU and send fewer duplicates. The cost is that a lost block means 4 s of silence, which is rare with minutes held ahead. 8 s gains little more and doubles that cost. Live shows stay at 1 s. Because `block_seconds` is set per set in the catalog, the station can change it later without an app update.

## Failed, fixed, or still unproven

- **Fixed:** an off-by-one stopped bursts one block short of the high watermark. Light then trickled one block per tick, which is the radio-awake pattern the profiles exist to avoid.
- **Fixed:** a window of 16 in-flight blocks regardless of size flooded the link with 8 s blocks, and retransmissions took the overhead to 13%. The window is now sized by bytes, and the overhead fell to 1.5%.
- **Fixed:** builder races that left sets in `.building`.
- **Unexplained:** swarm wire overhead of 14 to 58%. Duplicates (Hypercore sometimes takes a block from two peers) explain part of it; the rest is probably loopback retransmissions under load. Receive-only mode adds 24% duplicates. This matters on mobile data, so it is the first measurement for step 1.
- **Not built:** the upload rate cap (Hypercore has no upload gate), the RAM budget, Auto mode, the DHT pointer record, relays, blind peers, and engine updates.
- **Memory:** 110 to 146 MB RSS under Node. Bare on the phone is still to be measured.

## Decisions

Approved by the user: sharing while playing is on by default; the listener count is an estimate (`≈`); no Pears branding; the Light, Balanced and Generous presets plus Auto (for now); sharing on mobile data is opt-in only; part-built sets live in `music/.building/`.
Made by me, not yet approved: code in `pears/` in this worktree; one core per set; transport block length per set; canonical Ogg builder; receive-only by copy-and-clear; §10 (using every Pears node) and §11 (updatable engine) of the design note.

## Files and commits on `pears-spike`

- `docs/pears-spike-design.md` (design note), `docs/pears-spike-results.md` (this file), `design/feedback.md` (three entries for Claude Desktop).
- `pears/engine/`: `blocks.js`, `builder.js`, `mesh.js`, `policy.js`, `sketch.js`. `pears/tools/`: `station.js`, `listen.js`, `run.js`, `report.js`. Run with `cd pears && npm install && node tools/run.js && node tools/report.js`.
- Later design docs: `kademlia-research.md`, `engine-api.md` (the contract), `nym.md`, `desktop-seeder.md`; design note §6 Auto mode, §12 topic sharding. Engine: `index.js` (version boundary, topics, DHT record), `nym.js` and `nym-words.json` (Ed25519 nym).
- Crypto (user, 7 Nov): Ed25519 and X25519 favoured; file checks are BLAKE2b-256 inside the Ed25519-signed catalog; no SHA-256 in the engine.
- Commits from `a821556` on; see `git log`. Nothing pushed.

## What the main app needs to adopt it

- A bare-kit worklet in `SessionService` running a `pears/engine` bundle, and a host-side `PearsEngine.kt` speaking a versioned `bare-rpc` API (design §11).
- `MeshSupply : BlockSupply` beside `LocalSupply`, splitting transport blocks into 1 s blocks for `StreamClient`'s ring.
- `LibraryStore` reads finished sets from `music/` as it does today. The engine writes them there.
- Real values for `DemoMesh` from the engine's status stream (design §4), and the listener keypair. The nym comes from the engine (`docs/nym.md`), so there's no `Nym.kt`.

## Questions for the user

1. Step 1 (phones) is on the backburner, by the user's choice (7 Nov).
3. You are now in touch with the Pears developers. Could you raise these with them: a public `setUploading(false)`, blind-peer and relay use, and the duplicate-block behaviour?
