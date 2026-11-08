// Step 1: the engine in a bare-kit worklet on the phone. The host (MainActivity.kt) speaks the commands of
// docs/engine-api.md that step 1 needs over bare-rpc on Bare.IPC: hello, start, subscribe, stop. Control, status and
// events are JSON. Given only the station key, it finds the catalog through the station's DHT record, fetches every
// block (each verified against the signed tree by Hypercore), and builds each set in music/ with its BLAKE2b check.
// Nothing is decoded or played. Step 1 only: `start` also takes `bootstrap` (the laptop's own DHT on the Wi-Fi) and
// `dirs.log`, where one JSON line per event is written in the format tools/report.js reads. Step 2: no `bootstrap` means
// the public HyperDHT; `lan: false` turns off the LAN shortcut, `relayThrough` names a blind relay, and every
// connection is logged as a `conn` line with its path (lan, direct, punched, relayed) or the error it failed with.
import './globals.js'
import RPC from 'bare-rpc'
import fs from 'bare-fs'
import b4a from 'b4a'
import { ENGINE, topic, decodeRecord } from '../../engine/index.js'
import { Mesh, hex } from '../../engine/mesh.js'
import { profile } from '../../engine/policy.js'
import { SetBuilder, serialOf } from '../../engine/builder.js'

// Command and event numbers on the wire (bare-rpc commands are integers). Names as in docs/engine-api.md.
const CMD = { hello: 1, start: 2, subscribe: 3, status: 4, stop: 5 }
const EV = { status: 100, arrivals: 101, built: 102, warning: 103, fatal: 104 }

const t0 = Date.now()
const now = () => Date.now() - t0
let rpc = null
const emit = (ev, body) => { try { rpc.event(ev).send(JSON.stringify(body)) } catch {} }
const fail = (code, err) => emit(EV.fatal, { code, text: String(err?.stack || err) })
Bare.on('uncaughtException', (e) => fail('UNCAUGHT', e)) // an uncaught error would abort the whole app
Bare.on('unhandledRejection', (e) => { if (!state.stopping) fail('UNHANDLED', e) })

const state = { started: false, stopping: false, hz: 1, arrivalsOn: true }
let run = null

rpc = new RPC(Bare.IPC, async (req) => {
  const body = req.data && req.data.byteLength ? JSON.parse(b4a.toString(req.data)) : {}
  const reply = (o) => req.reply(JSON.stringify(o))
  const refuse = (code, text) => { const e = new Error(text); e.code = code; throw e }
  switch (req.command) {
    case CMD.hello:
      if (!body.api?.includes(1)) refuse('API_MISMATCH', 'host offers ' + JSON.stringify(body.api))
      return reply({ api: 1, engine: { version: ENGINE.version, wire: ENGINE.wire, storage: ENGINE.storage, native: ENGINE.native } })
    case CMD.start:
      if (state.started) refuse('BAD_REQUEST', 'already started')
      state.started = true
      run = new Run(body)
      return reply({ publicKey: hex(run.mesh.keyPair.publicKey) })
    case CMD.subscribe:
      state.hz = body.hz ?? 1
      state.arrivalsOn = !!body.arrivals
      run?.restartStatus()
      return reply({})
    case CMD.status:
      if (!run) refuse('NOT_STARTED', 'start first')
      return reply(run.snapshot())
    case CMD.stop:
      if (run) await run.stop('stop')
      reply({})
      return
    default:
      refuse('BAD_REQUEST', 'unknown command ' + req.command)
  }
})

class Run {
  constructor ({ seed, stationKey, dirs, profile: prof = 'generous', bootstrap, lan = true, relayThrough, network }) {
    this.p = profile(prof)
    this.dirs = dirs
    this.out = dirs.log ? fs.openSync(dirs.log, 'w') : null
    this.stationKey = b4a.from(stationKey, 'hex')
    this.mesh = new Mesh({
      dir: dirs.store, seed: b4a.from(seed, 'hex'), bootstrap: bootstrap?.length ? bootstrap : undefined, maxPeers: this.p.maxPeers,
      lan, relayThrough: relayThrough ? b4a.from(relayThrough, 'hex') : null, onconn: (r) => this.log({ ev: 'conn', ...r })
    })
    this.held = new Set()
    this.inflight = new Map()
    this.audio = 0
    this.firstMs = null
    this.allMs = null
    this.arrivals = []
    this.byPeer = new Map()
    this.sets = []
    this.phase = 'finding catalog'
    this.log({ ev: 'start', me: hex(this.mesh.keyPair.publicKey).slice(0, 8), mode: 'share', profile: this.p.name, publicDht: !bootstrap?.length, lan, relay: !!relayThrough, network: network || null })
    this.restartStatus()
    this.arrivalTimer = setInterval(() => this.flushArrivals(), 250)
    this.main().catch((e) => { if (!state.stopping) fail('RUN', e) })
  }

