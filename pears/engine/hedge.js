// Duplicate blocks (steps 1 and 2: 55 to 68% extra on a phone with two sources). Hypercore 11's replicator "hotswaps":
// every block in flight is a candidate, and any peer with fewer than max(16, maxInflight / 2) requests in flight asks
// for it too. Our fetch keeps about 4 blocks in flight, so a second peer is never busy and re-requests nearly every
// block; the loser is cancelled, but a 4 s block (~72 KB) is usually already on the wire. Read from hypercore 11.40's
// lib/replicator.js and lib/hotswap-queue.js; these are private methods, so pin the version and re-check on upgrade.
//   mode 'default': Hypercore as shipped.   'off': never hotswap (a block on a stalled peer waits for our timeout).
//   'delayed' (default here): hedge only blocks that have been in flight for afterMs, so a stalled peer still loses them.

export function hedge (core, { mode = 'delayed', afterMs = 1000 } = {}) {
  const r = core.replicator
  if (!r || mode === 'default') return
  if (mode === 'off') { r._updateHotswap = () => {}; return }
  const q = r.hotswaps
  const add = q.add.bind(q)
  q.add = (b) => { if (b.inflight.length === 1) b.firstSentAt = Date.now(); add(b) }
  r._updateHotswap = (peer) => {
    const max = peer.getMaxHotswapInflight()
    if (!peer.isActive() || peer.inflight >= max) return
    const now = Date.now()
    for (const b of q.pick(peer)) {
      if (now - (b.firstSentAt ?? now) < afterMs) continue
      if (peer._requestBlock(b) === false) continue
      peer.stats.hotswaps++
      r.stats.hotswaps++
      if (peer.inflight >= max) break
    }
  }
  // Hotswaps are otherwise checked only when a message arrives, so look again while blocks are in flight.
  const t = setInterval(() => {
    if (r.peers.length < 2 || r._blocks.isEmpty()) return
    for (const peer of r.peers) r._updateHotswap(peer)
  }, Math.max(200, afterMs / 4))
  t.unref?.()
  core.once('close', () => clearInterval(t))
}
