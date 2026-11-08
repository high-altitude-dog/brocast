// Step 2: a blind relay on the public HyperDHT, run from the laptop. It forwards encrypted UDX packets between two
// peers that name it (Hyperswarm/HyperDHT `relayThrough`) and can't read them. A relay on one peer's own network isn't
// the real deployment; this only proves the relay path works on the phones. Prints `RELAY <public key>`, then its
// stats every 5 s while anything is active, and a summary at exit. Stops itself after --minutes (default 10).
// Usage: node tools/relay.js [--minutes 10]
import fs from 'node:fs'
import path from 'node:path'
import DHT from 'hyperdht'
import crypto from 'hypercore-crypto'
import { Server as RelayServer } from 'blind-relay'

const arg = (k, d) => { const i = process.argv.indexOf('--' + k); return i > 0 ? process.argv[i + 1] : d }
const minutes = Number(arg('minutes', 10))
const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..')
const seedFile = path.join(root, 'work', 'relay-seed') // a stable key across restarts; generated here, never in git
fs.mkdirSync(path.dirname(seedFile), { recursive: true })
if (!fs.existsSync(seedFile)) fs.writeFileSync(seedFile, crypto.randomBytes(32), { mode: 0o600, flag: 'wx' })
const keyPair = crypto.keyPair(fs.readFileSync(seedFile))

const dht = new DHT()
const relay = new RelayServer({ createStream: (opts) => dht.createRawStream({ ...opts, framed: true }) })
let relayed = 0
const server = dht.createServer((socket) => {
  socket.on('error', () => {})
  const session = relay.accept(socket, { id: socket.remotePublicKey })
  session.on('error', () => {})
  socket.on('close', () => { relayed += socket.rawStream?.bytesReceived || 0 })
})
await server.listen(keyPair)
console.log('RELAY', b4hex(keyPair.publicKey))
const show = () => console.log('STATS', JSON.stringify({ pairings: relay.stats.pairings, streams: relay.stats.streams, sessions: relay.stats.sessions }))
const tick = setInterval(() => { if (relay.stats.sessions.active || relay.stats.pairings.active) show() }, 5000)

async function stop (why) {
  clearInterval(tick)
  show()
  console.log('STOPPING', why, 'control bytes in', relayed)
  await relay.close().catch(() => {})
  await server.close().catch(() => {})
  await dht.destroy()
  process.exit(0)
}
setTimeout(() => stop(`AUTOSTOP after ${minutes} min`), minutes * 60e3)
process.on('SIGINT', () => stop('SIGINT'))
process.on('SIGTERM', () => stop('SIGTERM'))
function b4hex (b) { return Buffer.from(b).toString('hex') }
