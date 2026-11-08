// Step 1: checks the sets a phone built against the laptop's. For each set it rebuilds the file from the station's own
// cores (the same canonical Ogg the phones build) and compares bytes and BLAKE2b with the phone's copy.
// Run after the station has stopped (Corestore holds a lock). Usage: [STATION=net-station] node tools/verify.js <dir of pulled .opus files>...
import fs from 'node:fs'
import path from 'node:path'
import Corestore from 'corestore'
import b4a from 'b4a'
import { keysFromSeed } from '../engine/mesh.js'
import { buildOgg, serialOf, blake2b } from '../engine/builder.js'

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..')
const work = path.join(root, 'work')
const name = process.env.STATION || 'lan-station' // step 2: STATION=net-station
const seed = fs.readFileSync(path.join(work, name + '-seed'))
const store = new Corestore(path.join(work, name), { primaryKey: keysFromSeed(seed).primaryKey, unsafe: true })
const bs = Number(process.env.BLOCK_SECONDS || 4)
const ns = store.namespace('blocks-' + bs)
const catalog = ns.get({ name: 'catalog' })
await catalog.ready()
const ed = JSON.parse(b4a.toString(await catalog.get(catalog.length - 1)))
let bad = 0
for (const it of ed.items) {
  const core = ns.get({ key: b4a.from(it.core, 'hex') })
  await core.ready()
  const blocks = []
  for (let i = 0; i < core.length; i++) blocks.push(await core.get(i))
  const mine = buildOgg(serialOf(core.key), blocks)
  for (const dir of process.argv.slice(2)) {
    const f = path.join(dir, it.id + '.opus')
    if (!fs.existsSync(f)) { console.log(dir, it.id, 'MISSING'); bad++; continue }
    const theirs = fs.readFileSync(f)
    const same = b4a.equals(mine, theirs)
    const b2 = blake2b(theirs)
    if (!same || b2 !== it.blake2b) bad++
    console.log(dir, it.id, same ? 'IDENTICAL' : 'DIFFERENT', `${theirs.length} bytes`, b2 === it.blake2b ? 'blake2b ok' : 'blake2b MISMATCH')
  }
}
await store.close()
process.exit(bad ? 1 : 0)
