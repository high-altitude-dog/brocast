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
import { SourceLog } from '../engine/sources.js'
import { nymOfKey, nymText } from '../engine/nym.js'
/** Anyone's nym from their key (v2), for a peer whose hello hasn't arrived yet. */
const keyNym = (k) => { try { return nymText(nymOfKey(b4a.from(String(k), 'hex'))) } catch { return null } }
import { PACKETS, blocks as fileBlocks } from '../engine/blocks.js'
import { haveVisual, writeVisual } from '../engine/visuals.js'
import { havePhoto, writePhoto } from '../engine/photos.js'

const CMD = { hello: 1, start: 2, subscribe: 3, status: 4, stop: 5, block: 6, prefs: 7, react: 8, want: 9 }
const EV = { status: 100, built: 102, warning: 103, fatal: 104, catalog: 105, visual: 106, photo: 107 }

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
    case CMD.react: // a silent reaction from this listener (hand-off 53)
      if (!run) refuse('NOT_STARTED', 'start first')
      return reply({ sent: run.mesh.react(body.e | 0) })
    case CMD.want: { // Pass it on (hand-off 53): connect straight to the giver by key, and fetch their set first
      if (!run) refuse('NOT_STARTED', 'start first')
      if (/^[0-9a-f]{64}$/.test(String(body.peer || ''))) { try { run.mesh.swarm.joinPeer(b4a.from(body.peer, 'hex')) } catch {} }
      const s = body.setId ? run.byId?.get(body.setId) : null
      if (s) run.focus(s)
      return reply({ known: !!s, built: !!s?.builder?.done })
    }
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

