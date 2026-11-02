// A test client: serves the remote server on a local port, found only by its public key.
//     node connect.js <public key in hex> [local port]
// Then, for example: curl http://127.0.0.1:8770/library.json
import DHT from 'hyperdht'
import net from 'node:net'

const key = Buffer.from(process.argv[2] || '', 'hex')
if (key.length !== 32) { console.error('usage: node connect.js <64 hex characters> [port]'); process.exit(1) }
const port = Number(process.argv[3] || 8770)
// Passes data one way, and only ends the other side once everything has been handed over. A plain pipe tears the DHT stream
// down as soon as the local socket closes, which loses whatever is still buffered: megabytes, on a fast server.
function forward (from, to) {
  from.on('data', (d) => {
    if (to.destroyed) return
    if (to.write(d) === false) { from.pause(); to.once('drain', () => from.resume()) }
  })
  from.on('end', () => { if (!to.destroyed) to.end() })
}

const dht = new DHT()

net.createServer((local) => {
  const started = Date.now()
  const remote = dht.connect(key)
  remote.once('open', () => console.log(`connected in ${Date.now() - started} ms, to ${remote.rawStream?.remoteHost ?? 'peer'}, ${remote.relay ? 'relayed' : 'direct'}`))
  forward(remote, local)
  forward(local, remote)
  const fail = (e) => { console.error(String(e.message || e)); local.destroy(); remote.destroy() }
  remote.on('error', fail)
  local.on('error', fail)
}).listen(port, '127.0.0.1', () => console.log('listening on 127.0.0.1:' + port))
