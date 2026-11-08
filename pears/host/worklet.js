// The engine host for the main app (org.beachsync.proto), in a bare-kit worklet. PearsEngine.kt speaks the commands of
// docs/engine-api.md over bare-rpc on Bare.IPC: hello, start, subscribe, status, stop. Control, status and events are JSON.
// Given only the station key, it finds the catalog through the station's DHT record, sends the catalog's library list to
// the app (the same format as the server's library.json), fetches the blocks of every set the phone doesn't have yet,
// builds each set in the library folder with its BLAKE2b check, and keeps sharing what it holds.
// Adapted from android-test/js/worklet.js (steps 1 and 2), without the measurement log.
import './globals.js'
import RPC from 'bare-rpc'
import b4a from 'b4a'
import { ENGINE, topic, decodeRecord } from '../engine/index.js'
import { Mesh, hex } from '../engine/mesh.js'
import { profile } from '../engine/policy.js'
import { SetBuilder, serialOf } from '../engine/builder.js'
import { hedge } from '../engine/hedge.js'

const CMD = { hello: 1, start: 2, subscribe: 3, status: 4, stop: 5 }
const EV = { status: 100, built: 102, warning: 103, fatal: 104, catalog: 105 }

const t0 = Date.now()
const now = () => Date.now() - t0
let rpc = null
const emit = (ev, body) => { try { rpc.event(ev).send(JSON.stringify(body)) } catch {} }
const fail = (code, err) => emit(EV.fatal, { code, text: String(err?.stack || err) })
Bare.on('uncaughtException', (e) => fail('UNCAUGHT', e)) // an uncaught error would abort the whole app
Bare.on('unhandledRejection', (e) => { if (!state.stopping) fail('UNHANDLED', e) })

const state = { started: false, stopping: false, hz: 1 }
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
      run?.restartStatus()
      return reply({})
    case CMD.status:
      if (!run) refuse('NOT_STARTED', 'start first')
      return reply(run.snapshot())
    case CMD.stop:
      if (run) await run.stop()
      reply({})
      return
    default:
      refuse('BAD_REQUEST', 'unknown command ' + req.command)
  }
})

class Run {
  constructor ({ seed, stationKey, dirs, profile: prof = 'balanced', lan = true, relayThrough, share = true }) {
    this.p = profile(prof)
    // share: false is receive-only (mobile data without the listener's opt-in): never announced, and each block is cleared once it's in the built
    // file, so there's nothing to upload (step 0's copy and clear: 0 bytes up).
    this.share = share
    this.dirs = dirs
    this.stationKey = b4a.from(stationKey, 'hex')
    this.mesh = new Mesh({
      dir: dirs.store, seed: b4a.from(seed, 'hex'), maxPeers: this.p.maxPeers, lan,
      relayThrough: relayThrough ? b4a.from(relayThrough, 'hex') : null
    })
    this.held = new Set()
    this.inflight = new Map()
    this.byPeer = new Map()
    this.sets = []
    this.phase = 'finding station'
    this.restartStatus()
    this.main().catch((e) => { if (!state.stopping) fail('RUN', e) })
  }

  async main () {
    const dht = this.mesh.swarm.dht
    let r = null
    while (!r && !state.stopping) { // the station's pointer record: the station key is all the phone is given
      r = await dht.mutableGet(this.stationKey, { refresh: () => true }).catch(() => null)
      if (!r) await new Promise((resolve) => setTimeout(resolve, 1000))
    }
    if (state.stopping) return
    this.catalog = this.mesh.store.get({ key: b4a.from(decodeRecord(r.value).catalog, 'hex') })
    await this.catalog.ready()
    const found = this.mesh.store.findingPeers()
    this.mesh.swarm.join(topic(this.catalog.key), { server: this.p.announce && this.share, client: true })
    this.mesh.swarm.flush().then(found)
    this.phase = 'getting the set list'
    await this.catalog.update({ wait: true })
    const ed = JSON.parse(b4a.toString(await this.catalog.get(this.catalog.length - 1)))
    this.edition = ed.edition
    this.seeds = new Set(ed.seeds)
    this.bs = ed.block_seconds
    // The station's library list, as the server's library.json, so the app's timeline is the same whichever way the sets arrive.
    emit(EV.catalog, { edition: ed.edition, library: ed.library ?? null })
    for (const [n, it] of ed.items.entries()) {
      const core = this.mesh.store.get({ key: b4a.from(it.core, 'hex') })
      await core.ready()
      hedge(core, { mode: 'delayed', afterMs: 1000 }) // duplicate blocks: a second peer is asked only after 1 s in flight
      const s = { n, it, blocks: it.blocks, core, got: 0, cleared: 0 }
      s.builder = new SetBuilder({ music: this.dirs.music, id: it.id, blocks: it.blocks, serial: serialOf(core.key), sha: it.blake2b })
      core.on('download', (index, bytes, from) => this.peer(from).rx += bytes)
      core.on('upload', (index, bytes, to) => this.peer(to).tx += bytes)
      this.sets.push(s)
    }
    // Sets already on the phone (from the server, before the mesh) aren't fetched again.
    this.todo = this.sets.filter((s) => !s.builder.done)
    this.total = this.todo.reduce((a, s) => a + s.blocks, 0)
    this.phase = this.total ? 'fetching' : 'all sets held'
    this.window = Math.max(2, Math.round(16 / this.bs)) // in flight by bytes, about 300 KB
    this.pump = setInterval(() => this.fetchMore(), 50)
    this.fetchMore()
  }

