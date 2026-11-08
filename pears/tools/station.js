// The station's seed server for step 0: puts each Opus set in its own core as blocks of N seconds, publishes the
// catalog, and serves. Prints `READY <catalog key> <station key>`, then a JSON summary on SIGTERM.
// Usage: node tools/station.js --dir D --seed-file F --library DIR --block-seconds N  (BOOTSTRAP env: JSON host/port list)
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'hypercore-crypto'
import { Mesh, hex } from '../engine/mesh.js'
import { blocks, FORMAT } from '../engine/blocks.js'
import { buildOgg, serialOf, blake2b } from '../engine/builder.js'
import { topic, encodeRecord } from '../engine/index.js'
import { EgressGuard, defaultAlert } from '../seeder/egress-guard.js'

const arg = (k, d) => { const i = process.argv.indexOf('--' + k); return i > 0 ? process.argv[i + 1] : d }
const dir = arg('dir')
const seedFile = arg('seed-file')
const library = arg('library')
const blockSeconds = Number(arg('block-seconds', 4)) // 4 s is the step 0 recommendation
const bootstrap = process.env.BOOTSTRAP ? JSON.parse(process.env.BOOTSTRAP) : undefined
// A seed on egress-billed cloud (cost class 3) runs with a cap: 1 GB a month for now (user, 7 Nov).
const capBytes = arg('cloud-cap-bytes') ? Number(arg('cloud-cap-bytes')) : null

if (!fs.existsSync(seedFile)) fs.writeFileSync(seedFile, crypto.randomBytes(32), { mode: 0o600, flag: 'wx' })
// Step 2: every connection to the station, with its path, printed as `CONN {json}`.
const mesh = new Mesh({ dir, seed: fs.readFileSync(seedFile), bootstrap, onconn: (r) => console.log('CONN', JSON.stringify(r)) })
const store = mesh.store.namespace('blocks-' + blockSeconds)

const items = []
for (const name of fs.readdirSync(library).filter((f) => f.endsWith('.opus')).sort()) {
  const id = name.replace(/\.opus$/, '')
  const core = store.get({ name: 'set/' + id })
  await core.ready()
  if (core.length === 0) {
    const all = blocks(path.join(library, name), blockSeconds)
    for (let i = 0; i < all.length; i += 256) await core.append(all.slice(i, i + 256))
  }
  const all = []
  for (let i = 0; i < core.length; i++) all.push(await core.get(i))
  const b2 = blake2b(buildOgg(serialOf(core.key), all)) // the hash of the file every phone will build
  items.push({ id, title: id, artist: '', blocks: core.length, seconds: core.length * blockSeconds, core: hex(core.key), blake2b: b2 })
}

const catalog = store.get({ name: 'catalog' })
await catalog.ready()
if (catalog.length === 0) {
  await catalog.append(Buffer.from(JSON.stringify({
    edition: 1, start_ns: 0, block_format: FORMAT, block_seconds: blockSeconds, seeds: [hex(mesh.keyPair.publicKey)], items
  })))
}

const uploads = new Map()
for (const it of items) {
  store.get({ key: Buffer.from(it.core, 'hex') }).on('upload', (index, bytes, to) => {
    const k = hex(to.remotePublicKey).slice(0, 8)
    uploads.set(k, (uploads.get(k) || 0) + bytes)
  })
}

mesh.swarm.join(topic(catalog.key), { server: true, client: false })
await mesh.swarm.flush()
// The pointer record: phones that know only the station key find the catalog through it, even with the station gone.
await mesh.swarm.dht.mutablePut(mesh.keyPair, encodeRecord({ catalog: hex(catalog.key), length: catalog.length, shards: 1 }), { seq: catalog.length })
const gossip = setInterval(() => mesh.gossip(), 2000)
if (capBytes) {
  const guard = new EgressGuard({
    capBytes,
    stateFile: path.join(dir, 'egress.json'),
    sent: () => mesh.wire().tx,
    alert: defaultAlert({ alertFile: path.join(dir, 'egress-alerts.log'), webhook: process.env.EGRESS_WEBHOOK }),
    shutdown: () => { // synchronous first: cut every connection now, then close the rest. Exit 0: nothing to retry.
      for (const conn of mesh.swarm.connections) conn.destroy()
      console.log('SHUTDOWN egress cap')
      mesh.close().finally(() => process.exit(0))
    }
  })
  try { guard.start() } catch { await mesh.close(); process.exit(0) }
  for (const it of items) store.get({ key: Buffer.from(it.core, 'hex') }).on('upload', () => guard.check())
}
console.log('READY', hex(catalog.key), hex(mesh.keyPair.publicKey))
console.log('NAT', JSON.stringify(mesh.nat()))
// Auto-stop (user, 7 Nov): every seed server stops itself after --minutes, 10 by default, so none is left running.
const minutes = Number(arg('minutes', 10))
setTimeout(() => { console.log(`AUTOSTOP after ${minutes} min`); process.kill(process.pid, 'SIGTERM') }, minutes * 60e3)

process.on('SIGTERM', async () => {
  clearInterval(gossip)
  const summary = { nat: mesh.nat(), uploads: Object.fromEntries(uploads), uploaded: [...uploads.values()].reduce((a, b) => a + b, 0), wire: mesh.wire(), sketch: mesh.sketch.estimate() }
  console.log('SUMMARY', JSON.stringify(summary))
  await mesh.close()
  process.exit(0)
})