  log (o) { if (this.out) fs.writeSync(this.out, JSON.stringify({ t: now(), ...o }) + '\n') }

  async main () {
    const dht = this.mesh.swarm.dht
    let r = null
    while (!r && !state.stopping) { // the station's pointer record: the station key is all the phone is given
      r = await dht.mutableGet(this.stationKey, { refresh: () => true }).catch(() => null)
      if (!r) await new Promise((resolve) => setTimeout(resolve, 1000))
    }
    if (state.stopping) return
    this.log({ ev: 'record', seq: r.seq, nat: this.mesh.nat() })
    const catalogKey = b4a.from(decodeRecord(r.value).catalog, 'hex')
    this.catalog = this.mesh.store.get({ key: catalogKey })
    await this.catalog.ready()
    const found = this.mesh.store.findingPeers()
    this.mesh.swarm.join(topic(this.catalog.key), { server: this.p.announce, client: true })
    this.mesh.swarm.flush().then(found)
    this.phase = 'reading catalog'
    await this.catalog.update({ wait: true })
    const ed = JSON.parse(b4a.toString(await this.catalog.get(this.catalog.length - 1)))
    this.edition = ed.edition
    this.seeds = new Set(ed.seeds)
    this.bs = ed.block_seconds
    this.log({ ev: 'catalog', items: ed.items.length, blockSeconds: this.bs })
    for (const [n, it] of ed.items.entries()) {
      const core = this.mesh.store.get({ key: b4a.from(it.core, 'hex') })
      await core.ready()
      const s = { n, it, blocks: it.blocks, core, got: 0 }
      s.builder = new SetBuilder({ music: this.dirs.music, id: it.id, blocks: it.blocks, serial: serialOf(core.key), sha: it.blake2b })
      core.on('download', (index, bytes, from) => {
        const k = hex(from.remotePublicKey)
        const station = this.seeds.has(k)
        this.log({ ev: 'block', set: n, index, bytes, peer: k.slice(0, 8), station })
        const p = this.byPeer.get(k) || { rx: 0, tx: 0, station }
        p.rx += bytes
        this.byPeer.set(k, p)
      })
      core.on('upload', (index, bytes, to) => {
        const k = hex(to.remotePublicKey)
        this.log({ ev: 'upload', set: n, index, bytes, peer: k.slice(0, 8) })
        const p = this.byPeer.get(k) || { rx: 0, tx: 0, station: this.seeds.has(k) }
        p.tx += bytes
        this.byPeer.set(k, p)
      })
      this.sets.push(s)
    }
    this.total = this.sets.reduce((a, s) => a + s.blocks, 0)
    this.phase = 'fetching'
    this.window = Math.max(2, Math.round(16 / this.bs)) // in flight by bytes, about 300 KB, as step 0
    this.pump = setInterval(() => this.fetchMore(), 50)
    this.fetchMore()
  }

  fetchMore () {
    if (state.stopping) return
    for (const s of this.sets) {
      for (let i = 0; i < s.blocks && this.inflight.size < this.window; i++) {
        const key = s.n + ':' + i
        if (this.held.has(key) || this.inflight.has(key)) continue
        this.fetch(s, i, key)
      }
      if (this.inflight.size >= this.window) return
    }
  }

  fetch (s, i, key) {
    const started = now()
    this.inflight.set(key, started)
    s.core.get(i, { timeout: 20000 }).then((buf) => {
      const ms = now() - started
      this.audio += buf.byteLength
      this.held.add(key)
      s.got++
      if (this.firstMs === null) this.firstMs = now()
      this.log({ ev: 'got', set: s.n, index: i, ms })
      if (state.arrivalsOn) this.arrivals.push({ g: key, bytes: buf.byteLength, ms })
      this.build(s)
      if (this.held.size === this.total && this.allMs === null) {
        this.allMs = now()
        this.phase = 'all blocks held, sharing'
        this.sendStatus()
      }
    }, (err) => this.log({ ev: 'fail', set: s.n, index: i, err: err.code || err.message }))
      .finally(() => this.inflight.delete(key))
  }

