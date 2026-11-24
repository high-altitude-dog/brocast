// A set's visualisation (the visualiser's <id>.viz and <id>.nodes) over the mesh, user 19 Nov: in whichever chunk size gives the best UX,
// complete before its set starts on the screen.
//
// Chunk size: 64 KB. A set's visual is about 1.5 to 4.5 MB, so 25 to 70 blocks. That is the size of a 4 s audio block (Opus at
// ~128 kb/s), so the engine's fetch window (in blocks, ~300 KB in flight) and the delayed hedge behave the same for both; the Merkle
// proof per block is a few hundred bytes (<1%); a block lost to a peer that vanishes costs 64 KB again, not megabytes. Bigger blocks
// would save requests but not time (the transfer is bandwidth-bound) and make each stall longer; smaller ones only add requests.
// A set's visual is fetched before its audio, so with ~5% of a set's bytes it is whole long before the set is due.
//
// Layout: one Hypercore per visual, holding the .viz bytes, the .nodes bytes, then the optional .ft.nodes (htdemucs_ft stems). The catalog item (signed, in the station's log)
// carries the core key and each file's length and BLAKE2b-256 (an old catalog has no ft entry); a phone writes the files only after every listed hash matches.
import fs from '#fs'
import path from '#path'
import { blake2b } from './builder.js'

export const VIS_BLOCK = 64 * 1024
export const VIS_FILES = ['viz', 'nodes', 'ft.nodes']
const listed = (v) => VIS_FILES.filter((ext) => v[ext])

/** The station side: the visual files of set [id] in [dir], or null if viz or nodes is missing; ft.nodes rides along when present. */
export function readVisual (dir, id) {
  const has = (ext) => { const f = path.join(dir, id + '.' + ext); return fs.existsSync(f) && fs.statSync(f).size > 0 }
  if (!has('viz') || !has('nodes')) return null
  const exts = VIS_FILES.filter(has)
  const bufs = exts.map((ext) => fs.readFileSync(path.join(dir, id + '.' + ext)))
  const all = Buffer.concat(bufs)
  const blocks = []
  for (let i = 0; i < all.length; i += VIS_BLOCK) blocks.push(all.subarray(i, i + VIS_BLOCK))
  return { blocks, meta: Object.fromEntries(exts.map((ext, k) => [ext, { bytes: bufs[k].length, blake2b: blake2b(bufs[k]) }])) }
}

/** Whether [music] already holds this visual, checked against the catalog's hashes. */
export function haveVisual (music, id, v) {
  try {
    return listed(v).every((ext) => {
      const f = path.join(music, id + '.' + ext)
      return fs.existsSync(f) && fs.statSync(f).size === v[ext].bytes && blake2b(fs.readFileSync(f)) === v[ext].blake2b
    })
  } catch { return false }
}

/** The phone side: the whole visual from its blocks, checked, then each file written and renamed into [music]. 'built' or a reason. */
export function writeVisual (music, id, v, blocks) {
  const all = Buffer.concat(blocks)
  const exts = listed(v)
  if (all.length !== exts.reduce((n, ext) => n + v[ext].bytes, 0)) return 'length'
  const parts = {}
  let at = 0
  for (const ext of exts) parts[ext] = all.subarray(at, at += v[ext].bytes)
  for (const ext of exts) if (blake2b(parts[ext]) !== v[ext].blake2b) return 'mismatch'
  for (const ext of exts) {
    const f = path.join(music, id + '.' + ext)
    fs.writeFileSync(f + '.tmp', parts[ext])
    fs.renameSync(f + '.tmp', f)
  }
  return 'built'
}
