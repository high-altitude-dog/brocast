// Laptop test of engine/photos.js, local testnet: no public DHT, no phones, no sound.
// A station publishes artist photos (one core each, catalog item {f, bytes, blake2b, core}); B fetches them by key, writePhoto checks the hash and writes
// <music>/artists/<file>. Checks: byte-identical, tampered refused, already-present not refetched, only listed artists/versions published, no photos = no entry.
// Usage: node tools/artist-test.js
import createTestnet from 'hyperdht/testnet.js'
import fs from 'fs'
import os from 'os'
import path from 'path'
import crypto from 'crypto'
import { Mesh } from '../engine/index.js'
import { readPhotos, havePhoto, writePhoto } from '../engine/photos.js'

const work = fs.mkdtempSync(path.join(os.tmpdir(), 'arttest-'))
const src = path.join(work, 'src'); const dst = path.join(work, 'dst')
fs.mkdirSync(src); fs.mkdirSync(dst)
const files = ['a1-1x1-3.jpg', 'a1-9x16-3.jpg', 'a1-16x9-3.jpg', 'a2-1x1-1.jpg']
for (const f of files) fs.writeFileSync(path.join(src, f), crypto.randomBytes(10_000 + Math.floor(Math.random() * 190_000)))
fs.writeFileSync(path.join(src, 'a3-1x1-1.jpg'), crypto.randomBytes(5000)) // artist not in the library json
fs.writeFileSync(path.join(src, 'a2-1x1-0.jpg'), crypto.randomBytes(5000)) // an old photo version
fs.writeFileSync(path.join(src, 'notes.txt'), 'x')
const ok = (c, w) => { console.log((c ? 'PASS ' : 'FAIL ') + w); if (!c) process.exitCode = 1 }

const photos = readPhotos(src, { a1: { v: 3 }, a2: { v: 1 } })
ok(photos.map((p) => p.f).join() === [...files].sort().join(), 'readPhotos lists only listed artists at their current version: ' + photos.map((p) => p.f))
ok(readPhotos(src, undefined).length === 0 && readPhotos(src, {}).length === 0, 'no artists in the library json: no photos')

const testnet = await createTestnet(4)
const node = (n) => new Mesh({ dir: path.join(work, n), seed: crypto.randomBytes(32), bootstrap: testnet.bootstrap })
const topic = crypto.randomBytes(32)
const A = node('a'); const B = node('b')
A.swarm.join(topic); await A.swarm.flush()
const cat = []
for (const p of photos) {
  const c = A.store.get({ name: 'photo/' + p.blake2b.slice(0, 32) }); await c.ready(); await c.append(p.buf)
  cat.push({ f: p.f, bytes: p.bytes, blake2b: p.blake2b, core: c.key.toString('hex') })
}
await A.swarm.flush(); B.swarm.join(topic); await B.swarm.flush()
const fetched = []
const getP = async (p) => { fetched.push(p.f); const c = B.store.get({ key: Buffer.from(p.core, 'hex') }); await c.ready(); return c.get(0, { timeout: 20000 }) }
for (const p of cat) {
  ok(!havePhoto(dst, p), p.f + ' not on B yet')
  const blk = await getP(p)
  ok(writePhoto(dst, p, blk) === 'built' && havePhoto(dst, p), p.f + ' written, matches the catalog hash')
  ok(fs.readFileSync(path.join(dst, 'artists', p.f)).equals(fs.readFileSync(path.join(src, p.f))), p.f + ' byte-identical')
  const bad = Buffer.from(blk); bad[3] ^= 1
  ok(writePhoto(path.join(work, 'x'), p, bad) === 'mismatch' && !fs.existsSync(path.join(work, 'x', 'artists', p.f)), p.f + ' tampered refused, nothing written')
  ok(writePhoto(path.join(work, 'x'), p, blk.subarray(1)) === 'length', p.f + ' short block refused')
}
ok(!fs.readdirSync(path.join(dst, 'artists')).some((f) => f.endsWith('.tmp')), 'no .tmp left')
// a second pass over the same catalog: everything present with the right hash, so nothing is asked for
const before = fetched.length
for (const p of cat) if (!havePhoto(dst, p)) await getP(p)
ok(fetched.length === before, 'already-present photos not refetched')
fs.writeFileSync(path.join(dst, 'artists', cat[0].f), 'corrupt')
ok(!havePhoto(dst, cat[0]) && cat.filter((p) => !havePhoto(dst, p)).length === 1, 'a corrupt file on disk is refetched (only that one)')
ok(writePhoto(dst, { ...cat[0], f: '../evil.jpg' }, Buffer.alloc(cat[0].bytes)) === 'length' && !fs.existsSync(path.join(dst, 'evil.jpg')), 'a catalog name outside the app\'s pattern is refused')
await A.close(); await B.close(); await testnet.destroy()
fs.rmSync(work, { recursive: true, force: true })
process.exit()
