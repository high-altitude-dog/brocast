// Builds the phone's IPv4-to-country table (hand-off 38) from DB-IP's free "IP to Country Lite" CSV (CC BY 4.0, https://db-ip.com):
//   curl -LO https://download.db-ip.com/free/dbip-country-lite-YYYY-MM.csv.gz
//   node tools/country-table.js dbip-country-lite-YYYY-MM.csv.gz ../android/app/src/main/assets/countries-v4.bin
// HyperDHT connections are IPv4 only, so IPv6 rows are dropped. Neighbouring ranges with the same country are merged.
// Format (engine/countries.js reads it): 'BSCC', uint32 version 1, uint32 count, the YYYY-MM source label in 8 bytes,
// then count uint32 range starts (big-endian, ascending), then count 2-byte ASCII codes. Each range runs to the next start;
// 'ZZ' is no country (reserved or unassigned).
import fs from 'fs'
import zlib from 'zlib'

const [src, out] = process.argv.slice(2)
if (!src || !out) { console.error('usage: node tools/country-table.js <dbip-country-lite.csv[.gz]> <out.bin>'); process.exit(1) }
const label = (src.match(/(\d{4}-\d{2})/) || [])[1]
if (!label) { console.error('the file name must carry its YYYY-MM release'); process.exit(1) }
let text = fs.readFileSync(src)
if (src.endsWith('.gz')) text = zlib.gunzipSync(text)

const ip = (s) => s.split('.').reduce((a, o) => a * 256 + Number(o), 0)
const starts = []
const codes = []
let next = 0
for (const line of text.toString('latin1').split('\n')) {
  const [a, b, cc] = line.trim().split(',')
  if (!cc || a.includes(':')) continue
  const from = ip(a)
  const to = ip(b)
  if (from > next) { starts.push(next); codes.push('ZZ') } // a gap in the source counts as no country
  if (codes[codes.length - 1] !== cc) { starts.push(from); codes.push(cc) }
  next = to + 1
}
if (next <= 0xffffffff) { starts.push(next); codes.push('ZZ') }

const buf = Buffer.alloc(20 + starts.length * 6)
buf.write('BSCC', 0, 'latin1')
buf.writeUInt32BE(1, 4)
buf.writeUInt32BE(starts.length, 8)
buf.write(label, 12, 'latin1')
starts.forEach((s, i) => buf.writeUInt32BE(s, 20 + i * 4))
codes.forEach((c, i) => buf.write(c, 20 + starts.length * 4 + i * 2, 'latin1'))
fs.writeFileSync(out, buf)
console.log(`${starts.length} ranges from ${label}, ${buf.length} bytes → ${out}`)
