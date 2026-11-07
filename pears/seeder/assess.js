// The seeder's assessment of its machine, and the hard limits that follow (docs/desktop-seeder.md, "Resource safety").
// Limits come from what the machine has, minus headroom kept for its owner and the OS, never from fixed numbers.
// probe() runs at start, every 5 minutes, and on power or disk events. limits() is pure, so the rules are exact.
// Host-side (desktop) code: it uses Node/Bare file system calls, and the engine itself never imports it.
import os from 'node:os'
import fs from 'node:fs'
import path from 'node:path'

const MB = 1048576
const GB = 1024 * MB
const read = (f) => { try { return fs.readFileSync(f, 'utf8').trim() } catch { return null } }

/** What the store's disk really is, following LUKS and LVM down to the physical device (Linux; elsewhere: unknown). */
function device (dir) {
  try {
    const dev = fs.statSync(dir).dev
    const major = Number((BigInt(dev) >> 8n) & 0xfffn | ((BigInt(dev) >> 32n) & ~0xfffn))
    const minor = Number((BigInt(dev) & 0xffn) | ((BigInt(dev) >> 12n) & ~0xffn))
    let p = fs.realpathSync(`/sys/dev/block/${major}:${minor}`)
    for (;;) { // a mapped device (dm-crypt, LVM) has its real device under slaves/
      const slaves = fs.existsSync(path.join(p, 'slaves')) ? fs.readdirSync(path.join(p, 'slaves')) : []
      if (!slaves.length) break
      p = fs.realpathSync(path.join(p, 'slaves', slaves[0]))
    }
    if (fs.existsSync(path.join(p, 'partition'))) p = path.dirname(p)
    return {
      name: path.basename(p),
      rotational: read(path.join(p, 'queue/rotational')) === '1',
      removable: read(path.join(p, 'removable')) === '1',
      usb: p.includes('/usb')
    }
  } catch { return { name: 'unknown', rotational: false, removable: false, usb: false } }
}

function psi (kind) {
  const line = read(`/proc/pressure/${kind}`)
  const m = line && line.match(/avg10=([\d.]+)/)
  return m ? Number(m[1]) : null
}

function onBattery () {
  try {
    const supplies = fs.readdirSync('/sys/class/power_supply')
    const mains = supplies.filter((s) => read(`/sys/class/power_supply/${s}/type`) === 'Mains')
    const bats = supplies.filter((s) => read(`/sys/class/power_supply/${s}/type`) === 'Battery')
    if (!bats.length) return { battery: false, discharging: false, pct: null }
    const online = mains.some((s) => read(`/sys/class/power_supply/${s}/online`) === '1')
    return { battery: true, discharging: !online, pct: Number(read(`/sys/class/power_supply/${bats[0]}/capacity`)) }
  } catch { return { battery: false, discharging: false, pct: null } }
}

export function probe (dir) {
  const meminfo = read('/proc/meminfo') || ''
  const avail = meminfo.match(/MemAvailable:\s+(\d+)/)
  const st = fs.statfsSync(dir)
  return {
    memTotal: os.totalmem(),
    memAvailable: avail ? Number(avail[1]) * 1024 : os.freemem(),
    cores: os.availableParallelism ? os.availableParallelism() : os.cpus().length,
    load1: os.loadavg()[0],
    diskTotal: st.blocks * st.bsize,
    diskFree: st.bavail * st.bsize,
    device: device(dir),
    power: onBattery(),
    pressure: { io: psi('io'), cpu: psi('cpu'), memory: psi('memory') }
  }
}

/** How much of the machine the owner lets the seeder use. Only the share changes; the headroom rules never do. */
export const SHARES = { light: 0.1, normal: 0.25, generous: 0.5 }
/** What a seeder can usefully use at each share: limits are the smaller of this and the machine's share. */
const NEED = {
  light: { connections: 8, cpu: 0.5, cache: 64 * MB },
  normal: { connections: 32, cpu: 1, cache: 256 * MB },
  generous: { connections: 64, cpu: 2, cache: 512 * MB }
}

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v))

/**
 * Hard limits from an assessment. [learned] holds what the seeder has measured while running: per-connection memory,
 * the highest sustained upload seen without the round-trip time rising. Returns limits plus the reasons, for the status page.
 */
export function limits (m, share = 'normal', learned = {}) {
  const why = []
  if (!SHARES[share]) share = 'normal'
  if (m.power.discharging) {
    share = 'light'
    why.push('on battery')
    if (m.power.pct !== null && m.power.pct < 30) return { share, paused: true, why: [`battery ${m.power.pct}%: paused`] }
  }
  const f = SHARES[share]
  const need = NEED[share]
  const perConn = learned.perConnBytes || 6 * MB // until measured
  const base = learned.baseBytes || 96 * MB

  // Memory: a share of the machine, but always leave the owner and the OS at least 15% of RAM, and never under 1 GB.
  const reserve = Math.max(1 * GB, 0.15 * m.memTotal)
  const wanted = base + need.connections * perConn + need.cache
  const memory = clamp(Math.min(wanted, f * m.memTotal, m.memAvailable - reserve), 128 * MB, 4 * GB)
  if (m.memAvailable - reserve < 128 * MB) why.push('memory tight')

  // CPU: core-equivalents the seeder may use. Halved if the machine is already busy.
  let cpu = Math.max(0.25, Math.min(need.cpu, f * m.cores))
  if (m.load1 > 0.8 * m.cores) { cpu /= 2; why.push('CPU busy') }

  // Disk space: a share of what's free above a floor, so the owner never runs out because of us.
  const floor = Math.max(5 * GB, 0.1 * m.diskTotal)
  const store = Math.max(0, 2 * f * (m.diskFree - floor))
  if (m.diskFree <= floor) why.push('disk nearly full')

  // Disk writes: by device class, because flash on USB overheats and stalls (this laptop's own drive does).
  const d = m.device
  const cls = d.usb || d.removable ? 'usb-flash' : d.rotational ? 'hdd' : 'ssd'
  const writes = { 'usb-flash': 10, hdd: 50, ssd: 200 }[cls] * MB * 2 * f
  const syncEverySec = cls === 'usb-flash' ? 10 : cls === 'hdd' ? 5 : 2
  if (cls === 'usb-flash') why.push('USB flash drive: gentle writes')

  // Connections: what the memory limit can hold, from the measured cost of one (6 MB until measured).
  const connections = clamp(Math.min(need.connections, Math.floor((memory - base - need.cache) / perConn)), 4, 64)

  // Upload: half the highest sustained rate seen without latency rising, so the household's internet stays quick.
  const upload = learned.uplinkBps ? 0.5 * learned.uplinkBps : 250000 // 2 Mbps until measured
  return { share, paused: false, cls, memory, cpu, store, writes, syncEverySec, connections, upload, reserve, floor, why }
}
