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

export class Mesh {
  constructor ({ dir, seed, bootstrap, maxPeers = 64, relaySketch = true }) {
    const k = keysFromSeed(seed)
    this.keyPair = k.keyPair
    this.store = new Corestore(dir, { primaryKey: k.primaryKey, unsafe: true })
    this.swarm = new Hyperswarm({ keyPair: this.keyPair, bootstrap, maxPeers })
    this.allow = null // set to (remotePublicKey) => bool to refuse connections, as Privacy mode does
    this.sketch = new Sketch()
    this.relaySketch = relaySketch
    this.channels = new Set()
    this.closedWire = { rx: 0, tx: 0, packetsRx: 0, packetsTx: 0, retransmits: 0 }
    this.swarm.on('connection', (conn) => this._onconnection(conn))
  }

  _onconnection (conn) {
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
