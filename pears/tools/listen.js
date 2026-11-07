// A listener for step 0: fetches the library by the catalog key, with a simulated playhead and the profile's watermarks.
// Nothing is decoded or played. Writes one JSON line per event to --out.
// Modes: share (announce and serve), receive (take from anyone, keep nothing others can ask for), privacy (station only).
// Usage: node tools/listen.js --dir D --catalog HEX --station HEX --out F [--mode share] [--profile balanced]
//        [--speed 1] [--wm-scale 1] [--wall 60] [--until all|play|wall]
import fs from 'node:fs'
import b4a from 'b4a'
import crypto from 'hypercore-crypto'
import { Mesh, hex } from '../engine/mesh.js'
import { profile, sharing } from '../engine/policy.js'
import { SetBuilder, serialOf } from '../engine/builder.js'
import { topic, decodeRecord } from '../engine/index.js'

const arg = (k, d) => { const i = process.argv.indexOf('--' + k); return i > 0 ? process.argv[i + 1] : d }
const mode = arg('mode', 'share')
const p = profile(arg('profile', 'balanced'))
const speed = Number(arg('speed', 1))
const wmScale = Number(arg('wm-scale', 1))
const wall = Number(arg('wall', 60)) * 1000
const until = arg('until', 'wall')
const stationKey = b4a.from(arg('station'), 'hex')
const out = fs.openSync(arg('out'), 'w')
let done = false
process.on('unhandledRejection', (e) => { if (!done) throw e }) // reads cut off by shutdown are expected
const t0 = performance.now()
const now = () => Math.round(performance.now() - t0)
const log = (o) => fs.writeSync(out, JSON.stringify({ t: now(), ...o }) + '\n')
const cpu0 = process.cpuUsage()

const phone = { privacy: mode === 'privacy', metered: false, charging: true, battery: 100, playing: true, dataSaver: false, sharedMobileMB: 0 }
const share = sharing(p, phone).on && mode === 'share'
const mesh = new Mesh({
  dir: arg('dir'),
  seed: crypto.randomBytes(32),
  bootstrap: process.env.BOOTSTRAP ? JSON.parse(process.env.BOOTSTRAP) : undefined,
  maxPeers: p.maxPeers,
  relaySketch: mode !== 'privacy'
})
const me = hex(mesh.keyPair.publicKey).slice(0, 8)
log({ ev: 'start', me, mode, profile: p.name, share, speed, wmScale })

// The station's pointer record. Reading it with refresh re-puts it, signature and all, on the nodes closest to it:
// every reader keeps it alive, and none needs the station's secret key.
async function readRecord () {
  const r = await mesh.swarm.dht.mutableGet(stationKey, { refresh: () => true })
  if (r) log({ ev: 'record', seq: r.seq })
  return r
}
let catalogHex = arg('catalog')
if (mode !== 'privacy') {
  const r = await readRecord()
  if (!catalogHex) catalogHex = decodeRecord(r.value).catalog // found by station key alone
}
const refresher = mode === 'share' ? setInterval(() => readRecord().catch(() => log({ ev: 'record', err: true })), 10000) : null
const catalog = mesh.store.get({ key: b4a.from(catalogHex, 'hex') })
await catalog.ready()
const found = mesh.store.findingPeers()
if (mode === 'privacy') {
  mesh.allow = (k) => b4a.equals(k, stationKey)
  mesh.swarm.joinPeer(stationKey)
} else {
  mesh.swarm.join(topic(catalog.key), { server: share && p.announce, client: true })
}
mesh.swarm.flush().then(found)
await catalog.update({ wait: true })
const ed = JSON.parse(b4a.toString(await catalog.get(catalog.length - 1)))
const seeds = new Set(ed.seeds)
const bs = ed.block_seconds
log({ ev: 'catalog', items: ed.items.length, blockSeconds: bs })

if (mode !== 'privacy') { mesh.sketch.add(mesh.keyPair.publicKey, 0); mesh.gossip() }
const gossip = setInterval(() => { mesh.gossip(); log({ ev: 'sketch', estimate: mesh.sketch.estimate() }) }, 2000)

// The timeline: every set in turn, looping, in blocks of bs seconds.
const sets = ed.items.map((it, n) => ({ n, it, blocks: it.blocks, core: mesh.store.get({ key: b4a.from(it.core, 'hex') }) }))
const total = sets.reduce((a, s) => a + s.blocks, 0)
const at = (g) => { g = ((g % total) + total) % total; for (const s of sets) { if (g < s.blocks) return [s, g]; g -= s.blocks } }
const held = new Set()
const cache = new Map() // receive mode keeps its copies here, outside the core, so peers can't ask for them
const inflight = new Map()
// Receive mode: blocks are copied out, then cleared from the core in batches, so peers find nothing to ask for.
const toClear = []
let clearing = false
const clearer = setInterval(async () => {
  if (clearing || !toClear.length) return
  clearing = true
  for (const [s, i] of toClear.splice(0)) await s.core.clear(i)
  clearing = false
}, 1000)
const lateBlocks = new Set()
let audio = 0

