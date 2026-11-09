// One mesh node, station or listener: a Corestore and a Hyperswarm keyed from one 32-byte seed, plus the listener-count
// sketch on its own Protomux channel, and each side's Show-my-country flag on another. Bare-compatible: no Node-only modules here.
import Corestore from 'corestore'
import Hyperswarm from 'hyperswarm'
import Protomux from 'protomux'
import c from 'compact-encoding'
import crypto from 'hypercore-crypto'
import b4a from 'b4a'
import { Sketch } from './sketch.js'
import { nymFrom, nymText } from './nym.js'

/** Everything a node needs, derived from its one secret seed, so one file backs it up. */
export function keysFromSeed (seed) {
  return {
    primaryKey: crypto.hash([seed, b4a.from('brocast/corestore')]),
    keyPair: crypto.keyPair(crypto.hash([seed, b4a.from('brocast/swarm')]))
  }
}

function add (w, raw) {
  if (!raw) return
  w.rx += raw.bytesReceived || 0
  w.tx += raw.bytesTransmitted || 0
  w.packetsRx += raw.packetsReceived || 0
  w.packetsTx += raw.packetsTransmitted || 0
  w.retransmits += raw.retransmits || 0
}

export const hex = (b) => b4a.toString(b, 'hex')

// Private, CGNAT (100.64/10) or loopback: a connection to one of these did not cross the public internet.
export const isPrivate = (h) => /^(10\.|127\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.|::1|f[cd]|fe80)/.test(h || '')
const copy = (o) => JSON.parse(JSON.stringify(o))

export class Mesh {
  /**
   * lan: false turns off HyperDHT's LAN shortcut for the connections this node opens (step 2: internet paths only).
   * relayThrough: a blind relay's public key, used only when hole-punching fails or this node's NAT is randomized.
   * forceRelay: (remotePublicKey) => bool; those connections go only through relayThrough (no fast open, no punch).
   *   Step 2 test only, to prove the relay path; the default (null) relays only after a punch fails.
   * onconn: called with one record per connection attempt and per accepted connection (step 2's connection types).
   */
  constructor ({ dir, seed, bootstrap, maxPeers = 64, relaySketch = true, lan = true, relayThrough = null, forceRelay = null, onconn = null }) {
    const k = keysFromSeed(seed)
    this.keyPair = k.keyPair
    this.store = new Corestore(dir, { primaryKey: k.primaryKey, unsafe: true })
    this.swarm = new Hyperswarm({ keyPair: this.keyPair, bootstrap, maxPeers, relayThrough: relayThrough || undefined })
    this.onconn = onconn
    this.conns = [] // step 2: every attempt and accepted connection, with its path and outcome
    this.relayKey = relayThrough
    this._watchConnects(lan, relayThrough, forceRelay)
    this.allow = null // set to (remotePublicKey) => bool to refuse connections, as Privacy mode does
    this.sketch = new Sketch()
    this.relaySketch = relaySketch
    this.channels = new Set()
    // Co-listener countries (hand-off 38): this node's own Show-my-country flag, sent to every peer, and each peer's as it last said.
    // A peer that never says (an older engine) counts as not shown.
    this.showCountry = false
    this.shows = new Map()
    this.flagChannels = new Set()
    // Swarm listener details (hand-off 46): each side's hello (nym proof, app and engine versions, how much it holds), checked against its key,
    // when each peer connected, and the listener's blocklist (they can't connect, and nothing is taken from them).
    this.hello = null
    this.peerInfo = new Map()
    this.connectedAt = new Map()
    this.blocked = new Set()
    this.helloChannels = new Set()
    this.closedWire = { rx: 0, tx: 0, packetsRx: 0, packetsTx: 0, retransmits: 0 }
    this.swarm.on('connection', (conn) => this._onconnection(conn))
  }

