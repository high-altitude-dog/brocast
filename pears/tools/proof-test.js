// Laptop test of proofs.js (work queue item 2, 15 Nov), on a local testnet: nothing reaches the public DHT, no sound.
// A is the station with one set. B has the same file "from HTTP" and an empty core: it fetches proofs only and imports its
// own blocks. Then A stops and C fetches the whole set from B. D tries a tampered file and must be refused.
// Usage: node tools/proof-test.js <set.opus> [block-seconds]
import createTestnet from 'hyperdht/testnet.js'
import fs from 'fs'
import os from 'os'
import path from 'path'
import crypto from 'crypto'
import { Mesh, blocks } from '../engine/index.js'

const file = process.argv[2]
const bs = Number(process.argv[3] || 4)
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'prooftest-'))
const testnet = await createTestnet(4)
const node = (name) => new Mesh({ dir: path.join(work, name), seed: crypto.randomBytes(32), bootstrap: testnet.bootstrap })
const topic = crypto.randomBytes(32)
const join = async (m) => { m.swarm.join(topic); await m.swarm.flush() }
const ok = (cond, what) => { console.log((cond ? 'PASS ' : 'FAIL ') + what); if (!cond) process.exitCode = 1 }

const mine = blocks(file, bs)
const audio = mine.reduce((a, b) => a + b.length, 0)
const A = node('a')
const set = A.store.get({ name: 'set' })
await set.ready()
for (let i = 0; i < mine.length; i += 256) await set.append(mine.slice(i, i + 256))
A.proofs.offer(set)
await join(A)

const B = node('b')
const bCore = B.store.get({ key: set.key })
await bCore.ready()
B.proofs.offer(bCore)
await join(B)
while (!B.proofs.channels.size) await new Promise((r) => setTimeout(r, 100))
let t = Date.now()
const r = await B.proofs.import(bCore, mine)
const wire = B.wire()
console.log('B import', JSON.stringify(r), Date.now() - t, 'ms;', mine.length, 'blocks,', (audio / 1048576).toFixed(1), 'MB audio; B wire rx', (wire.rx / 1024).toFixed(0), 'KB')
ok(r.ok && bCore.contiguousLength === mine.length, 'B holds every block after proofs only')
ok(wire.rx < audio * 0.1, 'B received under 10% of the audio size')

await A.swarm.destroy()
const C = node('c')
const cCore = C.store.get({ key: set.key })
await cCore.ready()
await join(C)
t = Date.now()
cCore.download({ start: 0, end: mine.length })
let same = true
for (let i = 0; i < mine.length; i++) { const b = await cCore.get(i, { timeout: 20000 }); if (!b.equals(mine[i])) same = false }
console.log('C fetched from B in', Date.now() - t, 'ms')
ok(same, 'C got the whole set from B, byte-identical, with the station off')

const D = node('d')
const dCore = D.store.get({ key: set.key })
await dCore.ready()
await join(D)
while (!D.proofs.channels.size) await new Promise((r) => setTimeout(r, 100))
const bad = mine.map((b) => b)
bad[3] = Buffer.from(bad[3]); bad[3][10] ^= 1
const rd = await D.proofs.import(dCore, bad)
ok(!rd.ok && dCore.contiguousLength === 3, 'D with a tampered block 3 is refused at block 3 (' + rd.why + ')')

for (const m of [B, C, D]) await m.swarm.destroy()
await testnet.destroy()
fs.rmSync(work, { recursive: true, force: true })
process.exit()
