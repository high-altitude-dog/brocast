// Turns a set's blocks back into an Ogg Opus file the app's player already reads (OpusFile), in a canonical layout:
// OpusHead, OpusTags, then each block's 50 packets on pages of at most 255 lace values. The layout is deterministic, so
// the station publishes the finished file's SHA-256 in the catalog and every phone can check what it built.
//
// While a set is incomplete its file grows in <music>/.building/ (user, 7 Nov). Only a whole, checked file is renamed
// into <music>/, so the music folder never holds a part-built set. A rename on the same disk is atomic.
import fs from 'node:fs'
import path from 'node:path'
import nodeCrypto from 'node:crypto'

const TABLE = new Uint32Array(256)
for (let i = 0; i < 256; i++) {
  let r = i << 24
  for (let j = 0; j < 8; j++) r = r & 0x80000000 ? (r << 1) ^ 0x04c11db7 : r << 1
  TABLE[i] = r >>> 0
}
function crc (buf) {
  let c = 0
  for (let i = 0; i < buf.length; i++) c = ((c << 8) ^ TABLE[((c >>> 24) ^ buf[i]) & 255]) >>> 0
  return c
}

function page (packets, granule, serial, seq, flags) {
  const laces = []
  for (const p of packets) { let n = p.length; while (n >= 255) { laces.push(255); n -= 255 } laces.push(n) }
  const h = Buffer.alloc(27 + laces.length)
  h.write('OggS', 0, 'latin1')
  h[5] = flags
  h.writeBigInt64LE(BigInt(granule), 6)
  h.writeUInt32LE(serial, 14)
  h.writeUInt32LE(seq, 18)
  h[26] = laces.length
  laces.forEach((l, i) => { h[27 + i] = l })
  const out = Buffer.concat([h, ...packets])
  out.writeUInt32LE(crc(out), 22)
  return out
}

/** The packets in one block: each a little-endian 2-byte length, then its bytes. */
export function split (block) {
  const out = []
  for (let at = 0; at < block.length;) {
    const n = block.readUInt16LE(at)
    out.push(block.subarray(at + 2, at + 2 + n))
    at += 2 + n
  }
  return out
}

function opusHead () {
  const h = Buffer.alloc(19) // the same header as the app's OpusBlock.head(): stereo, 48 kHz, no pre-skip
  h.write('OpusHead', 0, 'latin1')
  h[8] = 1
  h[9] = 2
  h.writeUInt32LE(48000, 12)
  return h
}
const opusTags = () => Buffer.concat([Buffer.from('OpusTags'), Buffer.from([7, 0, 0, 0]), Buffer.from('brocast'), Buffer.alloc(4)])

/** Writes one set's pages, block by block, in order. */
export class OggWriter {
  constructor (serial, blocks, sink) {
    this.serial = serial
    this.blocks = blocks
    this.sink = sink
    this.seq = 2
    this.granule = 0
    this.written = 0
    sink(page([opusHead()], 0, serial, 0, 2))
    sink(page([opusTags()], 0, serial, 1, 0))
  }

  add (block) {
    const last = this.written === this.blocks - 1
    const pkts = split(block)
    let cur = []
    let laces = 0
    const flush = (end) => {
      this.sink(page(cur, this.granule, this.serial, this.seq++, end ? 4 : 0))
      cur = []
      laces = 0
    }
    for (const p of pkts) {
      const l = Math.floor(p.length / 255) + 1
      if (laces + l > 255) flush(false)
      cur.push(p)
      laces += l
      this.granule += 960
    }
    flush(last)
    this.written++
  }
}

export const serialOf = (coreKey) => coreKey.readUInt32LE(0)

/** The finished file in memory, for the station to hash. */
export function buildOgg (serial, blocks) {
  const parts = []
  const w = new OggWriter(serial, blocks.length, (b) => parts.push(b))
  for (const b of blocks) w.add(b)
  return Buffer.concat(parts)
}

export const sha256 = (buf) => nodeCrypto.createHash('sha256').update(buf).digest('hex')

/**
 * Builds one set into <music>/.building/<id>.opus as its blocks arrive, in timeline order, then checks it and moves it
 * into <music>/<id>.opus. [get](i) returns block i if this phone holds it, else null.
 */
export class SetBuilder {
  constructor ({ music, id, blocks, serial, sha }) {
    this.building = path.join(music, '.building')
    fs.mkdirSync(this.building, { recursive: true })
    this.tmp = path.join(this.building, id + '.opus')
    this.final = path.join(music, id + '.opus')
    this.sha = sha
    this.done = fs.existsSync(this.final)
    if (this.done) return
    this.fd = fs.openSync(this.tmp, 'w') // a leftover from a crash is rebuilt: its blocks are still in the store
    this.hash = nodeCrypto.createHash('sha256')
    this.writer = new OggWriter(serial, blocks, (b) => { fs.writeSync(this.fd, b); this.hash.update(b) })
    this.busy = false
  }

  /** Writes every block now available in order. Returns 'built', 'mismatch' or null (still building). */
  async pump (get) {
    if (this.done) return null
    if (this.busy) { this.again = true; return null } // the running pump goes round once more
    this.busy = true
    try {
      for (;;) {
        this.again = false
        while (this.writer.written < this.writer.blocks) {
          const b = await get(this.writer.written)
          if (!b) break
          this.writer.add(b)
        }
        if (this.writer.written === this.writer.blocks) break
        if (!this.again) return null
      }
      fs.fsyncSync(this.fd)
      fs.closeSync(this.fd)
      if (this.hash.digest('hex') !== this.sha) { fs.rmSync(this.tmp); this.done = true; return 'mismatch' }
      fs.renameSync(this.tmp, this.final)
      const dir = fs.openSync(path.dirname(this.final), 'r')
      fs.fsyncSync(dir)
      fs.closeSync(dir)
      this.done = true
      return 'built'
    } finally { this.busy = false }
  }
}
