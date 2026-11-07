// An Ogg Opus file as 1 s blocks, the same bytes the server's OpusBlocks and the phone's OpusFile make:
// 50 packets of 20 ms, each a little-endian 2-byte length and its bytes. A longer block is that many 1 s blocks joined.
// Only compressed audio ever leaves this function, so nothing raw reaches the swarm.
import fs from 'node:fs'

export const PACKETS = 50
export const FORMAT = 'opus-20ms-1s'
const SILENCE = Buffer.from([0xf8, 0xff, 0xfe]) // one 20 ms stereo packet of silence

/** Every audio packet in the file, after OpusHead and OpusTags. */
export function packets (file) {
  const b = fs.readFileSync(file)
  const out = []
  let open = []
  let at = 0
  while (at + 27 <= b.length) {
    if (b.toString('latin1', at, at + 4) !== 'OggS') throw new Error('not an Ogg file')
    const count = b[at + 26]
    let data = at + 27 + count
    let piece = 0
    for (let i = 0; i < count; i++) {
      const lace = b[at + 27 + i]
      piece += lace
      if (lace < 255) {
        open.push(b.subarray(data, data + piece))
        out.push(open.length === 1 ? open[0] : Buffer.concat(open))
        open = []
        data += piece
        piece = 0
      }
    }
    if (piece) { open.push(b.subarray(data, data + piece)); data += piece }
    at = data
  }
  if (open.length || out.length < 3) throw new Error('no complete Opus stream')
  if (out[0].toString('latin1', 0, 8) !== 'OpusHead' || out[1].toString('latin1', 0, 8) !== 'OpusTags') throw new Error('not an Opus stream')
  return out.slice(2)
}

/** The file as blocks of [seconds] each; the last one is padded with silence, as the server does. */
export function blocks (file, seconds = 1) {
  const p = packets(file)
  const per = PACKETS * seconds
  const out = []
  for (let start = 0; start < p.length; start += per) {
    const parts = []
    for (let i = start; i < start + per; i++) {
      const pkt = i < p.length ? p[i] : SILENCE
      const len = Buffer.alloc(2)
      len.writeUInt16LE(pkt.length)
      parts.push(len, pkt)
    }
    out.push(Buffer.concat(parts))
  }
  return out
}
