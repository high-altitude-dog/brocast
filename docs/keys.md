# Identity keys: entropy, clamping and the first-run wizard

7 Nov 2026 (user: generate Ed25519 and X25519 keys from several sources of entropy at first start; make sure this code and the libraries beneath it don't clamp bits and reduce entropy; a wizard advises and guides). Code: `pears/engine/entropy.js`. The wizard screens are Claude Desktop's (see `design/feedback.md`).

## What the secret is

One **32-byte seed**. Everything derives from it by hashing (BLAKE2b with a context, as Corestore does), never by scalar arithmetic:

- the Ed25519 keypair (identity, signatures);
- its X25519 form (sealed boxes, later);
- the Corestore primary key;
- the nym (`docs/nym.md`).

The seed is stored whole. Checked 7 Nov: libsodium's secret key is `seed ‖ public key`, with the seed byte-for-byte unchanged.

## Sources and mixing

`mixSeed(sources)` takes every source the host gathered, adds its own 32 bytes from the OS (libsodium `randombytes_buf`), and mixes them all with **keyed BLAKE2b-256**, each source tagged with its name and length. The output is at least as unpredictable as the strongest single source, so a weak source can't make it worse.

- **Refused:** no machine source from the host (`NEED_MACHINE_SOURCE`), so there are always at least two machine sources. Also refused: an empty, stuck (all one byte) or repeated source.
- **Never:** a seed from user input alone (the "brain wallet" mistake).
- **Hygiene:** inputs are wiped after mixing. The wizard is told only which sources were used and how many bytes each gave, never their values.

| Platform | Machine sources (at least one besides the engine's) | Optional, from the user |
|---|---|---|
| Android | `host-csprng`: Java `SecureRandom` (BoringSSL DRBG). `keystore-hmac`: HMAC-SHA512 of a random nonce under a key generated inside StrongBox or the TEE, so the bytes depend on the hardware's own random generator. | `touch-jitter`: the wizard's scribble pad, with nanosecond timings and coordinates. `sensor-noise`: the low bits of the accelerometer and gyroscope over about 2 s; no permission needed. |
| Desktop seeder | `host-csprng`: the platform's generator. `cpu-jitter`: timing jitter of a tight loop, the jitterentropy method, counted conservatively. | `mouse-jitter`, `key-jitter`: in the desktop UI or the browser control page |

**Honest limits:**

- On Linux and Android, libsodium and `SecureRandom` both draw on the kernel's random generator, so they aren't fully independent. StrongBox is.
- User input adds unpredictability that's hard to measure, so the wizard never claims a number of bits for it.
- The seed's strength never rests on the user's part.

## Clamping (user, 7 Nov: libsodium sometimes clamps unnecessarily)

**Where clamping is required:** Ed25519 key generation (RFC 8032) and X25519 (RFC 7748) both clamp the scalar they derive from the seed: clear the low 3 bits, clear the top bit, set bit 254. That keeps keys out of the curve's small subgroups. It acts on SHA-512 of the seed, not on the seed. The ~128-bit security level is set by the curve, and clamping doesn't lower it.

**Where libsodium clamps when it shouldn't:** its default `crypto_scalarmult_ed25519` and `crypto_scalarmult_ed25519_base` clamp **any** scalar passed to them. That includes scalars that are already valid: a tweaked or derived key, a blinding factor, or anything reduced mod L. Clamping those again changes their value and breaks the maths, which is why the `_noclamp` variants exist.

- **Checked in the installed code, 7 Nov:** HyperDHT's key exchange (`noise-curve-ed`) clamps once itself, from the seed as RFC 8032 specifies, then calls `crypto_scalarmult_ed25519_noclamp`. A tweaked key passes through unclamped.
- **For X25519 sealed boxes:** `crypto_box_seal` clamps inside X25519 a key that `crypto_sign_ed25519_sk_to_curve25519` has already clamped. Clamping a clamped scalar changes nothing (checked: the converted key equals `clamp(SHA-512(seed)[0..32])`), so no information is lost.

**Rules for our code:**

1. Never call the clamping `crypto_scalarmult_ed25519*` functions. Use `_noclamp` after an explicit RFC 8032 clamp of a seed's hash, or on a scalar reduced mod L (`crypto_core_ed25519_scalar_reduce`).
2. Never clamp, mask or truncate the seed or anything derived from it by hashing.
3. Derive keys by hashing the seed (`keyPair(hash(seed ‖ context))`), never by tweaking scalars, unless a design needs tweaks (for example blinded keys). Then apply rule 1.
4. A review check: `grep -rn "scalarmult_ed25519\b\|scalarmult_ed25519_base\b" pears/` must find nothing.

## The first-run wizard (flow; screens by Claude Desktop)

1. **Welcome:** "Your phone makes its own identity. No account, no email, no server." What the key is, and that it never leaves the phone.
2. **Gathering:** the machine sources tick in one by one (name and byte count, never values).
3. **Add your own randomness (optional):** scribble, or move the phone. It's clearly marked as extra and never needed.
4. **Mixing:** one line on how sources are combined, with an expandable "how this works" for curious users: the sources table, keyed BLAKE2b, and the clamping notes above.
5. **Meet your nym:** the nym and the key's first 8 hex digits. Shuffle is offered, and keeps the key.
6. **Back up (optional for listeners):** losing the key only means a new nym. For a seeder it's recommended. For the station's publisher it's required, with a check that the backup was written down (publisher keys are on the security backlog).
7. **Done:** straight on to the existing first-use sharing sheet.

**Storage:**

- **Android:** the seed is encrypted with an AES-GCM key held in the Android Keystore (StrongBox where available) and kept in the app's private files.
- **Desktop:** a file readable only by its owner, in the app's storage.

The engine never writes the seed itself (engine API §9).

## Open

1. **Backup format:** 24 words from a 2048-word list (256 bits plus an 8-bit BLAKE2b checksum), plainly labelled "Brocast recovery words, not a crypto wallet". Using BIP-39 itself would mean its SHA-256 checksum, and would invite confusion with crypto wallets.
2. Should the wizard offer to **re-key** later (a new identity, with the old one announced as retired)? This ties to station key rotation on the security backlog.
