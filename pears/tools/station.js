// The station's seed server for step 0: puts each Opus set in its own core as blocks of N seconds, publishes the
// catalog, and serves. Prints `READY <catalog key> <station key>`, then a JSON summary on SIGTERM.
// Usage: node tools/station.js --dir D --seed-file F --library DIR --block-seconds N  (BOOTSTRAP env: JSON host/port list)
// With --library-json FILE (the server's library.json for those sets), the catalog carries it, so the app's timeline comes over the mesh too.
// Each set's visual (<id>.viz + <id>.nodes + optional <id>.ft.nodes, from --visuals DIR, default the library folder) goes in the catalog too (engine/visuals.js).
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'hypercore-crypto'
import { Mesh, hex } from '../engine/mesh.js'
import { blocks, FORMAT } from '../engine/blocks.js'
import { buildOgg, serialOf, blake2b } from '../engine/builder.js'
import { topic, encodeRecord } from '../engine/index.js'
import { EgressGuard, defaultAlert } from '../seeder/egress-guard.js'
import { readVisual } from '../engine/visuals.js'
import { readPhotos } from '../engine/photos.js'

const arg = (k, d) => { const i = process.argv.indexOf('--' + k); return i > 0 ? process.argv[i + 1] : d }
const dir = arg('dir')
const seedFile = arg('seed-file')
const library = arg('library')
const visuals = arg('visuals', library)
// With --artists DIR (<aid>-<crop>-<v>.jpg, the app's names), the photos of the library json's artists go in the catalog too (engine/photos.js).
const artistsDir = arg('artists')
const blockSeconds = Number(arg('block-seconds', 4)) // 4 s is the step 0 recommendation
const libraryJson = arg('library-json') ? JSON.parse(fs.readFileSync(arg('library-json'), 'utf8')) : null
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
  mesh.proofs.offer(core) // phones holding the set from HTTP prove their copy against it (proofs.js)
  const all = []
  for (let i = 0; i < core.length; i++) all.push(await core.get(i))
  const b2 = blake2b(buildOgg(serialOf(core.key), all)) // the hash of the file every phone will build
  const item = { id, title: id, artist: '', blocks: core.length, seconds: core.length * blockSeconds, core: hex(core.key), blake2b: b2 }
  // Its visual, if rendered: a core named by the files' hashes, so a new render is a new core and an old phone's copy is never mixed in.
  const vis = readVisual(visuals, id)
  if (vis) {
    const vc = store.get({ name: 'vis/' + id + '/' + vis.meta.viz.blake2b.slice(0, 16) + vis.meta.nodes.blake2b.slice(0, 16) + (vis.meta['ft.nodes']?.blake2b.slice(0, 16) || '') })
    await vc.ready()
    if (vc.length === 0) await vc.append(vis.blocks)
    item.visual = { core: hex(vc.key), blocks: vc.length, ...vis.meta }
  }
  items.push(item)
}

// Artist photos: one core each, named by the hash; absent flag or no library json = none.
const photos = []
for (const p of artistsDir && libraryJson ? readPhotos(artistsDir, libraryJson.artists) : []) {
  const pc = store.get({ name: 'photo/' + p.blake2b.slice(0, 32) })
  await pc.ready()
  if (pc.length === 0) await pc.append(p.buf)
  photos.push({ f: p.f, bytes: p.bytes, blake2b: p.blake2b, core: hex(pc.key) })
}

const catalog = store.get({ name: 'catalog' })
await catalog.ready()
// A new edition whenever the sets or the library list change; phones always read the newest.
const last = catalog.length ? JSON.parse(await catalog.get(catalog.length - 1)) : null
const same = last && JSON.stringify(last.items) === JSON.stringify(items) && JSON.stringify(last.library ?? null) === JSON.stringify(libraryJson) && JSON.stringify(last.photos ?? []) === JSON.stringify(photos)
if (!same) {
  await catalog.append(Buffer.from(JSON.stringify({
    edition: catalog.length + 1, start_ns: 0, block_format: FORMAT, block_seconds: blockSeconds, seeds: [hex(mesh.keyPair.publicKey)], items,
    ...(libraryJson ? { library: libraryJson } : {}), ...(photos.length ? { photos } : {})
  })))
}

const uploads = new Map()
const served = [...items.flatMap((it) => [it.core, ...(it.visual ? [it.visual.core] : [])]), ...photos.map((p) => p.core)]
console.log('PHOTOS', photos.length)
console.log('VISUALS', items.filter((it) => it.visual).length + ' of ' + items.length + ' sets')
for (const c of served) {
  store.get({ key: Buffer.from(c, 'hex') }).on('upload', (index, bytes, to) => {
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
  for (const c of served) store.get({ key: Buffer.from(c, 'hex') }).on('upload', () => guard.check())
}
console.log('READY', hex(catalog.key), hex(mesh.keyPair.publicKey))
// The station QR (critical path gap 3): brocast://station/<key>, plus ?relay=<key> when --relay KEY is given (gap 5; the relay key stays out of the repo and the APK).
const stationUrl = 'brocast://station/' + hex(mesh.keyPair.publicKey) + (arg('relay') ? '?relay=' + arg('relay') : '')
console.log('STATION-URL', stationUrl)
{
  const QR = (await import('qrcode')).default
  console.log(await QR.toString(stationUrl, { type: 'terminal', small: true }))
  const png = path.join(dir, 'station-qr.png')
  await QR.toFile(png, stationUrl, { width: 640, margin: 2 })
  console.log('QR-PNG', png)
}
console.log('NAT', JSON.stringify(mesh.nat()))
// Auto-stop (user, 7 Nov): every seed server stops itself after --minutes, so none is left running. The laptop station
// may run up to an hour each time it's started (user, 19 Nov), long enough to deliver a whole library; 60 by default.
const minutes = Math.min(60, Number(arg('minutes', 60)))
setTimeout(() => { console.log(`AUTOSTOP after ${minutes} min`); process.kill(process.pid, 'SIGTERM') }, minutes * 60e3)

process.on('SIGTERM', async () => {
  clearInterval(gossip)
  const summary = { nat: mesh.nat(), uploads: Object.fromEntries(uploads), uploaded: [...uploads.values()].reduce((a, b) => a + b, 0), wire: mesh.wire(), sketch: mesh.sketch.estimate() }
  console.log('SUMMARY', JSON.stringify(summary))
  await mesh.close()
  process.exit(0)
})