const music = arg('dir') + '.music'
for (const s of sets) {
  await s.core.ready()
  s.builder = new SetBuilder({ music, id: s.it.id, blocks: s.blocks, serial: serialOf(s.core.key), sha: s.it.blake2b })
  s.core.on('download', (index, bytes, from) => {
    const k = hex(from.remotePublicKey)
    log({ ev: 'block', set: s.n, index, bytes, peer: k.slice(0, 8), station: seeds.has(k) })
  })
  s.core.on('upload', (index, bytes, to) => log({ ev: 'upload', set: s.n, index, bytes, peer: hex(to.remotePublicKey).slice(0, 8) }))
}

function fetch (g) {
  const [s, i] = at(g)
  const key = s.n + ':' + i
  if (held.has(key) || inflight.has(key)) return
  const started = now()
  inflight.set(key, started)
  s.core.get(i, { timeout: 20000 }).then(async (buf) => {
    audio += buf.byteLength
    held.add(key)
    log({ ev: 'got', set: s.n, index: i, ms: now() - started })
    build(s)
    if (mode === 'receive') { cache.set(key, buf); toClear.push([s, i]) }
  }, (err) => log({ ev: 'fail', set: s.n, index: i, err: err.code || err.message })).finally(() => inflight.delete(key))
}

// Grows the set's file in music/.building in order, and moves it into music/ once whole and checked.
const local = (s) => async (i) => {
  const k = s.n + ':' + i
  if (!held.has(k)) return null
  return mode === 'receive' ? cache.get(k) : s.core.get(i, { wait: false })
}
async function build (s, last = false) {
  if (done && !last) return
  const r = await s.builder.pump(local(s))
  if (!r) return
  log({ ev: r, set: s.n })
  if (mode === 'receive') for (let i = 0; i < s.blocks; i++) cache.delete(s.n + ':' + i) // the file is the copy now
}

// Contiguous blocks held from the playhead, in seconds of audio.
function ahead (g) {
  let n = 0
  while (n < total) { const [s, i] = at(g + n); if (!held.has(s.n + ':' + i)) break; n++ }
  return n * bs
}

const high = Math.min(p.high * wmScale, total * bs)
const low = Math.min(p.low * wmScale, high)
// In flight by bytes, not count: about 300 KB, so large blocks don't flood the link and get resent.
const WINDOW = Math.max(2, Math.round(16 / bs))
let burst = null
let bursts = 0

const tick = setInterval(() => {
  if (done) return
  const played = (now() / 1000) * speed
  const g = Math.floor(played / bs)
  const [ps, pi] = at(g)
  if (until === 'play' && played >= total * bs) return finish('played')
  if (!held.has(ps.n + ':' + pi) && !lateBlocks.has(g)) { lateBlocks.add(g); log({ ev: 'late', g }) }
  const a = ahead(g)
  if (!burst && a < low) { burst = { start: now(), ahead: a }; bursts++; log({ ev: 'burst', ahead: a }) }
  if (burst) {
    if (a >= high || held.size === total) { log({ ev: 'idle', ms: now() - burst.start }); burst = null } else {
      for (let n = Math.floor(a / bs); n * bs <= high && inflight.size < WINDOW; n++) fetch(g + n)
    }
  }
  if (until === 'all' && held.size === total) return finish('all')
  if (now() > wall) return finish('wall')
}, 50)

async function finish (why) {
  done = true
  clearInterval(tick)
  clearInterval(clearer)
  if (refresher) clearInterval(refresher)
  clearInterval(gossip)
  for (const s of sets) { while (s.builder.busy) await new Promise((r) => setTimeout(r, 20)); await build(s, true) }
  const cpu = process.cpuUsage(cpu0)
  const conns = [...mesh.swarm.connections].map((c) => hex(c.remotePublicKey).slice(0, 8))
  log({
    ev: 'end', why, held: held.size, total, audio, wire: mesh.wire(), bursts, late: lateBlocks.size, conns,
    found: mesh.swarm.peers.size, rssMB: Math.round(process.memoryUsage().rss / 1048576), cpuMs: Math.round((cpu.user + cpu.system) / 1000),
    sketch: mesh.sketch.estimate(), built: sets.filter((s) => s.builder.done).length,
    music: fs.existsSync(music) ? fs.readdirSync(music) : [], building: fs.existsSync(music + '/.building') ? fs.readdirSync(music + '/.building') : []
  })
  await mesh.close()
  process.exit(0)
}
