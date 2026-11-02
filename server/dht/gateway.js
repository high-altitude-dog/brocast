// The server's identity: listens on an ed25519 keypair over HyperDHT and passes each connection to the stream server.
// Clients need only the public key, so the server can move without anything changing for them.
// The seed is the secret: it lives in IDENTITY_DIR, on a private volume, and must never enter this repo.
import DHT from 'hyperdht'
import crypto from 'node:crypto'
import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'

const dir = process.env.IDENTITY_DIR || '/identity'
const port = Number(process.env.TARGET_PORT || 8765)
const seedFile = path.join(dir, 'seed')

function seed () {
  if (!fs.existsSync(seedFile)) fs.writeFileSync(seedFile, crypto.randomBytes(32), { mode: 0o600, flag: 'wx' })
  const s = fs.readFileSync(seedFile)
  if (s.length !== 32) throw new Error('the seed file must hold 32 bytes')
  return s
}

// Passes data one way, and only ends the other side once everything has been handed over. A plain pipe tears the DHT stream
// down as soon as the local socket closes, which loses whatever is still buffered: megabytes, on a fast server.
function forward (from, to) {
  from.on('data', (d) => {
    if (to.destroyed) return
    if (to.write(d) === false) { from.pause(); to.once('drain', () => from.resume()) }
  })
  from.on('end', () => { if (!to.destroyed) to.end() })
}

const keyPair = DHT.keyPair(seed())
const dht = new DHT()
const server = dht.createServer((remote) => {
  const local = net.connect(port, '127.0.0.1')
  forward(remote, local)
  forward(local, remote)
  // Only errors tear the connection down.
  const fail = () => { remote.destroy(); local.destroy() }
  remote.on('error', fail)
  local.on('error', fail)
})

await server.listen(keyPair)
console.log('public key ' + keyPair.publicKey.toString('hex'))

async function stop () {
  await server.close()
  await dht.destroy()
  process.exit(0)
}
process.on('SIGTERM', stop)
process.on('SIGINT', stop)
