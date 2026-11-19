// A set's visualisation (the visualiser's <id>.viz and <id>.nodes) over the mesh, user 19 Nov: in whichever chunk size gives the best UX,
// complete before its set starts on the screen.
//
// Chunk size: 64 KB. A set's visual is about 1.5 to 4.5 MB, so 25 to 70 blocks. That is the size of a 4 s audio block (Opus at
// ~128 kb/s), so the engine's fetch window (in blocks, ~300 KB in flight) and the delayed hedge behave the same for both; the Merkle
// proof per block is a few hundred bytes (<1%); a block lost to a peer that vanishes costs 64 KB again, not megabytes. Bigger blocks
// would save requests but not time (the transfer is bandwidth-bound) and make each stall longer; smaller ones only add requests.
// A set's visual is fetched before its audio, so with ~5% of a set's bytes it is whole long before the set is due.
//
// Layout: one Hypercore per visual, holding the .viz bytes then the .nodes bytes. The catalog item (signed, in the station's log)
// carries the core key and each file's length and BLAKE2b-256; a phone writes the files only after both hashes match.
import fs from '#fs'
import path from '#path'
import { blake2b } from './builder.js'

export const VIS_BLOCK = 64 * 1024
export const VIS_FILES = ['viz', 'nodes']

/** The station side: the visual files of set [id] in [dir], or null if either is missing. */
export function readVisual (dir, id) {
  const files = VIS_FILES.map((ext) => path.join(dir, id + '.' + ext))
  if (!files.every((f) => fs.existsSync(f) && fs.statSync(f).size > 0)) return null
  const bufs = files.map((f) => fs.readFileSync(f))
  const all = Buffer.concat(bufs)
  const blocks = []
  for (let i = 0; i < all.length; i += VIS_BLOCK) blocks.push(all.subarray(i, i + VIS_BLOCK))
  return { blocks, meta: Object.fromEntries(VIS_FILES.map((ext, k) => [ext, { bytes: bufs[k].length, blake2b: blake2b(bufs[k]) }])) }
}

/** Whether [music] already holds this visual, checked against the catalog's hashes. */
export function haveVisual (music, id, v) {
  try {
    return VIS_FILES.every((ext) => {
      const f = path.join(music, id + '.' + ext)
      return fs.existsSync(f) && fs.statSync(f).size === v[ext].bytes && blake2b(fs.readFileSync(f)) === v[ext].blake2b
    })
  } catch { return false }
}

/** The phone side: the whole visual from its blocks, checked, then each file written and renamed into [music]. 'built' or a reason. */
export function writeVisual (music, id, v, blocks) {
  const all = Buffer.concat(blocks)
  if (all.length !== v.viz.bytes + v.nodes.bytes) return 'length'
  const parts = { viz: all.subarray(0, v.viz.bytes), nodes: all.subarray(v.viz.bytes) }
  for (const ext of VIS_FILES) if (blake2b(parts[ext]) !== v[ext].blake2b) return 'mismatch'
  for (const ext of VIS_FILES) {
    const f = path.join(music, id + '.' + ext)
    fs.writeFileSync(f + '.tmp', parts[ext])
    fs.renameSync(f + '.tmp', f)
  }
  return 'built'
}
