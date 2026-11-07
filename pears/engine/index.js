// The engine module's boundary (design note §11). The host (the app) checks these numbers before it runs a bundle:
// API is the host-to-engine contract, WIRE the peer protocol (part of every topic), STORAGE the on-disk format.
// Everything the host may call goes through here, so a new bundle can change the internals freely.
import crypto from 'hypercore-crypto'
import b4a from 'b4a'

export const ENGINE = { version: '0.1.0', api: [1], wire: 1, storage: 1, native: { 'udx-native': 1, 'rocksdb-native': 3, 'sodium-native': 5 } }

/** A swarm topic (design note §12): one per catalog, wire version and shard. Step 0 runs with a single shard. */
export function topic (catalogKey, shard = 0, wire = ENGINE.wire) {
  const n = b4a.alloc(5)
  n[0] = wire
  n.writeUInt32LE(shard, 1)
  return crypto.hash([b4a.from('brocast/topic'), catalogKey, n])
}

/** Which shard a listener belongs to, from its key: the top log2(S) bits, so doubling S splits each shard in two. */
export function shardOf (publicKey, shards) {
  if (shards <= 1) return 0
  const bits = Math.log2(shards)
  return crypto.hash(publicKey).readUInt32BE(0) >>> (32 - bits)
}

/** The station's pointer record on the DHT (design note §10): which catalog, how long, how many shards. */
export const encodeRecord = (r) => b4a.from(JSON.stringify(r))
export const decodeRecord = (b) => JSON.parse(b4a.toString(b))

export { Mesh, keysFromSeed, hex } from './mesh.js'
export { profile, sharing, PRESETS, FLOORS } from './policy.js'
export { Sketch } from './sketch.js'
export { SetBuilder, buildOgg, blake2b } from './builder.js'
export { nymProof, nymFrom, nymText } from './nym.js'
export { mixSeed, MACHINE } from './entropy.js'
export { blocks } from './blocks.js'
