// Fetches one set from whoever holds it (station off: only listeners), checks it against a local copy, and says who sent it.
// Proof-import check (15 Nov): a phone that imported its HTTP sets must serve them. No sound.
// Usage: node tools/fetch-one.js --catalog HEX --set ID --file LOCAL.opus [--block-seconds 4] [--station HEX]
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import crypto from 'node:crypto'
import b4a from 'b4a'
import { Mesh, hex } from '../engine/mesh.js'
import { topic, blocks } from '../engine/index.js'

const arg = (k, d) => { const i = process.argv.indexOf('--' + k); return i > 0 ? process.argv[i + 1] : d }
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fetch-one-'))
const mesh = new Mesh({ dir, seed: crypto.randomBytes(32) })
const catalog = mesh.store.get({ key: b4a.from(arg('catalog'), 'hex') })
await catalog.ready()
mesh.swarm.join(topic(catalog.key), { server: true, client: true }) // phones that don't announce still find us
await mesh.swarm.flush()
console.error('joined'); mesh.swarm.on('connection', (c) => console.error('conn', hex(c.remotePublicKey).slice(0, 8)))
await catalog.update({ wait: true })
while (catalog.length === 0) { await new Promise((resolve) => setTimeout(resolve, 1000)); await catalog.update({ wait: true }) }
const ed = JSON.parse(b4a.toString(await catalog.get(catalog.length - 1)))
const it = ed.items.find((x) => x.id === arg('set'))
const core = mesh.store.get({ key: b4a.from(it.core, 'hex') })
await core.ready()
const from = new Map()
core.on('download', (i, bytes, peer) => { const k = hex(peer.remotePublicKey).slice(0, 8); from.set(k, (from.get(k) || 0) + bytes) })
const want = blocks(arg('file'), Number(arg('block-seconds', 4)))
const t = Date.now()
let same = want.length === it.blocks
for (let i = 0; i < it.blocks; i++) { const b = await core.get(i, { timeout: 30000 }); if (!b4a.equals(b, want[i])) same = false }
console.log(JSON.stringify({ set: it.id, blocks: it.blocks, ms: Date.now() - t, identical: same, bytesFrom: Object.fromEntries(from), station: (arg('station') || '').slice(0, 8) }))
await mesh.swarm.destroy()
fs.rmSync(dir, { recursive: true, force: true })
process.exit()
