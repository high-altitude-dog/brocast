// Laptop test of engine/visuals.js (critical path gap 1), local testnet: no public DHT, no phones, no sound.
// A station publishes a synthetic visual (<id>.viz + <id>.nodes, and one with <id>.ft.nodes too) as one core of 64 KB blocks; B fetches the blocks by key,
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
fs.writeFileSync(path.join(src, 's1.ft.nodes'), crypto.randomBytes(350_003)) // s1 has the optional htdemucs_ft stems, s2 does not
fs.writeFileSync(path.join(src, 's2.viz'), crypto.randomBytes(500_000))
fs.writeFileSync(path.join(src, 's2.nodes'), crypto.randomBytes(100_007))
const ok = (c, w) => { console.log((c ? 'PASS ' : 'FAIL ') + w); if (!c) process.exitCode = 1 }

const testnet = await createTestnet(4)
const node = (n) => new Mesh({ dir: path.join(work, n), seed: crypto.randomBytes(32), bootstrap: testnet.bootstrap })
const topic = crypto.randomBytes(32)
const A = node('a'); const B = node('b')
A.swarm.join(topic); await A.swarm.flush()
for (const [id, exts, nblocks] of [['s1', ['viz', 'nodes', 'ft.nodes'], 36], ['s2', ['viz', 'nodes'], 10]]) {
  const vis = readVisual(src, id)
  ok(vis && vis.blocks.length === nblocks, id + ' readVisual: ' + vis?.blocks.length + ' blocks')
  ok(Object.keys(vis.meta).join() === exts.join(), id + ' catalog parts: ' + Object.keys(vis.meta).join('+'))
  const core = A.store.get({ name: 'vis/' + id }); await core.ready()
  await core.append(vis.blocks)
  await A.swarm.flush()
  const bc = B.store.get({ key: core.key }); await bc.ready()
  B.swarm.join(topic); await B.swarm.flush()
  const t = Date.now()
  const got = []
  for (let i = 0; i < core.length; i++) got.push(await bc.get(i, { timeout: 20000 }))
  console.log(id + ' B fetched', got.length, 'blocks in', Date.now() - t, 'ms; wire rx', (B.wire().rx / 1024).toFixed(0), 'KB')
  ok(!haveVisual(dst, id, vis.meta), id + ' B does not have it yet')
  ok(writeVisual(dst, id, vis.meta, got) === 'built', id + ' writeVisual built the files')
  ok(haveVisual(dst, id, vis.meta), id + ' files match the catalog hashes')
  ok(exts.every((e) => fs.readFileSync(path.join(dst, id + '.' + e)).equals(fs.readFileSync(path.join(src, id + '.' + e)))), id + ' byte-identical')
  ok(!fs.readdirSync(dst).some((f) => f.endsWith('.tmp')) && fs.existsSync(path.join(dst, id + '.ft.nodes')) === exts.includes('ft.nodes'), id + ' no .tmp left; ft.nodes written only if listed')
  const bad = got.map((b) => Buffer.from(b)); bad[5][0] ^= 1
  ok(writeVisual(path.join(work, 'x' + id), id, vis.meta, bad) === 'mismatch', id + ' tampered block refused')
  if (id === 's1') {
    const bf = got.map((b) => Buffer.from(b)); bf[bf.length - 1][0] ^= 1 // a flipped byte inside the ft part alone
    ok(writeVisual(path.join(work, 'xft'), id, vis.meta, bf) === 'mismatch', 'tampered ft.nodes refused')
    ok(writeVisual(path.join(work, 'xl'), id, vis.meta, got.slice(1)) === 'length', 'missing block refused')
    const old = { viz: vis.meta.viz, nodes: vis.meta.nodes } // an old catalog item: no ft entry, so only viz+nodes are checked
    fs.mkdirSync(path.join(work, 'xo'))
    ok(haveVisual(dst, id, old) && writeVisual(path.join(work, 'xo'), id, old, [Buffer.concat(got).subarray(0, old.viz.bytes + old.nodes.bytes)]) === 'built', 'old catalog without ft still verifies and writes viz+nodes')
    ok(!fs.existsSync(path.join(work, 'xo', id + '.ft.nodes')), 'old catalog writes no ft.nodes')
  }
}
await A.close(); await B.close(); await testnet.destroy()
fs.rmSync(work, { recursive: true, force: true })
process.exit()
