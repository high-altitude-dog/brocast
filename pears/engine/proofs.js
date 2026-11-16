// Offering a set that came over HTTP on the mesh (work queue, 15 Nov). A phone that downloaded a set from the server has the
// audio but an empty Hypercore, so it couldn't serve it, and switching to the mesh fetched the whole library again. Here the
// phone asks a peer for proofs only (tree nodes, the station's signature and manifest; every block value left out), puts its
// own block from the file into each proof and hands it to Hypercore's applyProof. Hypercore checks each block's hash against
// the signed tree, so a wrong block, or a hostile peer's proof, is refused, and the core then serves the set like any other.
// Proofs cost about 5% of the audio (laptop, 3 KB blocks; less for the 4 s blocks used on air).
//
// A peer serves proofs only for cores this node already opened (offer), so a peer can't make it open cores for keys it chooses.
import Protomux from 'protomux'
import c from 'compact-encoding'
import b4a from 'b4a'
import { wire } from 'hypercore/lib/messages.js'

export const PROTOCOL = 'brocast/proof/1'
export const BATCH = 64
const TIMEOUT_MS = 10000

const reply = {
  preencode (s, m) { c.uint.preencode(s, m.id); c.array(wire.data).preencode(s, m.proofs) },
  encode (s, m) { c.uint.encode(s, m.id); c.array(wire.data).encode(s, m.proofs) },
  decode (s) { return { id: c.uint.decode(s), proofs: c.array(wire.data).decode(s) } }
}

export class Proofs {
  constructor () {
    this.cores = new Map() // hex key → core this node will prove from
    this.channels = new Set()
    this.pending = new Map()
    this.nextId = 1
    this.served = 0 // proofs sent to peers
  }

  offer (core) { this.cores.set(b4a.toString(core.key, 'hex'), core) }

  attach (conn) {
    const ch = Protomux.from(conn).createChannel({ protocol: PROTOCOL, onclose: () => this.channels.delete(ch) })
    if (!ch) return
    ch.peer = conn.remotePublicKey ? b4a.toString(conn.remotePublicKey, 'hex') : null
    ch.reqMsg = ch.addMessage({ encoding: c.json, onmessage: (m) => this._serve(ch, m).catch(() => {}) })
    ch.resMsg = ch.addMessage({ encoding: reply, onmessage: (m) => { const p = this.pending.get(m.id); if (p) p(m.proofs) } })
    ch.open()
    this.channels.add(ch)
  }

  /** A peer's request: { id, core, from, to, nodes: [per block], upgradeFrom, length }. An empty reply means "can't". */
  async _serve (ch, m) {
    const core = this.cores.get(String(m?.core))
    const from = m.from | 0
    const to = Math.min(m.to | 0, from + BATCH)
    const length = m.length | 0
    const proofs = []
    if (core && from >= 0 && to > from && length >= to && core.length >= length) {
      try {
        for (let i = from; i < to; i++) {
          const upgrade = i === from && Number.isInteger(m.upgradeFrom) && m.upgradeFrom < length ? { start: m.upgradeFrom, length } : null
          const p = await core.proof({ block: { index: i, nodes: (m.nodes?.[i - from] | 0) }, upgrade })
          p.block.value = b4a.alloc(0) // the asker has the audio
          if (upgrade && m.upgradeFrom === 0) p.manifest = core.core.header.manifest
          proofs.push({ request: 0, fork: p.fork, block: p.block, hash: p.hash, seek: p.seek, upgrade: p.upgrade, manifest: p.manifest || null })
        }
      } catch { proofs.length = 0 }
    }
    this.served += proofs.length
    ch.resMsg.send({ id: m.id | 0, proofs })
  }

  _ask (ch, req) {
    const id = this.nextId++
    return new Promise((resolve) => {
      const t = setTimeout(() => { this.pending.delete(id); resolve(null) }, TIMEOUT_MS)
      this.pending.set(id, (proofs) => { clearTimeout(t); this.pending.delete(id); resolve(proofs) })
      ch.reqMsg.send({ ...req, id })
    })
  }

  /**
   * Fills [core] with [blocks] (this phone's own, from its file), proved by any peer that will. Peers are tried in turn;
   * one whose proofs don't fit is dropped for this set.
   * [prefer](channel) ranks peers (the station's seeds first). Returns { ok, imported, proofBytes, why }.
   */
  async import (core, blocks, prefer = () => 0) {
    const key = b4a.toString(core.key, 'hex')
    const bad = new Set()
    let proofBytes = 0
    while (core.contiguousLength < blocks.length) {
      const from = core.contiguousLength
      // Until the signed upgrade has landed this core can't count the nodes a block needs, so the first request is one block.
      const to = Math.min(from + (core.length < blocks.length ? 1 : BATCH), blocks.length)
      const nodes = []
      for (let i = from; i < to; i++) nodes.push(await core.missingNodes(i))
      const req = { core: key, from, to, nodes, upgradeFrom: core.length < blocks.length ? core.length : null, length: blocks.length }
      const peers = [...this.channels].filter((ch) => !bad.has(ch)).sort((a, b) => prefer(b) - prefer(a))
      if (!peers.length) return { ok: false, imported: core.contiguousLength, proofBytes, why: 'no peer proved it' }
      let done = false
      let answered = false
      for (const ch of peers) {
        const proofs = await this._ask(ch, req)
        if (!proofs || proofs.length !== to - from) { if (proofs && proofs.length) bad.add(ch); continue }
        answered = true
        proofBytes += c.encode(c.array(wire.data), proofs).length
        let fits = true
        for (let j = 0; j < proofs.length && fits; j++) {
          proofs[j].block.value = blocks[from + j]
          try { fits = await core.applyProof(proofs[j]) } catch { fits = false }
        }
        if (fits) { done = true; break }
        bad.add(ch) // a hostile peer, or our file isn't this set: the next peer settles which
      }
      if (!done) return { ok: false, imported: core.contiguousLength, proofBytes, why: answered ? 'block ' + core.contiguousLength + ' did not match' : 'no peer proved it' }
    }
    return { ok: true, imported: blocks.length, proofBytes }
  }
}