  async build (s) {
    const r = await s.builder.pump(async (i) => this.held.has(s.n + ':' + i) ? s.core.get(i, { wait: false }) : null)
    if (!r) return
    this.log({ ev: r, set: s.n })
    if (r === 'built') emit(EV.built, { setId: s.it.id, path: this.dirs.music + '/' + s.it.id + '.opus', blake2b: s.it.blake2b })
    else emit(EV.warning, { code: 'MISMATCH', text: s.it.id + ' failed its BLAKE2b check' })
    this.sendStatus()
  }

  flushArrivals () {
    if (!this.arrivals.length) return
    emit(EV.arrivals, this.arrivals.splice(0))
  }

  restartStatus () {
    clearInterval(this.statusTimer)
    if (state.hz > 0) this.statusTimer = setInterval(() => this.sendStatus(), 1000 / state.hz)
  }

  sendStatus () { emit(EV.status, this.snapshot()) }

  /** A subset of the status snapshot of docs/engine-api.md §6, with a `step1` block for the measurements. */
  snapshot () {
    const wire = this.mesh.wire()
    const stats = { dataRx: 0, requestTx: 0, cancelTx: 0 }
    for (const s of this.sets) {
      const st = s.core.replicator?.stats
      if (!st) continue
      stats.dataRx += st.wireData.rx
      stats.requestTx += st.wireRequest.tx
      stats.cancelTx += st.wireCancel.tx
    }
    const held = this.held.size
    return {
      at: { uptimeSec: Math.round(now() / 1000) },
      engine: { version: ENGINE.version, api: 1, wire: ENGINE.wire, storage: ENGINE.storage },
      me: { publicKey: hex(this.mesh.keyPair.publicKey) },
      profile: { chosen: this.p.name, effective: this.p.name },
      swarm: {
        connected: this.mesh.swarm.connections.size,
        foundNotConnected: Math.max(0, this.mesh.swarm.peers.size - this.mesh.swarm.connections.size),
        peers: [...this.byPeer].map(([k, p]) => ({ key: k, role: p.station ? 'station-seed' : 'listener', rxBytes: p.rx, txBytes: p.tx }))
      },
      library: { edition: this.edition ?? null, sets: this.sets.map((s) => ({ id: s.it.id, state: s.builder.done ? 'built' : 'building', pct: Math.floor((s.got / s.blocks) * 100) })) },
      ledger: {
        today: { wifi: { downBytes: wire.rx, upBytes: wire.tx } },
        overheadPct: this.audio ? +((wire.rx / this.audio - 1) * 100).toFixed(1) : null,
        duplicatePct: held ? +(((stats.dataRx - held) / held) * 100).toFixed(1) : null
      },
      step1: {
        phase: this.phase, held, total: this.total ?? null, inflight: this.inflight.size, audioBytes: this.audio,
        firstBlockMs: this.firstMs, allBlocksMs: this.allMs, wire, stats
      }
    }
  }

  async stop (why) {
    if (state.stopping) return
    state.stopping = true
    clearInterval(this.pump)
    clearInterval(this.statusTimer)
    clearInterval(this.arrivalTimer)
    this.flushArrivals()
    for (const s of this.sets) { while (s.builder.busy) await new Promise((resolve) => setTimeout(resolve, 20)) }
    const snap = this.snapshot()
    this.log({
      ev: 'end', why, held: snap.step1.held, total: snap.step1.total, audio: this.audio, wire: snap.step1.wire, stats: snap.step1.stats,
      firstMs: this.firstMs, allMs: this.allMs, conns: [...this.mesh.swarm.connections].map((c) => hex(c.remotePublicKey).slice(0, 8)),
      nat: this.mesh.nat(), built: this.sets.filter((s) => s.builder.done).length, bursts: 0, late: 0, rssMB: 0, cpuMs: 0, sketch: 0
    })
    emit(EV.status, snap)
    await Promise.race([this.mesh.close(), new Promise((resolve) => setTimeout(resolve, 1500))]) // the API's 2 s limit
    if (this.out) fs.closeSync(this.out)
  }
}
