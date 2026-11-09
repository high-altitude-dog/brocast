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
import { Mesh, hex, isPrivate } from '../engine/mesh.js'
import { Countries } from '../engine/countries.js'
import { profile } from '../engine/policy.js'
import { SetBuilder, serialOf } from '../engine/builder.js'
import { hedge } from '../engine/hedge.js'
import { nymFrom, nymProof, nymText } from '../engine/nym.js'
import { PACKETS } from '../engine/blocks.js'

const CMD = { hello: 1, start: 2, subscribe: 3, status: 4, stop: 5, block: 6, prefs: 7 }
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
    case CMD.block: { // playback before a set is whole: one 1 s block, as LocalSupply would read it from the built file
      const s = run?.byId?.get(body.setId)
      if (!s) refuse('NOT_READY', 'no such set yet: ' + body.setId)
      const i = Math.floor(body.second / run.bs)
      run.focus(s)
      const buf = await s.core.get(i, { timeout: 8000 })
      run.got(s, i)
      return req.reply(oneSecond(buf, body.second % run.bs))
    }
    case CMD.prefs: // settings that change live, without a restart
      if (!run) refuse('NOT_STARTED', 'start first')
      if ('showCountry' in body) run.mesh.setShowCountry(body.showCountry)
      if (Array.isArray(body.blocked)) run.mesh.setBlocked(body.blocked.map(String))
      return reply({})
    case CMD.stop:
      if (run) await run.stop()
      reply({})
      return
    default:
      refuse('BAD_REQUEST', 'unknown command ' + req.command)
  }
})

/** The [k]th 1 s block of a longer block: 50 packets, each a 2-byte little-endian length and its bytes (engine/blocks.js). */
function oneSecond (buf, k) {
  let at = 0
  const skip = (n) => { for (let p = 0; p < n; p++) at += 2 + buf.readUInt16LE(at) }
  skip(k * PACKETS)
  const from = at
  skip(PACKETS)
  return buf.subarray(from, at)
}

class Run {
  constructor ({ seed, stationKey, dirs, profile: prof = 'balanced', lan = true, relayThrough, share = true, count = true, showCountry = false, countryTable = null, blocked = [], app = '' }) {
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
    // The nym, from this phone's own signature (shuffle 0 until the wizard can shuffle), and the listener count: this phone joins the
    // gossiped sketch unless counting is off (Privacy mode), and passes on what it hears either way.
    const pk = this.mesh.keyPair.publicKey
    this.nym = nymText(nymFrom(pk, 0, nymProof(this.mesh.keyPair.secretKey, 0)))
    if (count) this.mesh.sketch.add(pk, 0)
    this.mesh.setShowCountry(showCountry)
    this.mesh.setBlocked(blocked)
    // The hello (hand-off 46): this phone's nym proof, app and engine versions, and how much of the library it holds, refreshed every 10 s.
    this.helloBase = { shuffle: 0, proof: hex(nymProof(this.mesh.keyPair.secretKey, 0)), app: String(app).slice(0, 32), engine: String(ENGINE.version) }
    this.mesh.setHello({ ...this.helloBase, heldPct: null })
    this.helloTimer = setInterval(() => this.mesh.setHello({ ...this.helloBase, heldPct: this.total ? Math.floor((100 * this.held.size) / this.total) : (this.phase === 'all sets held' ? 100 : null) }), 10000)
    try { this.countries = countryTable ? Countries.load(countryTable) : null } catch (e) { this.countries = null; emit(EV.warning, { code: 'COUNTRIES', text: String(e) }) }
    this.gossip = setInterval(() => this.mesh.gossip(), 2000)
    this.rate = { at: now(), byPeer: new Map() } // last snapshot's totals, for kb/s
    this.held = new Set()
    this.inflight = new Map()
    this.byPeer = new Map()
    this.sets = []
    this.byId = new Map()
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
    await this.readEdition()
    // A newer edition (new sets, a new list) is read as soon as it arrives, from the station or any peer that has it.
    this.catalog.on('append', () => this.readEdition().catch((e) => fail('CATALOG', e)))
    this.window = Math.max(2, Math.round(16 / this.bs)) // in flight by bytes, about 300 KB
    this.pump = setInterval(() => this.fetchMore(), 50)
    this.fetchMore()
  }

  /** The newest edition: sends its library list to the app and adds any set not seen before. */
  async readEdition () {
    const ed = JSON.parse(b4a.toString(await this.catalog.get(this.catalog.length - 1)))
    if (ed.edition === this.edition) return
    this.edition = ed.edition
    this.seeds = new Set(ed.seeds)
    this.bs = ed.block_seconds
    // The station's library list, as the server's library.json, so the app's timeline is the same whichever way the sets arrive.
    emit(EV.catalog, { edition: ed.edition, library: ed.library ?? null })
    for (const it of ed.items) {
      if (this.byId.has(it.id)) continue
      const n = this.sets.length
      const core = this.mesh.store.get({ key: b4a.from(it.core, 'hex') })
      await core.ready()
      hedge(core, { mode: 'delayed', afterMs: 1000 }) // duplicate blocks: a second peer is asked only after 1 s in flight
      const s = { n, it, blocks: it.blocks, core, got: 0, cleared: 0 }
      s.builder = new SetBuilder({ music: this.dirs.music, id: it.id, blocks: it.blocks, serial: serialOf(core.key), sha: it.blake2b })
      core.on('download', (index, bytes, from) => this.peer(from).rx += bytes)
      core.on('upload', (index, bytes, to) => this.peer(to).tx += bytes)
      this.sets.push(s)
      this.byId.set(it.id, s)
    }
    // Sets already on the phone (from the server, before the mesh) aren't fetched again.
    this.todo = this.sets.filter((s) => !s.builder.done)
    this.total = this.todo.reduce((a, s) => a + s.blocks, 0)
    this.phase = this.held.size < this.total ? 'fetching' : 'all sets held'
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
    s.core.get(i, { timeout: 20000 }).then(() => this.got(s, i), () => {})
      .finally(() => this.inflight.delete(key)) // a failed block is asked for again on the next pump
  }