/** Diagnostics for adb (logcat tag BeachSync, 'Mesh:'); added 15 Nov when a phone on store Wi-Fi never reached the station. */
const trace = (text) => emit(EV.warning, { code: 'TRACE', text })

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
      relayThrough: relayThrough ? b4a.from(relayThrough, 'hex') : null,
      onconn: (r) => trace('conn ' + JSON.stringify({ peer: r.peer, side: r.side, outcome: r.outcome, path: r.path, ms: r.ms, relay: r.relay || undefined }))
    })
    // The nym, from this phone's own signature (shuffle 0 until the wizard can shuffle), and the listener count: this phone joins the
    // gossiped sketch unless counting is off (Privacy mode), and passes on what it hears either way.
    const pk = this.mesh.keyPair.publicKey
    this.nym = nymText(nymOfKey(pk))
    if (count) this.mesh.sketch.add(pk, 0)
    this.mesh.setShowCountry(showCountry)
    this.mesh.setBlocked(blocked)
    // Silent reactions (hand-off 53): the last minute's, summed per emoji; the newest few with their sender's short key, for Swarm's pulses.
    this.reactions = []
    this.mesh.onreaction = (k, e, mine) => { this.reactions.push({ k: k.slice(0, 8), e, at: Date.now(), mine }); if (this.reactions.length > 5000) this.reactions.splice(0, 1000) }
    // The hello (hand-off 46): this phone's nym proof, app and engine versions, and how much of the library it holds, refreshed every 10 s.
    this.helloBase = { app: String(app).slice(0, 32), engine: String(ENGINE.version) }
    this.mesh.setHello({ ...this.helloBase, heldPct: null })
    this.helloTimer = setInterval(() => this.mesh.setHello({ ...this.helloBase, heldPct: this.total ? Math.floor((100 * this.held.size) / this.total) : (this.phase === 'all sets held' ? 100 : null) }), 10000)
    try { this.countries = countryTable ? Countries.load(countryTable) : null } catch (e) { this.countries = null; emit(EV.warning, { code: 'COUNTRIES', text: String(e) }) }
    this.gossip = setInterval(() => this.mesh.gossip(), 2000)
    this.srcTimer = setInterval(() => { for (const s of this.sets ?? []) s.src.flush() }, 5000)
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
    let tries = 0
    await dht.ready().catch(() => {})
    trace('dht ready: ' + JSON.stringify({ host: dht.host, port: dht.port, firewalled: dht.firewalled, randomized: dht.randomized, share: this.share, relay: !!this.mesh.relayKey }))
    while (!r && !state.stopping) { // the station's pointer record: the station key is all the phone is given
      let err = null
      r = await dht.mutableGet(this.stationKey, { refresh: () => true }).catch((e) => { err = e; return null })
      if (!r && ++tries % 10 === 1) trace('station record not found yet (try ' + tries + ')' + (err ? ': ' + (err.code || err.message) : ''))
      if (!r) await new Promise((resolve) => setTimeout(resolve, 1000))
    }
    if (r) trace('station record found after ' + tries + ' misses')
    if (state.stopping) return
    this.catalog = this.mesh.store.get({ key: b4a.from(decodeRecord(r.value).catalog, 'hex') })
    await this.catalog.ready()
    const found = this.mesh.store.findingPeers()
    this.mesh.swarm.join(topic(this.catalog.key), { server: this.p.announce && this.share, client: true })
    this.mesh.swarm.flush().then(found)
    this.phase = 'getting the set list'
    trace('joined the catalog topic; waiting for the set list')
    // Wait for the list itself: over a slow path (a relay) the first update can finish before the station has sent any of it (15 Nov).
    await this.catalog.update({ wait: true })
    while (this.catalog.length === 0 && !state.stopping) {
      await new Promise((resolve) => setTimeout(resolve, 1000))
      await this.catalog.update({ wait: true }).catch(() => {})
    }
    if (state.stopping) return
    trace('set list: ' + this.catalog.length + ' editions')
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
    this.addPhotos(ed.photos ?? [])
    for (const it of ed.items) {
      if (this.byId.has(it.id)) { await this.addVisual(this.byId.get(it.id), it.visual); continue }
      const n = this.sets.length
      const core = this.mesh.store.get({ key: b4a.from(it.core, 'hex') })
      await core.ready()
      hedge(core, { mode: 'delayed', afterMs: 1000 }) // duplicate blocks: a second peer is asked only after 1 s in flight
      const s = { n, it, blocks: it.blocks, core, got: 0, cleared: 0 }
      s.builder = new SetBuilder({ music: this.dirs.music, id: it.id, blocks: it.blocks, serial: serialOf(core.key), sha: it.blake2b })
      // Where it came from (hand-off 58): the first verified copy of each block names its sender; the time is from when it was asked for.
      s.src = new SourceLog(this.dirs.music + '/' + it.id + '.src', it.blocks, it.block_seconds ?? this.bs)
      core.on('download', (index, bytes, from) => {
        this.peer(from).rx += bytes
        const k = hex(from.remotePublicKey)
        const asked = this.inflight.get(n + ':' + index)
        s.src.record(index, k, asked == null ? null : now() - asked, () => this.describe(k))
      })
      core.on('upload', (index, bytes, to) => this.peer(to).tx += bytes)
      if (this.share) this.mesh.proofs.offer(core)
      this.sets.push(s)
      this.byId.set(it.id, s)
      await this.addVisual(s, it.visual)
    }
    this.importHeld()
    // Sets already on the phone (from the server, before the mesh) aren't fetched again; nor are visuals it holds.
    this.todo = this.sets.filter((s) => !s.builder.done || (s.vis && !s.vis.done))
    this.total = this.todo.filter((s) => !s.builder.done).reduce((a, s) => a + s.blocks, 0)
    this.phase = this.held.size < this.total ? 'fetching' : 'all sets held'
  }

  /** The catalog's artist photos (engine/photos.js): each one not already on disk with the right hash is fetched, checked and written, two at a time. */
  addPhotos (list) {
    this.photoSeen ??= new Set()
    const todo = list.filter((p) => !this.photoSeen.has(p.core) && (this.photoSeen.add(p.core), !havePhoto(this.dirs.music, p)))
    const next = async () => {
      for (let p; (p = todo.shift()) && !state.stopping;) {
        try {
          const core = this.mesh.store.get({ key: b4a.from(p.core, 'hex') })
          await core.ready()
          const r = writePhoto(this.dirs.music, p, await core.get(0, { timeout: 20000 }))
          if (r === 'built') emit(EV.photo, { file: p.f })
          else emit(EV.warning, { code: 'PHOTO', text: p.f + ' failed its check: ' + r })
          if (!this.share) await core.clear(0, 1).catch(() => {})
        } catch (e) { this.photoSeen.delete(p.core); setTimeout(() => this.addPhotos([p]), 30000) } // not found in time: asked again in 30 s
      }
    }
    next(); next()
  }

  /** Set [s]'s visual from the catalog (engine/visuals.js): already held and checked, or a core to fetch before the set's audio. */
  async addVisual (s, v) {
    if (!v || s.vis?.v.core === v.core) return
    const core = this.mesh.store.get({ key: b4a.from(v.core, 'hex') })
    await core.ready()
    hedge(core, { mode: 'delayed', afterMs: 1000 })
    core.on('download', (index, bytes, from) => { this.peer(from).rx += bytes })
    core.on('upload', (index, bytes, to) => this.peer(to).tx += bytes)
    s.vis = { v, core, blocks: v.blocks, held: new Set(), done: haveVisual(this.dirs.music, s.it.id, v), cleared: false }
    if (s.vis.done && !this.share) { await core.clear(0, v.blocks).catch(() => {}); s.vis.cleared = true }
  }

  /** Block [i] of set [s]'s visual is in the store; once all are, the files are checked and written where the visualiser looks. */
  async gotVisual (s, vis, i) {
    if (vis !== s.vis || vis.done) return
    vis.held.add(i)
    if (vis.held.size < vis.blocks || vis.writing) return
    vis.writing = true
    try {
      const blocks = []
      for (let k = 0; k < vis.blocks; k++) blocks.push(await vis.core.get(k, { wait: false }))
      const r = writeVisual(this.dirs.music, s.it.id, vis.v, blocks)
      vis.done = true
      if (r === 'built') emit(EV.visual, { setId: s.it.id })
      else emit(EV.warning, { code: 'VISUAL', text: s.it.id + ' visual failed its check: ' + r })
      if (!this.share) { await vis.core.clear(0, vis.blocks).catch(() => {}); vis.cleared = true }
    } catch (e) { emit(EV.warning, { code: 'VISUAL', text: s.it.id + ': ' + (e?.message || e) }) } finally { vis.writing = false }
    this.sendStatus()
  }

  /**
   * Sets this phone already has from the server: proofs only are fetched, its own blocks go into the core, and the set is
   * then served like a mesh set (proofs.js). Not on receive-only, which never uploads. One set at a time, oldest first.
   */
  async importHeld () {
    if (!this.share || this.importing) return
    this.importing = true
    try {
      for (const s of this.sets) {
        if (state.stopping) return
        if (!s.builder.done || s.imported || s.core.contiguousLength >= s.blocks) { if (s.core.contiguousLength >= s.blocks) s.imported = true; continue }
        let mine
        try { mine = fileBlocks(this.dirs.music + '/' + s.it.id + '.opus', s.it.block_seconds ?? this.bs) } catch (e) { s.imported = 'unreadable'; continue }
        if (mine.length !== s.blocks) { s.imported = 'length'; emit(EV.warning, { code: 'IMPORT', text: s.it.id + ': file has ' + mine.length + ' blocks, set has ' + s.blocks }); continue }
        const t = now()
        const r = await this.mesh.proofs.import(s.core, mine, (ch) => this.seeds?.has(ch.peer) ? 1 : 0)
        if (r.ok) s.imported = true
        else if (r.why === 'no peer proved it') { setTimeout(() => this.importHeld(), 30000); return } // try again when peers come
        else { s.imported = 'mismatch'; emit(EV.warning, { code: 'IMPORT', text: s.it.id + ': ' + r.why }) }
        emit(EV.warning, { code: 'IMPORTED', text: s.it.id + (r.ok ? ' offered on the mesh' : ' not offered') + ': ' + r.imported + '/' + s.blocks + ' blocks, ' + Math.round(r.proofBytes / 1024) + ' KB of proofs, ' + Math.round(now() - t) + ' ms' })
        this.sendStatus()
      }
    } finally { this.importing = false }
  }

  /** A source, as the log keeps it the first time it sends a block: nym, country at the time, route, and whether it's the station. */
  describe (k) {
    let route = null
    for (const c of this.mesh.conns) if (c.outcome === 'open' && c.peer === k.slice(0, 8)) route = c.path
    const host = [...this.mesh.swarm.connections].find((c) => hex(c.remotePublicKey) === k)?.rawStream?.remoteHost
    let country = 'unknown'
    if (!this.mesh.shows.get(k)) country = 'hidden'
    else if (route !== 'relayed' && this.countries) country = this.countries.lookup(isPrivate(host) ? this.mesh.swarm.dht.host : host) ?? 'unknown'
    return { nym: this.mesh.peerInfo.get(k)?.nym ?? null, country, route, station: this.seeds?.has(k) ?? false }
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
      // The set's visual first: it must be whole before the set starts on the screen, and it is ~5% of the set's bytes.
      const vis = s.vis
      if (vis && !vis.done) {
        for (let i = 0; i < vis.blocks && this.inflight.size < this.window; i++) {
          const key = 'v' + s.n + ':' + i
          if (vis.held.has(i) || this.inflight.has(key)) continue
          this.inflight.set(key, now())
          vis.core.get(i, { timeout: 20000 }).then(() => this.gotVisual(s, vis, i), () => {}).finally(() => this.inflight.delete(key))
        }
        if (this.inflight.size >= this.window) return
      }
      if (s.builder.done) continue
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
    s.src.flush()
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
    return { nym: i?.nym ?? keyNym(k), verified: true, app: i?.app ?? null, engineVersion: i?.engine ?? null, heldPct: i?.heldPct ?? null, rttMs: Number.isFinite(rtt) ? Math.round(rtt) : null, connectedAt: this.mesh.connectedAt.get(k) ?? null }
  }

  /** The last minute's reactions per emoji, and the last 5 s individually (short key, emoji, how long ago in ms). */
  reactionSummary () {
    const t = Date.now()
    this.reactions = this.reactions.filter((r) => t - r.at < 60000)
    const counts = [0, 0, 0, 0, 0, 0]
    for (const r of this.reactions) counts[r.e]++
    return { counts, recent: this.reactions.filter((r) => t - r.at < 5000).map((r) => ({ k: r.k, e: r.e, ago: t - r.at, mine: r.mine })) }
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
      reactions: this.reactionSummary(),
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
        sets: this.sets.map((s) => ({ id: s.it.id, state: s.builder.done ? 'built' : 'building', pct: s.builder.done ? 100 : Math.floor((s.got / s.blocks) * 100), offered: s.builder.done ? s.core.contiguousLength >= s.blocks : null, visual: !s.vis ? 'none' : s.vis.done ? 'held' : Math.floor((s.vis.held.size / s.vis.blocks) * 100) + '%' }))
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
    clearInterval(this.srcTimer)
    for (const s of this.sets) s.src.flush()
    for (const s of this.sets) { while (s.builder.busy) await new Promise((resolve) => setTimeout(resolve, 20)) }
    await Promise.race([this.mesh.close(), new Promise((resolve) => setTimeout(resolve, 1500))]) // the API's 2 s limit
  }
}
