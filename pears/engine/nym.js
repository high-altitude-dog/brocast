// The colour-fruit-animal nym (docs/nym.md). It comes from the listener's own Ed25519 signature over a fixed message and
// the shuffle counter. Ed25519 signatures are deterministic (RFC 8032), so the same key and counter always give the same
// nym. Peers verify the proof against the listener's public key, so nobody can show a nym for someone else's key.
// The signature is hashed with BLAKE2b (the stack's own hash) so each word is picked uniformly. No SHA-256.
import crypto from 'hypercore-crypto'
import b4a from 'b4a'
import words from './nym-words.json' with { type: 'json' }

const CONTEXT = b4a.from('brocast/nym/v1')

function message (shuffle) {
  const n = b4a.alloc(4)
  n.writeUInt32LE(shuffle >>> 0)
  return b4a.concat([CONTEXT, n])
}

/** The owner makes the proof once per shuffle, and sends it with the counter in its hello to peers. */
export const nymProof = (secretKey, shuffle) => crypto.sign(message(shuffle), secretKey)

/** Anyone can turn a key, counter and proof into the nym. Returns null if the proof isn't that key's. */
export function nymFrom (publicKey, shuffle, proof) {
  if (!crypto.verify(message(shuffle), proof, publicKey)) return null
  const h = crypto.hash(proof)
  return [words.colours[h[0] & 63], words.fruits[h[1] & 63], words.animals[h[2]]]
}

/**
 * v2 (user, 25 Nov: "deterministic for everyone"): the nym comes from the public key alone, so any device can show anyone's nym, a
 * station's straight from its link or QR. No proof and no shuffle. Same word lists as v1. Matches web/src/engine/station-nym.ts.
 * Test vector: key 00 01 … 1f gives OPAL BANANA SCALLOP.
 */
const CONTEXT_V2 = b4a.from('brocast/nym/v2')
export function nymOfKey (publicKey) {
  const h = crypto.hash([CONTEXT_V2, publicKey])
  return [words.colours[h[0] & 63], words.fruits[h[1] & 63], words.animals[h[2]]]
}

/** As shown on screen: `AMBER FIG HERON`. */
export const nymText = (nym) => nym.join(' ').toUpperCase()