  peer (conn) {
    const k = hex(conn.remotePublicKey)
    let p = this.byPeer.get(k)
    if (!p) this.byPeer.set(k, p = { rx: 0, tx: 0, station: this.seeds?.has(k) ?? false })
    return p
  }

  fetchMore () {
    if (state.stopping) return
    for (const s of this.todo) {
      for (let i = 0; i < s.blocks && this.inflight.size < this.window; i++) {
        const key = s.n + ':' + i
        if (this.held.has(key) || this.inflight.has(key)) continue
        this.fetch(s, i, key)
      }
      if (this.inflight.size >= this.window) return
    }
  }

  fetch (s, i, key) {
    this.inflight.set(key, now())
    s.core.get(i, { timeout: 20000 }).then(() => {
      this.held.add(key)
      s.got++
      this.build(s)
      if (this.held.size === this.total) { this.phase = 'all sets held'; this.sendStatus() }
    }, () => {}).finally(() => this.inflight.delete(key)) // a failed block is asked for again on the next pump
  }

  async build (s) {
    const r = await s.builder.pump(async (i) => this.held.has(s.n + ':' + i) ? s.core.get(i, { wait: false }) : null)
    if (!this.share) {
      const upTo = s.builder.done ? s.blocks : s.builder.writer.written
      if (upTo > s.cleared) { const from = s.cleared; s.cleared = upTo; await s.core.clear(from, upTo) }
    }
    if (!r) return
    if (r === 'built') emit(EV.built, { setId: s.it.id, path: this.dirs.music + '/' + s.it.id + '.opus', blake2b: s.it.blake2b })
    else emit(EV.warning, { code: 'MISMATCH', text: s.it.id + ' failed its BLAKE2b check' })
    this.sendStatus()
  }

  restartStatus () {
    clearInterval(this.statusTimer)
    if (state.hz > 0) this.statusTimer = setInterval(() => this.sendStatus(), 1000 / state.hz)
  }

  sendStatus () { emit(EV.status, this.snapshot()) }

  /** A subset of the status snapshot of docs/engine-api.md §6. */
  snapshot () {
    const wire = this.mesh.wire()
    return {
      at: { uptimeSec: Math.round(now() / 1000) },
      engine: { version: ENGINE.version, api: 1, wire: ENGINE.wire, storage: ENGINE.storage },
      me: { publicKey: hex(this.mesh.keyPair.publicKey) },
      phase: this.phase,
      sharing: this.share,
      profile: { chosen: this.p.name, effective: this.p.name },
      swarm: {
        connected: this.mesh.swarm.connections.size,
        foundNotConnected: Math.max(0, this.mesh.swarm.peers.size - this.mesh.swarm.connections.size),
        peers: [...this.byPeer].map(([k, p]) => ({ key: k, role: p.station ? 'station-seed' : 'listener', rxBytes: p.rx, txBytes: p.tx }))
      },
      library: {
        edition: this.edition ?? null,
        held: this.held.size,
        total: this.total ?? null,
        sets: this.sets.map((s) => ({ id: s.it.id, state: s.builder.done ? 'built' : 'building', pct: s.builder.done ? 100 : Math.floor((s.got / s.blocks) * 100) }))
      },
      ledger: { today: { downBytes: wire.rx, upBytes: wire.tx } }
    }
  }

  async stop () {
    if (state.stopping) return
    state.stopping = true
    clearInterval(this.pump)
    clearInterval(this.statusTimer)
    for (const s of this.sets) { while (s.builder.busy) await new Promise((resolve) => setTimeout(resolve, 20)) }
    await Promise.race([this.mesh.close(), new Promise((resolve) => setTimeout(resolve, 1500))]) // the API's 2 s limit
  }
}
