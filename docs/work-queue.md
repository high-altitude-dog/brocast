# Work queue

Work agreed but not started. Newest first. Move an item out when it is built.

## Next session (user, 15 Nov)

1. **Release with the relay.** Publish a new GitHub release so phones installed from GitHub get the relay support and the engine's catalog-wait fix (commit `193df04`). The relay key stays out of the APK and the repo; give it by adb (`--es relay`) or a later in-app setting.
2. **Offer HTTP-downloaded sets on the mesh.** A phone holding a set from the server should fetch only the station's proofs (tree and signature, not the audio), check its own file's blocks against them, and then serve them like any mesh set. Hypercore has no ready-made import, so this is an engine change plus a test. Until then a phone switching to the mesh fetches its whole library again.
3. **Nearby phone-to-phone transport.** Hypercore replication over a direct radio link, for beaches, festivals and venue Wi-Fi that isolates clients or has no internet. Both test phones support Wi-Fi Direct and Bluetooth LE (not Wi-Fi Aware), so Nearby Connections or Wi-Fi Direct. Bridge the Android socket into the engine; test with the laptop station off and no internet.

Context: on 15 Nov, on client-isolated public Wi-Fi, station → Galaxy → Motorola (station off) worked through a blind relay on the test server, byte-identical. A set took about 70 s through the relay; relayed bytes count as the server's egress, so relay tests stay at one set.

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
