// Artist photos over the mesh (user 23 Nov), the same way as a set's visual (visuals.js): the catalog (signed) lists each photo's
// file name, length and BLAKE2b-256 and the key of its core; a phone writes the file only after the hash matches.
// Layout: one Hypercore per photo holding the whole JPEG as one block (10 to 200 KB, so no chunking), named by its hash: a new photo
// version is a new core. File names are the app's own, <artistId>-<crop>-<v>.jpg, written to <music>/artists/ where LibraryStore looks.
import fs from '#fs'
import path from '#path'
import { blake2b } from './builder.js'

const NAME = /^(.+)-(1x1|9x16|16x9)-(\d+)\.jpg$/
export const PHOTO_MAX = 1024 * 1024 // a block this big is no photo; refused on both sides

/** The station side: every <aid>-<crop>-<v>.jpg in [dir] whose artist is in [artists] (library.json's map) at that photo version. */
export function readPhotos (dir, artists) {
  const out = []
  for (const f of fs.readdirSync(dir).sort()) {
    const m = NAME.exec(f)
    if (!m || artists?.[m[1]]?.v !== Number(m[3])) continue
    const buf = fs.readFileSync(path.join(dir, f))
    if (buf.length && buf.length <= PHOTO_MAX) out.push({ f, buf, bytes: buf.length, blake2b: blake2b(buf) })
  }
  return out
}

/** Whether [music]/artists already holds this photo, checked against the catalog's hash. */
export function havePhoto (music, p) {
  try {
    const f = path.join(music, 'artists', p.f)
    return fs.statSync(f).size === p.bytes && blake2b(fs.readFileSync(f)) === p.blake2b
  } catch { return false }
}

/** The phone side: the block checked, then written and renamed into [music]/artists. 'built' or a reason. */
export function writePhoto (music, p, block) {
  if (!NAME.test(p.f) || block.length !== p.bytes || p.bytes > PHOTO_MAX) return 'length'
  if (blake2b(block) !== p.blake2b) return 'mismatch'
  const f = path.join(music, 'artists', p.f)
  fs.mkdirSync(path.dirname(f), { recursive: true })
  fs.writeFileSync(f + '.tmp', block)
  fs.renameSync(f + '.tmp', f)
  return 'built'
}
