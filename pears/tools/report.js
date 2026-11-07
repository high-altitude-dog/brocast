// Summarises work/<scenario>/*.jsonl into one table per scenario.
import fs from 'node:fs'
import path from 'node:path'

const work = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..', 'work')
const pct = (a, q) => { if (!a.length) return '-'; const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(q * s.length))] }
const mb = (b) => (b / 1048576).toFixed(1)

for (const sc of fs.readdirSync(work).filter((d) => fs.existsSync(path.join(work, d, 'station.json'))).sort()) {
  const st = JSON.parse(fs.readFileSync(path.join(work, sc, 'station.json')))
  console.log(`\n## ${sc}  (station sent ${mb(st.uploaded)} MB)`)
  console.log('node mode profile | first ms | all ms | MB audio | wire overhead | from listeners | dup blocks | MB shared | req | p50/p95/max ms | bursts | late | conns | RSS MB | CPU ms | sketch')
  for (const f of fs.readdirSync(path.join(work, sc)).filter((x) => x.endsWith('.jsonl')).sort()) {
    const ev = fs.readFileSync(path.join(work, sc, f), 'utf8').trim().split('\n').map((l) => JSON.parse(l))
    const start = ev.find((e) => e.ev === 'start')
    const end = ev.find((e) => e.ev === 'end')
    if (!end) { console.log(f, 'no end line'); continue }
    const got = ev.filter((e) => e.ev === 'got')
    const blocks = ev.filter((e) => e.ev === 'block')
    const fromL = blocks.filter((e) => !e.station).reduce((a, e) => a + e.bytes, 0)
    const all = blocks.reduce((a, e) => a + e.bytes, 0)
    const up = ev.filter((e) => e.ev === 'upload').reduce((a, e) => a + e.bytes, 0)
    const ms = got.map((e) => e.ms)
    console.log([
      `${f.replace('.jsonl', '')} ${start.mode} ${start.profile}`,
      got[0]?.t ?? '-', end.why === 'all' ? end.t : '-', mb(end.audio),
      end.audio ? ((end.wire.rx / end.audio - 1) * 100).toFixed(1) + '%' : '-',
      all ? ((fromL / all) * 100).toFixed(0) + '%' : '-', all ? (((blocks.length - got.length) / got.length) * 100).toFixed(0) + '%' : '-', mb(up), got.length,
      `${pct(ms, 0.5)}/${pct(ms, 0.95)}/${pct(ms, 1)}`, end.bursts, end.late, end.conns.length, end.rssMB, end.cpuMs, end.sketch
    ].join(' | '))
  }
}
