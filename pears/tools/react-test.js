// Silent reactions on a local testnet (hand-off 53): three nodes in a line A–B–C; A's reaction reaches C through B, signed, once each;
// a second within 3 s is refused; a blocked sender is dropped.
import os from 'node:os'
import path from 'node:path'
import fs from 'node:fs'
import crypto from 'hypercore-crypto'
import b4a from 'b4a'
import { Mesh, hex } from '../engine/mesh.js'

const { default: createTestnet } = await import('hyperdht/testnet.js')
const testnet = await createTestnet(3)
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'react-'))
const node = (n) => new Mesh({ dir: path.join(tmp, n), seed: crypto.randomBytes(32), bootstrap: testnet.bootstrap })
const [a, b, c] = ['a', 'b', 'c'].map(node)
const got = { a: [], b: [], c: [] }
for (const [n, m] of [['a', a], ['b', b], ['c', c]]) m.onreaction = (k, e, mine) => got[n].push({ k: k.slice(0, 4), e, mine })
// A line: A only knows B, C only knows B (each joins a pair topic).
const ab = crypto.randomBytes(32); const bc = crypto.randomBytes(32)
const both = { server: true, client: true }
a.swarm.join(ab, both); b.swarm.join(ab, both); b.swarm.join(bc, both); c.swarm.join(bc, both)
await Promise.all([a.swarm.flush(), b.swarm.flush(), c.swarm.flush()])
const wait = (ms) => new Promise((r) => setTimeout(r, ms))
for (let i = 0; i < 300 && (a.reactChannels.size < 1 || c.reactChannels.size < 1 || b.reactChannels.size < 2); i++) await wait(100)
const ok = (cond, what) => { console.log((cond ? '  ok   ' : '  FAIL ') + what); if (!cond) process.exitCode = 1 }
ok(a.react(2), 'A sends 🙌')
await wait(500)
ok(got.b.length === 1 && got.b[0].e === 2, 'B gets it once')
ok(got.c.length === 1 && got.c[0].k === hex(a.keyPair.publicKey).slice(0, 4), 'C gets it through B, from A')
ok(!a.react(1), 'a second within 3 s is refused')
c.setBlocked([hex(a.keyPair.publicKey)])
await wait(3100)
ok(a.react(0), 'A sends again after 3 s')
await wait(500)
ok(got.b.length === 2, 'B gets the second')
ok(got.c.length === 1, 'C, which blocked A, drops it')
for (const m of [a, b, c]) await m.close()
await testnet.destroy()
console.log(process.exitCode ? 'FAILED' : 'ALL PASSED')
