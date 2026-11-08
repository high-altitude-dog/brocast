// Step 1, the laptop side: a small HyperDHT of its own on the Wi-Fi (nothing reaches the public DHT), and the step 0
// station seeding the test library on it. Phones are given the bootstrap address and the station key, nothing else.
// Usage: node tools/lan.js [--host <laptop LAN IP>] [--port 49737] [--minutes 10] [--block-seconds 4]
// Everything stops itself after --minutes (user, 7 Nov). Port 8766 is never used.
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import createTestnet from 'hyperdht/testnet.js'
import { makeLibrary } from './library.js'

const arg = (k, d) => { const i = process.argv.indexOf('--' + k); return i > 0 ? process.argv[i + 1] : d }
const lanIp = () => Object.values(os.networkInterfaces()).flat().find((a) => a.family === 'IPv4' && !a.internal)?.address
const host = arg('host', lanIp())
const port = Number(arg('port', 49737))
const minutes = Number(arg('minutes', 10))
const bs = arg('block-seconds', '4')
if (port === 8766) throw new Error('port 8766 stays closed')

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..')
const work = path.join(root, 'work')
const library = path.join(work, 'audio')
const dir = path.join(work, 'lan-station')
const alerts = path.join(dir, 'egress-alerts.log')
makeLibrary(library)
fs.mkdirSync(dir, { recursive: true })

const reportAlerts = (when) => {
  const t = fs.existsSync(alerts) ? fs.readFileSync(alerts, 'utf8').trim() : ''
  console.log(`EGRESS ALERTS (${when}): ${t || 'none'}`)
}
reportAlerts('start')

const testnet = await createTestnet(3, { host, port })
console.log('BOOTSTRAP', `${host}:${port}`)
const station = spawn('node', [path.join(root, 'tools/station.js'), '--dir', dir, '--seed-file', path.join(work, 'lan-station-seed'),
  '--library', library, '--block-seconds', bs, '--minutes', String(minutes)],
{ env: { ...process.env, BOOTSTRAP: JSON.stringify(testnet.bootstrap) }, stdio: ['ignore', 'pipe', 'inherit'] })
station.stdout.on('data', (d) => process.stdout.write(d))

async function stop (why) {
  console.log('STOPPING', why)
  if (station.exitCode === null) {
    station.kill('SIGTERM')
    await new Promise((resolve) => station.on('exit', resolve))
  }
  await testnet.destroy()
  reportAlerts('stop')
  process.exit(0)
}
station.on('exit', () => stop('station exited'))
setTimeout(() => stop(`AUTOSTOP after ${minutes} min`), minutes * 60e3 + 5000)
process.on('SIGINT', () => stop('SIGINT'))
process.on('SIGTERM', () => stop('SIGTERM'))
