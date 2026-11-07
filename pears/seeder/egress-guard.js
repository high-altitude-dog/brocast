// The cloud egress cap (user, 7 Nov: 1 GB for now; shut it down and alert the user if it's exceeded).
// Counts bytes sent on the wire, which is what cloud platforms bill, across every connection. check() must be called on
// every block upload, not on a timer: a fast link sends tens of MB a second, and polling once a second overshot a 10 MB
// test cap by 23%. The count persists across restarts in a state file (saved at most once a second, and at once on a
// warning or a trip). It warns at 50, 80 and 90%, and trips at 95% of the cap, so packet headers the count can't
// see never take the bill past the cap. Once tripped, it stays tripped: a restart, or a container restart policy, can't
// bring the seed back. Only the user clears it (delete the state file, or raise the cap).
import fs from 'node:fs'

const WARN = [0.5, 0.8, 0.9]
export const TRIP_AT = 0.95

const month = (d = new Date()) => d.toISOString().slice(0, 7) // calendar month, UTC

export class EgressGuard {
  /** [sent]() returns this process's total bytes sent so far. [alert](level, text) tells the user. [shutdown]() stops serving. */
  constructor ({ capBytes, stateFile, sent, alert, shutdown, now = () => new Date() }) {
    this.cap = capBytes
    this.file = stateFile
    this.sent = sent
    this.alert = alert
    this.shutdown = shutdown
    this.now = now
    this.state = this._load()
    this.base = 0 // this process's sent bytes when the current month began
    this.timer = null
  }

  _load () {
    try { return JSON.parse(fs.readFileSync(this.file, 'utf8')) } catch { return { month: month(this.now()), bytes: 0, warned: [], tripped: null } }
  }

  _save () {
    const tmp = this.file + '.tmp'
    fs.writeFileSync(tmp, JSON.stringify(this.state))
    fs.renameSync(tmp, this.file)
  }

  /** Throws if the cap was tripped, so the seed refuses to start. Then checks once a second. */
  start () {
    if (this.state.tripped) {
      const t = this.state.tripped
      this.alert('stopped', `Cloud seed stays off: the egress cap tripped at ${mb(t.bytes)} of ${mb(this.cap)} on ${t.at}. Delete ${this.file} or raise the cap to restart.`)
      throw new Error('EGRESS_CAP_TRIPPED')
    }
    this.base = this.sent()
    this.lastSave = 0
    this.timer = setInterval(() => this.check(), 1000) // also catches traffic that isn't block uploads
    if (this.timer.unref) this.timer.unref()
  }

  /** Call on every block upload, and it runs every second too. Synchronous, so a trip cuts traffic at once. */
  check () {
    if (this.state.tripped) return
    const m = month(this.now())
    const total = this.sent()
    if (m !== this.state.month) { // a new month: the count starts again, but a trip never clears by itself
      this.state = { month: m, bytes: 0, warned: [], tripped: null }
      this.base = total
    }
    const bytes = this.state.bytes + (total - this.base)
    this.base = total
    this.state.bytes = bytes
    for (const w of WARN) {
      if (bytes >= w * this.cap && !this.state.warned.includes(w)) {
        this.state.warned.push(w)
        this._save()
        this.alert('warning', `Cloud seed has sent ${mb(bytes)} this month: ${Math.round(w * 100)}% of the ${mb(this.cap)} cap.`)
      }
    }
    if (bytes >= TRIP_AT * this.cap) {
      this.state.tripped = { at: this.now().toISOString(), bytes }
      this._save()
      clearInterval(this.timer)
      this.shutdown() // first: the caller cuts every connection synchronously, before anything else is sent
      this.alert('tripped', `Cloud seed SHUT DOWN: sent ${mb(bytes)} this month, at the ${mb(this.cap)} cap. It stays off until you restart it.`)
      return
    }
    const t = Date.now()
    if (t - this.lastSave >= 1000) { this.lastSave = t; this._save() }
  }

  stop () { clearInterval(this.timer); this._save() }
}

const mb = (b) => (b / 1048576).toFixed(1) + ' MB'

/**
 * The default alert: a loud line on stderr, an alert file beside the state file for dashboards and monitors, and a
 * webhook if the user has set one (for example an ntfy topic or a mail gateway). Nothing leaves the machine unless set.
 */
export function defaultAlert ({ alertFile, webhook } = {}) {
  return (level, text) => {
    const line = `${new Date().toISOString()} EGRESS ${level.toUpperCase()} ${text}`
    process.stderr.write('\n!!! ' + line + '\n')
    if (alertFile) fs.appendFileSync(alertFile, line + '\n')
    if (webhook) fetch(webhook, { method: 'POST', body: text }).catch(() => {})
  }
}
