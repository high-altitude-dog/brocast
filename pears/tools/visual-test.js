// Laptop test of engine/visuals.js (critical path gap 1), local testnet: no public DHT, no phones, no sound.
// A station publishes a synthetic visual (<id>.viz + <id>.nodes) as one core of 64 KB blocks; B fetches the blocks by key,
// writeVisual checks both hashes and writes the files; a tampered block must be refused. Usage: node tools/visual-test.js
import createTestnet from 'hyperdht/testnet.js'
import fs from 'fs'
import os from 'os'
import path from 'path'
import crypto from 'crypto'
import { Mesh } from '../engine/index.js'
import { readVisual, writeVisual, haveVisual } from '../engine/visuals.js'

const work = fs.mkdtempSync(path.join(os.tmpdir(), 'vistest-'))
const src = path.join(work, 'src'); const dst = path.join(work, 'dst')
fs.mkdirSync(src); fs.mkdirSync(dst)
fs.writeFileSync(path.join(src, 's1.viz'), crypto.randomBytes(1_700_000))
fs.writeFileSync(path.join(src, 's1.nodes'), crypto.randomBytes(300_001))
const ok = (c, w) => { console.log((c ? 'PASS ' : 'FAIL ') + w); if (!c) process.exitCode = 1 }

const testnet = await createTestnet(4)
const node = (n) => new Mesh({ dir: path.join(work, n), seed: crypto.randomBytes(32), bootstrap: testnet.bootstrap })
const topic = crypto.randomBytes(32)
const vis = readVisual(src, 's1')
ok(vis && vis.blocks.length === 31, 'readVisual: ' + vis?.blocks.length + ' blocks')
const A = node('a'); const core = A.store.get({ name: 'vis/s1' }); await core.ready()
await core.append(vis.blocks)
A.swarm.join(topic); await A.swarm.flush()
const B = node('b'); const bc = B.store.get({ key: core.key }); await bc.ready()
B.swarm.join(topic); await B.swarm.flush()
const t = Date.now()
const got = []
for (let i = 0; i < core.length; i++) got.push(await bc.get(i, { timeout: 20000 }))
console.log('B fetched', got.length, 'blocks in', Date.now() - t, 'ms; wire rx', (B.wire().rx / 1024).toFixed(0), 'KB')
ok(!haveVisual(dst, 's1', vis.meta), 'B does not have it yet')
ok(writeVisual(dst, 's1', vis.meta, got) === 'built', 'writeVisual built the files')
ok(haveVisual(dst, 's1', vis.meta), 'files match the catalog hashes')
ok(['viz', 'nodes'].every((e) => fs.readFileSync(path.join(dst, 's1.' + e)).equals(fs.readFileSync(path.join(src, 's1.' + e)))), 'byte-identical')
const bad = got.map((b) => Buffer.from(b)); bad[5][0] ^= 1
ok(writeVisual(path.join(work, 'x'), 's1', vis.meta, bad) === 'mismatch', 'tampered block refused')
await A.close(); await B.close(); await testnet.destroy()
fs.rmSync(work, { recursive: true, force: true })
process.exit()
