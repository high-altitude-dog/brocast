// Step 1: one line per phone from results/<serial>/{run.jsonl,host.json}, pulled by android-test/phone.sh.
// Usage: node tools/phone-report.js android-test/results/<serial>...
import fs from 'node:fs'
import path from 'node:path'

const pct = (a, q) => { const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(q * s.length))] }
const mb = (b) => (b / 1048576).toFixed(2)
for (const dir of process.argv.slice(2)) {
  const ev = fs.readFileSync(path.join(dir, 'run.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l))
  const host = JSON.parse(fs.readFileSync(path.join(dir, 'host.json'), 'utf8'))
  const end = ev.find((e) => e.ev === 'end')
  const got = ev.filter((e) => e.ev === 'got')
  const blocks = ev.filter((e) => e.ev === 'block')
  const ups = ev.filter((e) => e.ev === 'upload')
  const cat = ev.find((e) => e.ev === 'catalog')
  const fromL = blocks.filter((e) => !e.station).reduce((a, e) => a + e.bytes, 0)
  const all = blocks.reduce((a, e) => a + e.bytes, 0)
  const ms = got.map((e) => e.ms)
  // Wire received while fetching: the end line is after sharing too, so overhead is also given to the last block.
  const w = end.wire
  console.log(`${host.model} (${path.basename(dir)})`)
  console.log(`  catalog at ${cat?.t} ms; first block ${end.firstMs} ms; all ${end.held}/${end.total} blocks ${end.allMs} ms; sets built ${end.built}`)
  console.log(`  per block ms p50/p95/max ${pct(ms, 0.5)}/${pct(ms, 0.95)}/${pct(ms, 1)}; from other phone ${all ? ((fromL / all) * 100).toFixed(0) : 0}%`)
  console.log(`  audio ${mb(end.audio)} MB; wire rx ${mb(w.rx)} MB (overhead ${((w.rx / end.audio - 1) * 100).toFixed(1)}%), tx ${mb(w.tx)} MB; shared ${mb(ups.reduce((a, e) => a + e.bytes, 0))} MB`)
  console.log(`  data msgs ${end.stats.dataRx} for ${end.held} blocks (${(((end.stats.dataRx - end.held) / end.held) * 100).toFixed(1)}% extra); requests ${end.stats.requestTx}, cancels ${end.stats.cancelTx}; download events ${blocks.length}`)
  console.log(`  packets rx ${w.packetsRx} (${(w.rx / w.packetsRx).toFixed(0)} B avg); my retransmits ${w.retransmits}`)
  const st = ev.find((e) => e.ev === 'start')
  if (st?.publicDht) {
    const n = end.nat || {}
    console.log(`  network ${st.network}; public DHT; LAN shortcut ${st.lan ? 'on' : 'off'}; relay ${st.relay ? 'set' : 'none'}`)
    console.log(`  my NAT: firewalled ${n.firewalled}, randomized ${n.randomized}; punches ${JSON.stringify(n.punches)}; relaying ${JSON.stringify(n.relaying)}`)
    for (const c of ev.filter((e) => e.ev === 'conn')) {
      console.log(`  conn ${c.peer} ${c.side} at ${c.t} ms: ${c.outcome === 'open' ? (c.path === 'unknown' || c.path === 'accepted' ? 'punched (round 1; old log)' : c.path) : 'FAILED ' + c.outcome}${c.ms !== null ? ' in ' + c.ms + ' ms' : ''} via ${c.remote}${c.punches ? ' ' + JSON.stringify(c.punches) : ''}`)
    }
  }
  console.log(`  CPU ${host.cpuMsToDone} ms to done (${host.doneMs} ms wall), ${host.cpuMsTotal} ms total; plugged ${host.plugged} (battery figures invalid while charging)`)
}
