// The listener-count estimate (design note §4): a HyperLogLog sketch that every phone merges with its peers'.
// 1024 registers, one byte each on the wire, about ±3%. Merging takes the larger of each register, so the order
// sketches arrive in doesn't matter and a sketch can be merged any number of times.
import crypto from 'hypercore-crypto'
import b4a from 'b4a'

const P = 10
const M = 1 << P
const ALPHA = 0.7213 / (1 + 1.079 / M)

export class Sketch {
  constructor (registers = b4a.alloc(M)) { this.r = registers }

  /** Adds one listener, hashed with the hour, so a listener who leaves ages out. */
  add (publicKey, hour) {
    const h = crypto.hash(b4a.concat([publicKey, b4a.from(String(hour))]))
    const idx = ((h[0] << 8) | h[1]) & (M - 1)
    // Rank: position of the first 1 bit in the next 32 bits.
    const w = ((h[2] << 24) | (h[3] << 16) | (h[4] << 8) | h[5]) >>> 0
    const rank = w === 0 ? 33 : Math.clz32(w) + 1
    if (rank > this.r[idx]) this.r[idx] = rank
  }

  merge (other) {
    let changed = false
    for (let i = 0; i < M; i++) if (other[i] > this.r[i]) { this.r[i] = other[i]; changed = true }
    return changed
  }

  estimate () {
    let sum = 0
    let zeros = 0
    for (let i = 0; i < M; i++) { sum += 2 ** -this.r[i]; if (this.r[i] === 0) zeros++ }
    const e = ALPHA * M * M / sum
    return e <= 2.5 * M && zeros ? Math.round(M * Math.log(M / zeros)) : Math.round(e)
  }
}
