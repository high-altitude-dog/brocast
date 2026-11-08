// One mesh node, station or listener: a Corestore and a Hyperswarm keyed from one 32-byte seed, plus the listener-count
// sketch on its own Protomux channel. Bare-compatible: no Node-only modules here.
import Corestore from 'corestore'
import Hyperswarm from 'hyperswarm'
import Protomux from 'protomux'
import c from 'compact-encoding'
import crypto from 'hypercore-crypto'
import b4a from 'b4a'
import { Sketch } from './sketch.js'

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
const isPrivate = (h) => /^(10\.|127\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.|::1|f[cd]|fe80)/.test(h || '')
const copy = (o) => JSON.parse(JSON.stringify(o))

export class Mesh {
  /**
   * lan: false turns off HyperDHT's LAN shortcut for the connections this node opens (step 2: internet paths only).
   * relayThrough: a blind relay's public key, used only when hole-punching fails or this node's NAT is randomized.
   * onconn: called with one record per connection attempt and per accepted connection (step 2's connection types).
   */
  constructor ({ dir, seed, bootstrap, maxPeers = 64, relaySketch = true, lan = true, relayThrough = null, onconn = null }) {
    const k = keysFromSeed(seed)
    this.keyPair = k.keyPair
    this.store = new Corestore(dir, { primaryKey: k.primaryKey, unsafe: true })
    this.swarm = new Hyperswarm({ keyPair: this.keyPair, bootstrap, maxPeers, relayThrough: relayThrough || undefined })
    this.onconn = onconn
    this.conns = [] // step 2: every attempt and accepted connection, with its path and outcome
    this._watchConnects(lan)
    this.allow = null // set to (remotePublicKey) => bool to refuse connections, as Privacy mode does
    this.sketch = new Sketch()
    this.relaySketch = relaySketch
    this.channels = new Set()
    this.closedWire = { rx: 0, tx: 0, packetsRx: 0, packetsTx: 0, retransmits: 0 }
    this.swarm.on('connection', (conn) => this._onconnection(conn))
  }

  /** Wraps dht.connect so each outgoing attempt is recorded: time to open, path, or the error code it failed with. */
  _watchConnects (lan) {
    const dht = this.swarm.dht
    const connect = dht.connect.bind(dht)
    dht.connect = (publicKey, opts = {}) => {
      const t = Date.now()
      const before = copy({ punches: dht.stats.punches, relaying: dht.stats.relaying })
      const conn = connect(publicKey, lan ? opts : { ...opts, localConnection: false })
      conn.on('open', () => this._record(conn, 'client', t, before, 'open'))
      conn.on('error', (err) => this._record(conn, 'client', t, before, err.code || err.message))
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
      if (isPrivate(host)) path = 'lan'
      else if (relaying && relaying.successes) path = 'relayed'
      else if (punches && punches.open && !punches.consistent && !punches.random) path = 'direct'
      else path = dht.firewalled ? 'punched' : 'direct'
    }
    const r = {
      peer: conn.remotePublicKey ? hex(conn.remotePublicKey).slice(0, 8) : null, side, outcome, path,
      ms: t ? Date.now() - t : null, remote: host ? host + ':' + raw.remotePort : null, punches, relaying
    }
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
    conn.on('error', () => {})
    conn.on('close', () => add(this.closedWire, conn.rawStream))
    this.store.replicate(conn)
    if (!this.relaySketch) return
    const ch = Protomux.from(conn).createChannel({ protocol: 'brocast/sketch/1', onclose: () => this.channels.delete(ch) })
    if (!ch) return
    ch.sketchMsg = ch.addMessage({ encoding: c.raw, onmessage: (b) => this.sketch.merge(b) })
    ch.open()
    this.channels.add(ch)
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
