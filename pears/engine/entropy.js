// The identity seed from several sources of entropy (user, 7 Nov; docs/keys.md).
// The seed is 32 bytes and is the only secret: the Ed25519 keypair, its X25519 form, the Corestore key and the nym all
// derive from it. It is kept whole: no code here or in the host clamps, truncates or masks it. Ed25519 and X25519 clamp
// the scalar they derive from the seed (SHA-512 of it), as RFC 8032 and RFC 7748 require; that is the algorithm, not a
// loss of the seed's entropy, and the curve's ~128-bit security level is unaffected.
//
// Mixing: BLAKE2b-256, keyed with a fixed context, over every source, each tagged and length-prefixed, so no two
// arrangements of the inputs collide. The result is at least as unpredictable as the strongest single source. A weak
// source (a scribble) can't make it worse, and the OS generator is always included, so the user's input is only ever
// extra. Never accept a seed made from user input alone.
import sodium from 'sodium-universal'
import b4a from 'b4a'

const CONTEXT = b4a.from('brocast/seed/v1/mix-context!!!!!') // 32 bytes: the BLAKE2b key
const MIN_OS_BYTES = 32
// Machine sources independent of the engine's own OS draw. At least one is required, so there are always two or more.
export const MACHINE = ['os', 'host-csprng', 'keystore-hmac', 'cpu-jitter']

/**
 * [sources]: [{ name, bytes }], from the host: for example 'os' (required), 'host-csprng', 'keystore-hmac',
 * 'touch-jitter', 'sensor-noise', 'mouse-jitter'. Returns { seed, report }. The report names the sources and sizes, for
 * the wizard, and never contains any of their bytes.
 */
export function mixSeed (sources) {
  const own = b4a.alloc(MIN_OS_BYTES)
  sodium.randombytes_buf(own) // the engine draws from the OS itself too, whatever the host sent
  const machine = sources.filter((s) => MACHINE.includes(s.name))
  if (machine.reduce((n, s) => n + s.bytes.length, 0) < MIN_OS_BYTES) throw new Error('NEED_MACHINE_SOURCE')
  // A bad machine source stops everything. A bad sensor or user source is only dropped, and the wizard shows it.
  const dropped = []
  const kept = []
  const seen = new Set()
  for (const s of [{ name: 'engine-os', bytes: own }, ...sources]) {
    const fault = health(s, seen)
    if (!fault) { kept.push(s); continue }
    if (s.name === 'engine-os' || MACHINE.includes(s.name)) throw new Error(fault + ' ' + s.name)
    dropped.push({ name: s.name, why: fault })
  }
  const all = kept

  const state = b4a.alloc(sodium.crypto_generichash_STATEBYTES)
  sodium.crypto_generichash_init(state, CONTEXT, 32)
  for (const s of all) {
    const name = b4a.from(s.name)
    const len = b4a.alloc(8)
    len.writeUInt32LE(name.length, 0)
    len.writeUInt32LE(s.bytes.length, 4)
    sodium.crypto_generichash_update(state, len)
    sodium.crypto_generichash_update(state, name)
    sodium.crypto_generichash_update(state, s.bytes)
  }
  const seed = b4a.alloc(32)
  sodium.crypto_generichash_final(state, seed)
  for (const s of all) sodium.sodium_memzero(s.bytes) // the inputs are as secret as the seed until mixed
  return { seed, report: { used: all.map((s) => ({ name: s.name, bytes: s.bytes.length })), dropped } }
}

/** What's wrong with a source, or null: empty, stuck on one byte value, or a repeat of another source's bytes. */
function health (s, seen) {
  if (!s.bytes || !s.bytes.length) return 'EMPTY_SOURCE'
  if (s.bytes.length >= 8 && s.bytes.every((b) => b === s.bytes[0])) return 'STUCK_SOURCE'
  const k = b4a.toString(s.bytes, 'hex')
  if (seen.has(k)) return 'REPEATED_SOURCE'
  seen.add(k)
  return null
}
