// Duplicate-block test on the laptop: a local HyperDHT testnet (nothing reaches the public DHT), the step 0 station,
// and two listeners that fetch exactly as the phone worklet does (about 4 blocks in flight, core.get per block).
// Usage: node tools/dup.js [--hedge default|off|delayed] [--after-ms 1000] [--stall-ms 0] [--runs 1]
// --stall-ms N freezes listener A (SIGSTOP) 6 s into the run for N ms, to check B still gets every block from the station.
import { spawn, fork } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import b4a from 'b4a'
import crypto from 'hypercore-crypto'
import { Mesh, hex } from '../engine/mesh.js'
import { hedge } from '../engine/hedge.js'
import { topic, decodeRecord } from '../engine/index.js'
import { makeLibrary } from './library.js'

const arg = (k, d) => { const i = process.argv.indexOf('--' + k); return i > 0 ? process.argv[i + 1] : d }
const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..')
const work = path.join(root, 'work')

if (arg('role') === 'listener') await listener()
else await main()

async function main () {
  const { default: createTestnet } = await import('hyperdht/testnet.js')
  const mode = arg('hedge', 'delayed'); const afterMs = arg('after-ms', '1000'); const stallMs = Number(arg('stall-ms', 0))
  const library = path.join(work, 'audio'); makeLibrary(library)
  const testnet = await createTestnet(3)
  const env = { ...process.env, BOOTSTRAP: JSON.stringify(testnet.bootstrap) }
  for (let run = 1; run <= Number(arg('runs', 1)); run++) {
    const sdir = path.join(work, 'dup-station')
    const station = spawn('node', [path.join(root, 'tools/station.js'), '--dir', sdir, '--seed-file', path.join(work, 'dup-station-seed'),
      '--library', library, '--block-seconds', '4', '--minutes', '10'], { env, stdio: ['ignore', 'pipe', 'inherit'] })
    let out = ''
    const key = await new Promise((resolve) => station.stdout.on('data', (d) => { out += d; const m = out.match(/READY \w+ (\w+)/); if (m) resolve(m[1]) }))
    const kids = ['a', 'b'].map((n) => {
      const dir = path.join(work, 'dup-' + n); fs.rmSync(dir, { recursive: true, force: true })
      return fork(new URL(import.meta.url).pathname, ['--role', 'listener', '--dir', dir, '--station', key, '--hedge', mode, '--after-ms', afterMs], { env })
    })
    if (stallMs) setTimeout(() => { kids[0].kill('SIGSTOP'); setTimeout(() => kids[0].kill('SIGCONT'), stallMs) }, 6000)
    const res = await Promise.all(kids.map((k) => new Promise((resolve) => k.on('message', resolve))))
    for (const k of kids) k.kill('SIGTERM')
    station.kill('SIGTERM'); await new Promise((resolve) => station.on('exit', resolve))
    for (const [i, r] of res.entries()) {
      console.log(`run ${run} ${mode}${mode === 'delayed' ? ' ' + afterMs + 'ms' : ''}${stallMs ? ' stall ' + stallMs : ''} ${'AB'[i]}: all ${r.allMs} ms, per block max ${r.maxMs} ms;` +
        ` data msgs ${r.dataRx}/${r.held} (${(((r.dataRx - r.held) / r.held) * 100).toFixed(1)}% extra); requests ${r.requestTx}, cancels ${r.cancelTx}, hotswaps ${r.hotswaps};` +
        ` wire overhead ${((r.rx / r.audio - 1) * 100).toFixed(1)}%; from other listener ${((r.fromOther / r.audio) * 100).toFixed(0)}%`)
    }
  }
  await testnet.destroy()
  process.exit(0)
}

// One listener: the phone worklet's fetch loop (worklet.js Run.main and fetchMore), without the builder or RPC.
async function listener () {
  const mesh = new Mesh({ dir: arg('dir'), seed: crypto.randomBytes(32), bootstrap: JSON.parse(process.env.BOOTSTRAP), maxPeers: 64 })
  const t0 = Date.now()
  const stationKey = b4a.from(arg('station'), 'hex')
  let r = null
  while (!r) r = await mesh.swarm.dht.mutableGet(stationKey, { refresh: () => true }).catch(() => null)
  const catalog = mesh.store.get({ key: b4a.from(decodeRecord(r.value).catalog, 'hex') })
  await catalog.ready()
  const found = mesh.store.findingPeers()
  mesh.swarm.join(topic(catalog.key), { server: true, client: true })
  mesh.swarm.flush().then(found)
  await catalog.update({ wait: true })
  const ed = JSON.parse(b4a.toString(await catalog.get(catalog.length - 1)))
  const seeds = new Set(ed.seeds)
  let fromOther = 0
  const sets = []
  for (const it of ed.items) {
    const core = mesh.store.get({ key: b4a.from(it.core, 'hex') })
    await core.ready()
    hedge(core, { mode: arg('hedge', 'delayed'), afterMs: Number(arg('after-ms', 1000)) })
    core.on('download', (i, bytes, from) => { if (!seeds.has(hex(from.remotePublicKey))) fromOther += bytes })
    sets.push({ core, blocks: it.blocks })
  }
  const total = sets.reduce((a, s) => a + s.blocks, 0)
  const window = Math.max(2, Math.round(16 / ed.block_seconds))
  const held = new Set(); const inflight = new Set()
  let audio = 0; let maxMs = 0
  await new Promise((resolve) => {
    const more = () => {
      for (const [n, s] of sets.entries()) {
        for (let i = 0; i < s.blocks && inflight.size < window; i++) {
          const k = n + ':' + i
          if (held.has(k) || inflight.has(k)) continue
          inflight.add(k)
          const t = Date.now()
          s.core.get(i, { timeout: 20000 }).then((buf) => {
            maxMs = Math.max(maxMs, Date.now() - t); audio += buf.byteLength; held.add(k)
            if (held.size === total) resolve()
          }, () => {}).finally(() => inflight.delete(k))
        }
      }
    }
    setInterval(more, 50); more()
  })
  const allMs = Date.now() - t0
  const st = { dataRx: 0, requestTx: 0, cancelTx: 0, hotswaps: 0 }
  for (const s of sets) { const x = s.core.replicator.stats; st.dataRx += x.wireData.rx; st.requestTx += x.wireRequest.tx; st.cancelTx += x.wireCancel.tx; st.hotswaps += x.hotswaps }
  // Let in-flight duplicates land before counting, as on the phones (their end line is after sharing).
  await new Promise((resolve) => setTimeout(resolve, 3000))
  const st2 = sets.reduce((a, s) => a + s.core.replicator.stats.wireData.rx, 0)
  process.send({ allMs, maxMs, held: held.size, audio, fromOther, rx: mesh.wire().rx, ...st, dataRx: st2 })
  // Keep serving the other listener until the parent stops us.
}