  /** Wraps dht.connect so each outgoing attempt is recorded: time to open, path, or the error code it failed with. */
  _watchConnects (lan, relayThrough, forceRelay) {
    const dht = this.swarm.dht
    const connect = dht.connect.bind(dht)
    dht.connect = (publicKey, opts = {}) => {
      const t = Date.now()
      const before = copy({ punches: dht.stats.punches, relaying: dht.stats.relaying })
      let o = lan ? opts : { ...opts, localConnection: false }
      const forced = !!(relayThrough && forceRelay && !b4a.equals(publicKey, relayThrough) && forceRelay(publicKey)) // never the relay itself
      // relayThrough plus a refused punch: hyperdht keeps the connection while the relay pairs (connect.js, 6.36).
      if (forced) o = { ...o, relayThrough, fastOpen: false, holepunch: () => false }
      const conn = connect(publicKey, o)
      conn.forcedRelay = forced
      let opened = false
      conn.on('open', () => { opened = true; this._record(conn, 'client', t, before, 'open') })
      // An error after opening is a connection ending (a peer stopping), not a failure to connect.
      conn.on('error', (err) => { if (!opened) this._record(conn, 'client', t, before, err.code || err.message) })
      return conn
    }
  }

  /** One connection record. Path: 'lan' for a private address; 'relayed' if relay counters moved; 'direct' if only
   * punches.open moved (the remote's firewall is open); otherwise 'punched'. A punch that succeeds in round 1 (both
   * sides' openSession through consistent NATs) moves no counter, so firewalled with no counters is a punch too
   * (step 2, from hyperdht 6.36's connect.js and holepuncher.js; inferred, not documented). */
  _record (conn, side, t, before, outcome) {
    const dht = this.swarm.dht
    const raw = conn.rawStream
    const host = raw?.remoteHost || null
    const d = (a, b) => Object.fromEntries(Object.keys(a).map((k) => [k, a[k] - (b?.[k] || 0)]).filter(([, v]) => v))
    const punches = before ? d(dht.stats.punches, before.punches) : null
    const relaying = before ? d(dht.stats.relaying, before.relaying) : null
    let path = null
    if (outcome === 'open') {
      // Relayed traffic arrives from our own connection to the relay, so its address gives a relayed stream away.
      const viaRelay = !!this.relayKey && this.conns.some((x) => x.relay && x.remote === (host ? host + ':' + raw.remotePort : null))
      if (isPrivate(host)) path = 'lan'
      else if (viaRelay || (relaying && relaying.successes) || conn.forcedRelay) path = 'relayed'
      else if (punches && punches.open && !punches.consistent && !punches.random) path = 'direct'
      else path = dht.firewalled ? 'punched' : 'direct'
    }
    const r = {
      peer: conn.remotePublicKey ? hex(conn.remotePublicKey).slice(0, 8) : null, side, outcome, path,
      ms: t ? Date.now() - t : null, remote: host ? host + ':' + raw.remotePort : null, punches, relaying
    }
    if (this.relayKey && conn.remotePublicKey && b4a.equals(conn.remotePublicKey, this.relayKey)) r.relay = true // the link to the relay itself
    this.conns.push(r)
    if (this.onconn) this.onconn(r)
  }

  /** This node's own NAT as HyperDHT sees it, and the cumulative punch and relay counters. */
  nat () {
    const dht = this.swarm.dht
    return { host: dht.host, port: dht.port, firewalled: dht.firewalled, randomized: dht.randomized, punches: copy(dht.stats.punches), relaying: copy(dht.stats.relaying), connects: copy(this.swarm.stats.connects) }
  }

