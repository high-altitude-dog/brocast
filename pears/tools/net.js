// Step 2, the laptop side: the step 0 station on the public HyperDHT (no BOOTSTRAP), seeding the test library from
// the home network. Phones are given only the station key. Reads the egress alert log at start and stop.
// Usage: node tools/net.js [--minutes 10] [--block-seconds 4]. The station stops itself after --minutes (user, 7 Nov).
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { makeLibrary } from './library.js'

const arg = (k, d) => { const i = process.argv.indexOf('--' + k); return i > 0 ? process.argv[i + 1] : d }
const minutes = Number(arg('minutes', 10))
const bs = arg('block-seconds', '4')
const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..')
const work = path.join(root, 'work')
const library = path.join(work, 'audio')
const dir = path.join(work, 'net-station')
const alerts = path.join(dir, 'egress-alerts.log')
makeLibrary(library)
fs.mkdirSync(dir, { recursive: true })

const reportAlerts = (when) => {
  const t = fs.existsSync(alerts) ? fs.readFileSync(alerts, 'utf8').trim() : ''
  console.log(`EGRESS ALERTS (${when}): ${t || 'none'}`)
}
reportAlerts('start')
const env = { ...process.env }
delete env.BOOTSTRAP
const station = spawn('node', [path.join(root, 'tools/station.js'), '--dir', dir, '--seed-file', path.join(work, 'net-station-seed'),
  '--library', library, '--block-seconds', bs, '--minutes', String(minutes)], { env, stdio: ['ignore', 'inherit', 'inherit'] })
const stop = async (why) => {
  console.log('STOPPING', why)
  if (station.exitCode === null) { station.kill('SIGTERM'); await new Promise((resolve) => station.on('exit', resolve)) }
  reportAlerts('stop')
  process.exit(0)
}
station.on('exit', () => stop('station exited'))
setTimeout(() => stop(`AUTOSTOP after ${minutes} min`), minutes * 60e3 + 5000)
process.on('SIGINT', () => stop('SIGINT'))
process.on('SIGTERM', () => stop('SIGTERM'))
