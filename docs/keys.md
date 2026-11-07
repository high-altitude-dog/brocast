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

- **Refused:** no machine source from the host (`NEED_MACHINE_SOURCE`), so there are always at least two machine sources. A faulty machine source (empty, stuck on one byte, or repeating another) also stops key generation. A faulty sensor or user source is dropped and listed instead.
- **Never:** a seed from user input alone (the "brain wallet" mistake).
- **Hygiene:** inputs are wiped after mixing. The wizard is told only which sources were used and how many bytes each gave, never their values.

| Platform | Machine sources (at least one besides the engine's) | Optional, from the user |
|---|---|---|
| Android | `host-csprng`: Java `SecureRandom` (BoringSSL DRBG). `keystore-hmac`: HMAC-SHA512 of a random nonce under a key generated inside StrongBox or the TEE, so the bytes depend on the hardware's own random generator. | Every phone sensor (next section), plus `touch-jitter`, the wizard's scribble pad |
| Desktop seeder | `host-csprng`: the platform's generator. `cpu-jitter`: timing jitter of a tight loop, the jitterentropy method, counted conservatively. | `mouse-jitter`, `key-jitter`: in the desktop UI or the browser control page |

### Phone sensors: as many as the phone has (user, 7 Nov)

The host gathers sensor noise while the wizard's welcome screen is up, so it adds no waiting.

1. **Every hardware sensor, discovered at run time.** `SensorManager.getSensorList(TYPE_ALL)` returns every sensor the phone has, so nothing is hard-coded. Typically:
   - accelerometer, gyroscope and magnetometer, with their **uncalibrated** variants, whose raw bias and noise are better entropy;
   - barometer, light, proximity, ambient temperature and humidity where present;
   - hinge angle on foldables;
   - the fused sensors (gravity, linear acceleration, rotation vectors). These are included even though they're derived from the others, because mixing can't make the seed weaker.
2. **Sampling:**
   - each sensor at its fastest rate for about 3 s, recording the full 32-bit float values (the low bits are the noise) and each event's nanosecond timestamp. The jitter between event times is a source in itself;
   - `HIGH_SAMPLING_RATE_SENSORS` is declared (a normal permission, granted at install) so Android 12+ doesn't cap the rate at 200 Hz.
3. **Other no-permission sources:**
   - battery current and voltage (`BATTERY_PROPERTY_CURRENT_NOW` and the battery broadcast), read rapidly;
   - the touch pad's timings and coordinates;
   - timing jitter from a tight loop (`cpu-jitter`).
4. **With a permission the listener has already granted:**
   - GNSS raw measurements (pseudorange rates, carrier-phase and clock noise), only while Use satellites is on;
   - Wi-Fi and Bluetooth scan signal strengths, only if their permissions are already held.

   **None of these in Privacy mode,** and the app never asks for a permission just for this.
5. **Opt-in, in the wizard, each with its own permission prompt:**
   - **Camera:** a dark-frame shot of a covered lens; sensor shot noise is excellent entropy.
   - **Microphone:** 1 s of room noise, using the low bits only. It's recording, not playback, so the sound rules aren't affected; nothing is kept.
6. **Each source is named separately** (for example `sensor-gyroscope-uncalibrated`), so the wizard can tick each one in.
7. **Faults:**
   - A sensor with no noise (stuck readings, all identical) is **dropped and listed**, and doesn't stop key generation.
   - A machine source with a fault stops it.
8. **Privacy:** raw sensor data is never stored, logged or sent. It goes straight into the mixer and is wiped. Location-bearing sources (GNSS, Wi-Fi, Bluetooth) are mixed and destroyed in the same call, so even a crash dump can't hold a map of where the phone was.

**On the desktop:** the counterparts are CPU and disk timing jitter, mouse and keyboard timing, battery readings on laptops, and an opt-in microphone or webcam dark frame through the browser control page.

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