  _onconnection (conn) {
    if (!conn.isInitiator) this._record(conn, 'server', null, null, 'open')
    if (this.allow && !this.allow(conn.remotePublicKey)) return conn.destroy()
    if (conn.remotePublicKey && this.blocked.has(hex(conn.remotePublicKey))) return conn.destroy()
    conn.on('error', () => {})
    conn.on('close', () => add(this.closedWire, conn.rawStream))
    this.store.replicate(conn)
    const k = conn.remotePublicKey ? hex(conn.remotePublicKey) : null
    const fc = Protomux.from(conn).createChannel({
      protocol: 'brocast/country/1',
      onopen: () => fc.flagMsg.send(this.showCountry ? 1 : 0),
      onclose: () => { this.flagChannels.delete(fc); if (k) this.shows.delete(k) }
    })
    if (fc) {
      fc.flagMsg = fc.addMessage({ encoding: c.uint, onmessage: (v) => { if (k) this.shows.set(k, v === 1) } })
      fc.open()
      this.flagChannels.add(fc)
    }
    if (k) this.connectedAt.set(k, Date.now())
    const hc = Protomux.from(conn).createChannel({
      protocol: 'brocast/hello/1',
      onopen: () => { if (this.hello) hc.helloMsg.send(this.hello) },
      onclose: () => { this.helloChannels.delete(hc); if (k) { this.peerInfo.delete(k); this.connectedAt.delete(k) } }
    })
    if (hc) {
      hc.helloMsg = hc.addMessage({
        encoding: c.json,
        onmessage: (m) => {
          if (!k || !m) return
          let nym = null
          try { const n = nymFrom(conn.remotePublicKey, m.shuffle | 0, b4a.from(String(m.proof || ''), 'hex')); nym = n ? nymText(n) : null } catch { nym = null }
          this.peerInfo.set(k, { nym, verified: nym !== null, app: String(m.app || '').slice(0, 32), engine: String(m.engine || '').slice(0, 32), heldPct: Number.isFinite(m.heldPct) ? Math.max(0, Math.min(100, m.heldPct)) : null })
        }
      })
      hc.open()
      this.helloChannels.add(hc)
    }
    if (!this.relaySketch) return
    const ch = Protomux.from(conn).createChannel({ protocol: 'brocast/sketch/1', onclose: () => this.channels.delete(ch) })
    if (!ch) return
    ch.sketchMsg = ch.addMessage({ encoding: c.raw, onmessage: (b) => this.sketch.merge(b) })
    ch.open()
    this.channels.add(ch)
  }

  /** This node's hello: { shuffle, proof (hex), app, engine, heldPct }. Sent to every peer now and to each new one. */
  setHello (hello) {
    this.hello = hello
    for (const ch of this.helloChannels) ch.helloMsg.send(hello)
  }

  /** The listener's blocklist (hex keys): blocked peers are cut off now and refused from then on. */
  setBlocked (keys) {
    this.blocked = new Set(keys)
    for (const conn of this.swarm.connections) if (this.blocked.has(hex(conn.remotePublicKey))) conn.destroy()
  }

  /** Round trip to a peer in ms, from its UDX stream, or null. */
  rttMs (key) {
    for (const conn of this.swarm.connections) if (hex(conn.remotePublicKey) === key) return conn.rawStream?.rtt ?? null
    return null
  }

  /** Changes this node's Show-my-country flag and tells every peer at once. */
  setShowCountry (on) {
    this.showCountry = !!on
    for (const ch of this.flagChannels) ch.flagMsg.send(this.showCountry ? 1 : 0)
  }

  /** Sends this node's sketch to every peer. Merging is idempotent, so resending is harmless. */
  gossip () {
    for (const ch of this.channels) ch.sketchMsg.send(this.sketch.r)
  }

  /**
   * Bytes on the wire, every connection, both ways, including handshakes, proofs and the DHT stream framing, with UDX
   * packet counts and this side's retransmissions (step 1: to explain the swarm wire overhead).
   */
  wire () {
    const w = { ...this.closedWire }
    for (const conn of this.swarm.connections) add(w, conn.rawStream)
    return w
  }

  async close () {
    await this.swarm.destroy()
    await this.store.close()
  }
}
