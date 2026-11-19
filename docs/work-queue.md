# Work queue

Work agreed but not started. Newest first. Move an item out when it is built.

## CRITICAL PATH (user, 19 Nov): done by the end of the next session

**Goal:** a user downloads and installs the APK from GitHub, scans the QR of a station, and receives all of the station's sets/tracks and visualisations over Pears. Everything else waits until this works.

Where it stands (checked 19 Nov): the in-app scan of a `brocast://station/<key>` code already switches station (`ui/FindStation.kt`), and sets already arrive over the mesh by station key (`5e9eca5`, MeshSupply `98bd8f4`). Gaps, in build order:

1. **Visualisations over the mesh.** Built 19 Nov, untested: `pears/engine/visuals.js` (64 KB blocks, one core per visual, hashes in the signed catalog; fetched before the set's audio), `station.js --visuals DIR`, worklet `addVisual`/`gotVisual`, event 106. Next: laptop testnet run, then a phone. User 19 Nov: send them over Pears in whichever chunk size gives the best UX (pick and justify it; the visual must be complete before its set starts on the screen). Today `.viz` and `.nodes` reach a phone only by hand (`LibraryStore.visFile`). The station must publish them in its signed catalog (`pears/tools/station.js`), the engine must fetch and verify them like sets (`pears/host/worklet.js`), and the app must save them where `VisualiserScreen` looks.
2. ~~The visualiser in the release APK.~~ Done 19 Nov (user: "do it"): `preBuild` copies `visualiser/looks` into every build, a release build refuses to build without it, and the Test features switch (which opens the Visualiser) is in release builds too. Not yet committed or released.
3. **A station QR.** User 19 Nov: the QR is the Ed25519 public key of the user who made the station. `station.js` should print the `brocast://station/<key>` code (terminal QR and PNG). Note: its READY line prints the station key second.
4. **A station that stays up long enough** to deliver the whole library. Decided (user, 19 Nov): the laptop station may run up to 1 hour each time it's started (`station.js --minutes`, default and cap 60). Everything else keeps the 10-minute default.
5. **Relay for a fresh install.** Decided (user 19 Nov): **the station QR carries the relay's key** (e.g. `brocast://station/<key>?relay=<key>`; the relay key stays out of the repo and the APK), and **every peer sees the relay as a peer with its own nym** (it shows in the peer lists and Swarm like any listener, so the relay needs its own identity key and must speak `brocast/hello/1`). Background: a GitHub install has no relay key (adb only). Fine where hole punching works (home Wi-Fi, mobile data). Optional: carry the relay key in the station QR.
6. **New GitHub release** (release key, scan for server details), then the end-to-end check on a phone with the app removed. User 19 Nov: "yes" to both; still ask before starting the test run itself.

Parked behind the critical path: the 15 Nov list below, Android NTP rules and verbose test-mode NTP log (user 18 Nov), Running Order highlight on the upload page (user 19 Nov; the live page belongs to the visualiser session's copy), hand-offs h59/h60/h62 directory.

## Next session (user, 15 Nov)

1. **Release with the relay.** Publish a new GitHub release so phones installed from GitHub get the relay support and the engine's catalog-wait fix (commit `193df04`). The relay key stays out of the APK and the repo; give it by adb (`--es relay`) or a later in-app setting.
2. **Offer HTTP-downloaded sets on the mesh.** A phone holding a set from the server should fetch only the station's proofs (tree and signature, not the audio), check its own file's blocks against them, and then serve them like any mesh set. Hypercore has no ready-made import, so this is an engine change plus a test. Until then a phone switching to the mesh fetches its whole library again.
   - **Status 17 Nov:** built 15 Nov (`78668b5`, laptop test passes); phone test still to do, on home Wi-Fi (not café Wi-Fi; no VM relay needed). Plan: fresh station seed (so the phone's core is empty and its mesh store stays), one set the Galaxy already has from HTTP, key by adb, expect `IMPORTED … offered on the mesh` and only proofs on the wire (~5% of the set). Also check the `364fc35` route fix on the next relay run.
3. **Nearby phone-to-phone transport.** Hypercore replication over a direct radio link, for beaches, festivals and venue Wi-Fi that isolates clients or has no internet. Both test phones support Wi-Fi Direct and Bluetooth LE (not Wi-Fi Aware), so Nearby Connections or Wi-Fi Direct. Bridge the Android socket into the engine; test with the laptop station off and no internet.
4. **Peers as blind relays** (user, 15 Nov). Hyperswarm only relays through a node that runs a blind relay and whose key the phones know, and the wider Pears network has no shared relay pool, so on 15 Nov the VM relayed. Instead, a peer outside the isolating network (home station, phone with an open NAT) runs `blind-relay` in the engine and advertises its key (catalog or hello); phones that can't punch pick a relay peer before the VM. Costs the relaying peer data and battery: opt-in, Wi-Fi or unmetered only, with a byte cap.

Context: on 15 Nov, on client-isolated public Wi-Fi, station → Galaxy → Motorola (station off) worked through a blind relay on the test server, byte-identical. A set took about 70 s through the relay; relayed bytes count as the server's egress, so relay tests stay at one set.

## Claude Desktop hand-offs, in build order (user, 15 Nov: "do them in whichever order you prefer")

Done: h61 Time signals on the beat (`deb628a`).

1. **h64 + h63 (screens on data we already have).** h64: a way into every screen (KEPT pill opens Kept, Settings › Archive › Open Kept, Settings › Advanced › Sync view, a new Settings "You" section, fixed back links). h63: the SET LIST button replaces the Library bar on Now playing; the Set list screen (running order from the signed edition, all sets, status on this phone, set sheet). Leave the Receiving and Where-it-came-from rows until 2.
2. **h58 engine: per-block source log**, then the Where it came from screen, the Receiving screen and the set picker (h63 item 4), and the Receiving box's real senders and colours.
3. **h62 Find a station.** The engine needs the mesh station directory (signed listings on a topic; h60 item 2) and a `brocast://station/<key>` QR. Header changes (Settings to top left, Find to top right with the first-start pulse), the Find screen with its radar, lists and scan, states, and switching with Undo.
4. **h59 + h60 Your own station.** The largest: station keys and catalog writer on the phone, Opus encoding on the phone, holder counts, edition rules, co-host key handover and fork safety. Needs 3's directory.

## Mesh findings from the 15 Nov phone test

- **Phones don't announce** on the catalog topic under the balanced profile, so two phones that only look can't find each other once the station is gone (a laptop listener had to announce). Decide who announces (e.g. every sharing phone on unmetered Wi-Fi).
- **Networks that pass only HTTP/HTTPS** (user): HyperDHT is UDP, so it is blocked outright there. Fallback: carry the DHT over WebSocket on TCP 443 (Holepunch `@hyperswarm/dht-relay`) to a relay peer, or have peers tunnel traffic dressed as HTTPS. Detect: DHT not ready after N s, then switch.
- The READY line of `pears/tools/station.js` prints the catalog key first and the station key second; phones need the second.

## Backlog: security and key management (user, 7 Nov: more important once there are users besides the user)

- **Threat model** for the mesh: hostile peers, an inflated listener count, eclipse (a phone cut off from honest peers), and key loss or theft. Starting points: `docs/kademlia-research.md` §5, design note §12 (census).
- **Release key** for engine and seeder updates: kept offline, possibly needing more than one signer, and able to revoke a bad release. With no Play Store, this key is the line between an attacker and code on every phone.
- **Station key rotation:** a record signed by the old key that names the new key, so installed apps follow it without an update. Also a plan for losing the key.
- **Moving the station off the payments VM** before any key ships in an app.

## Server side

- **Artist registry** (4 Nov, from design hand-off 16): one entry per artist with a canonical name, photos (focus point and credit) and alias tags. The names and photos are held by the user and stay out of this repo.
- **Matching at ingest:** case-insensitive, trim and collapse spaces, strip accents, treat `&`, `and`, `x` and `feat.` alike. Anything unsure is held for a person to confirm at upload, never guessed on a phone.
- **Artist id in the signed file record,** and per track in cue lists for sets with several artists.
- **Photos travel like other small files** (compressed, fetched once, kept). Until then they are copied to the phones by hand (see `artist-photos/` in `.gitignore`).
- **Upload page:** a field for the artist and a place to confirm unsure matches.

## Phone side, waiting on the Pears spike

- Real nym from the listener's keypair, listener count and Swarm; Buffer from per-block arrival data. These are built with marked demo data until then.

## Backlog: tests on the phones (user, 9 Nov)

- **Pass it on, a real transfer** (hand-off 53): remove one set from the Galaxy (keep a copy), show its code on the Pixel, scan it on the Galaxy (camera app and in-app Scan), and check the set comes straight from the Pixel, is byte-identical and plays. Then the can't-reach case (different networks, no internet).
- **Silent reactions between phones**: send from one, see it land on the next beat 1 on the other and pulse on Swarm; the 3 s limit and a blocked sender on the phones (laptop test: `pears/tools/react-test.js`).