  /** Block [i] of set [s] is in the store, by the fetch loop or for playback: counted once, and the set built as far as it goes. */
  got (s, i) {
    const key = s.n + ':' + i
    if (this.held.has(key)) return
    this.held.add(key)
    s.got++
    this.build(s)
    if (this.held.size === this.total) { this.phase = 'all sets held'; this.sendStatus() }
  }

  /** The set being played goes to the front of the fetch order, so what's heard next arrives first. */
  focus (s) {
    const at = this.todo?.indexOf(s) ?? -1
    if (at > 0) { this.todo.splice(at, 1); this.todo.unshift(s) }
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

  /** Swarm listener details (hand-off 46): their checked nym, versions and holding from their hello, round trip, and when they connected. */
  details (k) {
    const i = this.mesh.peerInfo.get(k)
    const rtt = this.mesh.rttMs(k)
    return { nym: i?.nym ?? null, verified: i?.verified ?? false, app: i?.app ?? null, engineVersion: i?.engine ?? null, heldPct: i?.heldPct ?? null, rttMs: Number.isFinite(rtt) ? Math.round(rtt) : null, connectedAt: this.mesh.connectedAt.get(k) ?? null }
  }

  /** A subset of the status snapshot of docs/engine-api.md §6. */
  snapshot () {
    const wire = this.mesh.wire()
    // kb/s per peer since the last snapshot, and each connection's path from its latest open record.
    const t = now()
    const dt = Math.max(0.25, (t - this.rate.at) / 1000)
    const paths = new Map()
    for (const c of this.mesh.conns) if (c.outcome === 'open' && c.peer) paths.set(c.peer, c.path)
    const hosts = new Map([...this.mesh.swarm.connections].map((c) => [hex(c.remotePublicKey), c.rawStream?.remoteHost]))
    const live = new Set(hosts.keys())
    // Each peer's country, only if it allows it (hand-off 38): a code, 'hidden', or 'unknown'. A relayed address is the relay's, so it's
    // never looked up; a same-Wi-Fi peer shares this phone's own public address, as the DHT sees it.
    const country = (k, path) => {
      if (!this.mesh.shows.get(k)) return 'hidden'
      if (path === 'relayed' || !this.countries) return 'unknown'
      const host = hosts.get(k)
      return this.countries.lookup(isPrivate(host) ? this.mesh.swarm.dht.host : host) ?? 'unknown'
    }
    const peers = [...this.byPeer].filter(([k]) => live.has(k)).map(([k, p]) => {
      const was = this.rate.byPeer.get(k) || p
      return {
        key: k, role: p.station ? 'station-seed' : 'listener', path: paths.get(k.slice(0, 8)) ?? null, country: country(k, paths.get(k.slice(0, 8))), rxBytes: p.rx, txBytes: p.tx,
        toYouKbps: Math.round(((p.rx - was.rx) * 8) / 1000 / dt), fromYouKbps: Math.round(((p.tx - was.tx) * 8) / 1000 / dt),
        ...this.details(k)
      }
    })
    // Connected peers that haven't sent or asked for a block yet still count, with no traffic.
    for (const k of live) if (!this.byPeer.has(k)) peers.push({ key: k, role: this.seeds?.has(k) ? 'station-seed' : 'listener', path: paths.get(k.slice(0, 8)) ?? null, country: country(k, paths.get(k.slice(0, 8))), rxBytes: 0, txBytes: 0, toYouKbps: 0, fromYouKbps: 0, ...this.details(k) })
    this.rate = { at: t, byPeer: new Map([...this.byPeer].map(([k, p]) => [k, { rx: p.rx, tx: p.tx }])) }
    return {
      at: { uptimeSec: Math.round(now() / 1000) },
      engine: { version: ENGINE.version, api: 1, wire: ENGINE.wire, storage: ENGINE.storage },
      me: { publicKey: hex(this.mesh.keyPair.publicKey), nym: this.nym, showCountry: this.mesh.showCountry },
      countries: this.countries ? { source: 'DB-IP ' + this.countries.source } : null,
      listeners: { estimate: this.mesh.sketch.estimate() },
      phase: this.phase,
      sharing: this.share,
      profile: { chosen: this.p.name, effective: this.p.name },
      swarm: {
        connected: this.mesh.swarm.connections.size,
        foundNotConnected: Math.max(0, this.mesh.swarm.peers.size - this.mesh.swarm.connections.size),
        peers
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
    clearInterval(this.gossip)
    clearInterval(this.helloTimer)
    for (const s of this.sets) { while (s.builder.busy) await new Promise((resolve) => setTimeout(resolve, 20)) }
    await Promise.race([this.mesh.close(), new Promise((resolve) => setTimeout(resolve, 1500))]) // the API's 2 s limit
  }
}
