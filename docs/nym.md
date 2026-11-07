# Nym: spec

7 Nov 2026. Each listener's colour-fruit-animal name, for example `AMBER FIG HERON`. It's built in `pears/engine/nym.js`, with the word lists in `pears/engine/nym-words.json` (version 1).

## Derivation (user, 7 Nov: favour Ed25519 and X25519 over SHA-256)

1. The message is `"brocast/nym/v1" ‖ shuffle`, with shuffle as a u32, little-endian, starting at 0.
2. The proof is the listener's **Ed25519 signature** of that message. Ed25519 signatures are deterministic (RFC 8032), so the same key and counter always give the same proof.
3. Each word is picked from `BLAKE2b-256(proof)`: colour = byte 0 & 63, fruit = byte 1 & 63, animal = byte 2. Every list has a power-of-two length, so each word is equally likely.
4. On screen, the three words are shown in capitals, separated by spaces.

**Shuffle** adds 1 to the counter and makes a new proof. The keypair never changes. The host stores the counter (engine API `start` and `shuffleNym`). The engine sends the counter and proof in its hello to each peer, and the peer checks the proof against the key before showing the nym. The result:

- No one can show a nym for a key they don't hold.
- A nym stays verifiable when it travels: in peer lists, or in published headset results (design §7).
- There is no SHA-256 anywhere. Ed25519 uses SHA-512 internally, which is part of the algorithm itself.

## Collisions

There are 64 × 64 × 256 = 1,048,576 names, so at millions of listeners collisions are certain. The key is the identity and the nym is only a label. When two peers on one screen share a nym, both also show the first 4 hex digits of their key. The design note's earlier figure of 64 × 128 × 256 changed to 64 × 64 × 256 because 128 well-known fruits are hard to find.

## Word rules

Each word:

- is lower case ASCII, one word, 2 to 10 letters;
- appears in only one list (so `orange`, `lime`, `plum`, `peach`, `olive` and `lemon` are fruits, not colours; `salmon` is an animal);
- is family-friendly whatever it's paired with. So there are **no primates** and **no skin-tone colour words** (black, white, brown, tan, yellow). Otherwise a colour and an animal attached to a person could make a slur. Bird names with rude readings are left out too;
- isn't **`pear`**, and doesn't contain it. A nym like `AMBER PEAR HERON` could read as Pears branding, which stays out until the user says otherwise.

`python3 -I` on the check script (kept outside the repo) enforces these rules and the counts. Adding or changing a word needs a new list version, because it changes existing nyms. The version is part of the signed message (`v1`).

## On the phone

The engine computes nyms; the host only shows them (`me.nym`, `swarm.peers[].nym` in the engine API). So there's no `Nym.kt`, and no crypto needed in Kotlin.
