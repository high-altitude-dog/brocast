// Co-listener countries (hand-off 38): an IPv4 address to its ISO country code, from the table tools/country-table.js builds out of
// DB-IP's IP to Country Lite (CC BY 4.0). Looked up on the phone only; no address goes to an outside service.
import fs from '#fs'
import b4a from 'b4a'

export class Countries {
  /** [buf] is the whole table file. Throws if it isn't one. */
  constructor (buf) {
    if (b4a.toString(buf.subarray(0, 4), 'latin1') !== 'BSCC' || buf.readUInt32BE(4) !== 1) throw new Error('not a country table')
    this.n = buf.readUInt32BE(8)
    this.source = b4a.toString(buf.subarray(12, 20), 'latin1').replace(/\0+$/, '')
    this.buf = buf
  }

  static load (file) { return new Countries(fs.readFileSync(file)) }

  /** The two-letter code for a dotted IPv4 address, or null for no country, a private address or anything else. */
  lookup (host) {
    const m = /^(?:::ffff:)?(\d+)\.(\d+)\.(\d+)\.(\d+)$/.exec(host || '')
    if (!m) return null
    const ip = ((+m[1] * 256 + +m[2]) * 256 + +m[3]) * 256 + +m[4]
    let lo = 0
    let hi = this.n - 1 // the last range whose start is at or below ip
    while (lo < hi) {
      const mid = (lo + hi + 1) >>> 1
      if (this.buf.readUInt32BE(20 + mid * 4) <= ip) lo = mid
      else hi = mid - 1
    }
    const at = 20 + this.n * 4 + lo * 2
    const cc = String.fromCharCode(this.buf[at], this.buf[at + 1])
    return cc === 'ZZ' ? null : cc
  }
}
