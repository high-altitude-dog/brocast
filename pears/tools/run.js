// Step 0 runner: a local HyperDHT testnet (nothing reaches the public DHT), one station, and listeners per scenario.
// Usage: node tools/run.js [scenario ...]   Results: work/<scenario>/*.jsonl, then node tools/report.js
import { spawn, execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import createTestnet from 'hyperdht/testnet.js'

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..')
const work = path.join(root, 'work')
const library = path.join(work, 'audio')
fs.mkdirSync(library, { recursive: true })

// Three 6-minute sets of stereo pink noise, at the station's settings: 160 kbps VBR Opus, 20 ms frames.
// Made here and only ever sent as Opus blocks, so no raw audio crosses any network.
for (const n of [1, 2, 3]) {
  const f = path.join(library, `set${n}.opus`)
  if (fs.existsSync(f)) continue
  execFileSync('nice', ['-n', '19', 'ffmpeg', '-nostdin', '-v', 'error', '-y',
    '-f', 'lavfi', '-i', `anoisesrc=color=pink:amplitude=0.25:duration=360:seed=${n}`,
    '-f', 'lavfi', '-i', `anoisesrc=color=pink:amplitude=0.25:duration=360:seed=${n + 10}`,
    '-filter_complex', 'amerge=inputs=2', '-ar', '48000', '-c:a', 'libopus', '-b:a', '160k', '-vbr', 'on',
    '-frame_duration', '20', '-page_duration', '1000000', '-f', 'ogg', f])
}

const L = (name, o = {}) => ({ name, mode: 'share', profile: 'generous', delay: 0, until: 'all', wall: 120, speed: 1, wmScale: 1, ...o })
const SCENARIOS = {
  // Block length: one listener takes the whole 18-minute library as fast as it can.
  ...Object.fromEntries([1, 2, 4, 8].map((bs) => [`block-${bs}s`, { bs, nodes: [L('a')] }])),
  // A small swarm, joining one at a time, every listener sharing.
  ...Object.fromEntries([1, 4].map((bs) => [`swarm-${bs}s`, { bs, nodes: [0, 1, 2, 3].map((i) => L('l' + i, { delay: i * 4000, until: 'wall', wall: 40 })) }])),
  // Receive-only and Privacy mode alongside two full sharers.
  modes: { bs: 1, nodes: [L('s0', { until: 'wall', wall: 60 }), L('s1', { delay: 2000, until: 'wall', wall: 58 }), L('recv', { mode: 'receive', delay: 20000, wall: 40 }), L('priv', { mode: 'privacy', delay: 20000, wall: 40 })] },
  // Watermark bursts per profile: playhead at 20x, watermarks at a tenth, 18 minutes of audio in 54 s.
  ...Object.fromEntries(['light', 'balanced', 'generous'].map((pr) => [`profile-${pr}`, { bs: 1, nodes: [L('a', { profile: pr, until: 'play', speed: 20, wmScale: 0.1, wall: 70 })] }]))
}

const testnet = await createTestnet(4)
const env = { ...process.env, BOOTSTRAP: JSON.stringify(testnet.bootstrap) }
const wanted = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(SCENARIOS)

for (const name of wanted) {
  const sc = SCENARIOS[name]
  const dir = path.join(work, name)
  fs.rmSync(dir, { recursive: true, force: true })
  fs.mkdirSync(dir, { recursive: true })
  const station = spawn('node', [path.join(root, 'tools/station.js'), '--dir', path.join(work, 'station'), '--seed-file', path.join(work, 'station-seed'), '--library', library, '--block-seconds', String(sc.bs)], { env })
  let stationOut = ''
  station.stderr.on('data', (d) => process.stderr.write('[station] ' + d))
  const [catalog, key] = await new Promise((resolve, reject) => {
    station.stdout.on('data', (d) => {
      stationOut += d
      const m = stationOut.match(/READY (\w+) (\w+)/)
      if (m) resolve([m[1], m[2]])
    })
    station.on('exit', (code) => reject(new Error('station exited ' + code)))
  })
  const runs = sc.nodes.map((n) => new Promise((resolve) => setTimeout(() => {
    const child = spawn('node', [path.join(root, 'tools/listen.js'), '--dir', path.join(dir, n.name + '.store'), '--catalog', catalog, '--station', key,
      '--out', path.join(dir, n.name + '.jsonl'), '--mode', n.mode, '--profile', n.profile, '--speed', String(n.speed), '--wm-scale', String(n.wmScale),
      '--wall', String(n.wall), '--until', n.until], { env, stdio: ['ignore', 'inherit', 'inherit'] })
    child.on('exit', resolve)
  }, n.delay)))
  await Promise.all(runs)
  station.kill('SIGTERM')
  await new Promise((resolve) => station.on('exit', resolve))
  const s = stationOut.match(/SUMMARY (.*)/)
  if (s) fs.writeFileSync(path.join(dir, 'station.json'), s[1])
  for (const f of fs.readdirSync(dir)) if (f.endsWith('.store') || f.endsWith('.music')) fs.rmSync(path.join(dir, f), { recursive: true, force: true })
  console.log('done', name)
}
await testnet.destroy()
