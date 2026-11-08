// Step 2: the forced-relay path on a local HyperDHT testnet (nothing reaches the public DHT). A blind relay, a seeder
// with one 64-block core and a fetcher that must reach it only through the relay. Prints each connection record and
// the relay's pairing stats. Usage: node tools/relay-local.js [--public]
// --public uses the public HyperDHT instead (about 4 MB over the home line; the localhost testnet can report a
// connection open that the server never sees, so the relay test needs real addresses).
import createTestnet from 'hyperdht/testnet.js'
import crypto from 'hypercore-crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { Server as RelayServer } from 'blind-relay'
import { Mesh } from '../engine/mesh.js'

const pub = process.argv.includes('--public')
const testnet = pub ? { bootstrap: undefined, destroy: async () => {} } : await createTestnet(3)
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'relay-local-'))
const { default: DHT } = await import('hyperdht')
const rdht = new DHT({ bootstrap: testnet.bootstrap })
await rdht.ready()
const relay = new RelayServer({ createStream: (opts) => rdht.createRawStream({ ...opts, framed: true }) })
const rkp = crypto.keyPair()
const rserver = rdht.createServer((socket) => { socket.on('error', () => {}); try { relay.accept(socket, { id: socket.remotePublicKey }).on('error', () => {}) } catch (e) { console.log('accept threw', e.stack.split('\n').slice(0, 4).join(' / ')) } })
await rserver.listen(rkp)
console.log('relay key', Buffer.from(rkp.publicKey).toString('hex').slice(0, 8))
rserver.on('connection', () => console.log('relay got a connection'))

const log = (who) => (r) => console.log(who, JSON.stringify(r))
const a = new Mesh({ dir: path.join(tmp, 'a'), seed: crypto.randomBytes(32), bootstrap: testnet.bootstrap, relaySketch: false, lan: false, relayThrough: rkp.publicKey, onconn: log('A') })
const core = a.store.get({ name: 'x' })
await core.ready()
for (let i = 0; i < 64; i++) await core.append(crypto.randomBytes(60000))
a.swarm.join(core.discoveryKey, { server: true, client: false })
await a.swarm.flush()

const b = new Mesh({ dir: path.join(tmp, 'b'), seed: crypto.randomBytes(32), bootstrap: testnet.bootstrap, relaySketch: false, lan: false, relayThrough: rkp.publicKey, forceRelay: () => true, onconn: log('B') })
const bc = b.store.get({ key: core.key })
await bc.ready()
b.swarm.join(core.discoveryKey, { server: false, client: true })
const t = Date.now()
await Promise.race([bc.download({ start: 0, end: 64 }).done(), new Promise((resolve, reject) => setTimeout(() => reject(new Error('timeout 60 s')), 60000))])
  .then(() => console.log('fetched 64 blocks in', Date.now() - t, 'ms'), (e) => console.log('FAILED', e.message))
console.log('relay sessions', JSON.stringify(relay.stats.sessions)); console.log('relay', JSON.stringify(relay.stats.pairings), JSON.stringify(relay.stats.streams), 'A relaying', JSON.stringify(a.swarm.dht.stats.relaying))
await b.close(); await a.close(); await rserver.close(); await relay.close(); await rdht.destroy(); await testnet.destroy()
fs.rmSync(tmp, { recursive: true, force: true })
process.exit(0)
