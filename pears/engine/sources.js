// Where each block of a set came from (hand-off 58): a small table of sources plus one byte per block, kept beside the
// set as <id>.src and deleted with it. Never uploaded. The app reads it for the Where it came from screen and the
// Receiving box.
//
// File: 'BSRC', u8 version (1), u32 LE header length, the header as JSON
//   { blocks, blockSeconds, sources: [{ key, nym, country, route, station }] }
// then `blocks` bytes of source (0 = not from a peer: already on the phone or not yet here; n = sources[n - 1];
// 255 = one of the sources past the 254th), then `blocks` bytes of fetch time in 4 ms steps (0 = unknown, 255 = 1 s or more).
import fs from '#fs'
import b4a from 'b4a'

const MAGIC = 'BSRC'
const MAX = 254

export class SourceLog {
  constructor (file, blocks, blockSeconds) {
    this.file = file
    this.blocks = blocks
    this.blockSeconds = blockSeconds
    this.sources = []
    this.index = new Map() // key → 1-based index
    this.from = b4a.alloc(blocks)
    this.ms = b4a.alloc(blocks)
    this.dirty = false
    this.load()
  }

  /** Picks up an earlier run's log, so a set fetched over two sessions keeps all its sources. */
  load () {
    let buf
    try { buf = fs.readFileSync(this.file) } catch { return }
    try {
      if (b4a.toString(buf.subarray(0, 4)) !== MAGIC || buf[4] !== 1) return
      const n = buf[5] | (buf[6] << 8) | (buf[7] << 16) | (buf[8] << 24)
      const head = JSON.parse(b4a.toString(buf.subarray(9, 9 + n)))
      if (head.blocks !== this.blocks) return
      const at = 9 + n
      if (buf.length < at + 2 * this.blocks) return
      this.sources = head.sources
      this.sources.forEach((s, i) => this.index.set(s.key, i + 1))
      this.from = b4a.from(buf.subarray(at, at + this.blocks))
      this.ms = b4a.from(buf.subarray(at + this.blocks, at + 2 * this.blocks))
    } catch {}
  }

  /** Block [i] arrived from peer [key]; [describe] gives { nym, country, route, station } the first time a peer is seen. */
  record (i, key, ms, describe) {
    if (i < 0 || i >= this.blocks || this.from[i]) return
    let n = this.index.get(key)
    if (!n) {
      if (this.sources.length < MAX) {
        this.sources.push({ key, ...describe() })
        n = this.sources.length
        this.index.set(key, n)
      } else n = 255
    }
    this.from[i] = n
    this.ms[i] = ms == null ? 0 : Math.max(1, Math.min(255, Math.round(ms / 4)))
    this.dirty = true
  }

  /** Writes the log if anything changed: to a temporary file first, then renamed, so a reader never sees half of it. */
  flush () {
    if (!this.dirty) return
    this.dirty = false
    const head = b4a.from(JSON.stringify({ blocks: this.blocks, blockSeconds: this.blockSeconds, sources: this.sources }))
    const pre = b4a.alloc(9)
    pre.set(b4a.from(MAGIC), 0)
    pre[4] = 1
    for (let k = 0; k < 4; k++) pre[5 + k] = (head.length >>> (8 * k)) & 255
    const tmp = this.file + '.part'
    try {
      fs.writeFileSync(tmp, b4a.concat([pre, head, this.from, this.ms]))
      fs.renameSync(tmp, this.file)
    } catch { this.dirty = true }
  }
}
