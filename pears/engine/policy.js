// The resource profile (design note §6). A profile only changes how blocks are fetched and shared, never timing.
// Watermarks are in seconds of audio ahead of the playhead: refill when below low, up to high.
//
// Sharing is set per network (user, 7 Nov). Wi-Fi is usually free, so sharing there is generous. Mobile data is the
// listener's paid allowance, so sharing on it is off in every preset and only ever opt-in, with its own daily cap.
// "Mobile" means any metered network, as Android reports it: that includes phone hotspots and paid Wi-Fi.

export const PRESETS = {
  light: {
    low: 120, high: 900, maxPeers: 4, announce: false, ramMB: 24, allowanceMB: 1024,
    wifi: { share: 'charging', whilePlaying: true, uploadKbps: 1000 },
    mobile: { downloadCapMB: 100, share: false, shareCapMB: 0 }
  },
  balanced: {
    low: 300, high: 7200, maxPeers: 8, announce: true, ramMB: 64, allowanceMB: 4096,
    wifi: { share: 'battery-ok', whilePlaying: true, uploadKbps: 4000 },
    mobile: { downloadCapMB: 500, share: false, shareCapMB: 0 }
  },
  generous: {
    low: 1800, high: 86400, maxPeers: 24, announce: true, ramMB: 192, allowanceMB: 10240,
    wifi: { share: 'always', whilePlaying: true, uploadKbps: Infinity },
    mobile: { downloadCapMB: Infinity, share: false, shareCapMB: 0 }
  }
}

/** Hard floors no setting can go below, so a profile can never starve playback. */
export const FLOORS = { low: 30, maxPeers: 1, batteryShare: 30 }

export function profile (name, overrides = {}) {
  const base = PRESETS[name]
  if (!base) throw new Error('unknown profile ' + name)
  const p = { name, ...base, ...overrides, wifi: { ...base.wifi, ...overrides.wifi }, mobile: { ...base.mobile, ...overrides.mobile } }
  p.low = Math.max(FLOORS.low, p.low)
  p.high = Math.max(p.low, p.high)
  p.maxPeers = Math.max(FLOORS.maxPeers, p.maxPeers)
  return p
}

/**
 * Whether to share now, and why not. [phone] is { privacy, metered, charging, battery, playing, dataSaver, sharedMobileMB }.
 * On the laptop in step 0 every node is unmetered and charging, so only the profile and Privacy mode decide.
 */
export function sharing (p, phone) {
  if (phone.privacy) return { on: false, why: 'privacy' }
  if (phone.metered) {
    if (!p.mobile.share || phone.dataSaver) return { on: false, why: 'mobile data' }
    if (phone.sharedMobileMB >= p.mobile.shareCapMB) return { on: false, why: 'mobile cap reached' }
  } else {
    if (p.wifi.share === 'off') return { on: false, why: 'off' }
    if (p.wifi.share === 'charging' && !phone.charging) return { on: false, why: 'not charging' }
    if (p.wifi.share === 'battery-ok' && !phone.charging && phone.battery < FLOORS.batteryShare) return { on: false, why: 'battery low' }
  }
  if (phone.playing && !p.wifi.whilePlaying) return { on: false, why: 'playing' }
  return { on: true, why: null }
}

/**
 * Auto mode (design note §6): picks a preset from the phone's state. The defaults are sensible; the listener can change
 * each one in Settings. Safety rules (no sharing on mobile data unless opted in, Data Saver obeyed, the floors) sit in
 * sharing() and the floors, so no Auto setting can weaken them.
 */
export const AUTO_DEFAULTS = {
  lowBatteryPct: 20, // at or under this, not charging: Light. Range 10 to 50.
  recoverBand: 10, // leave Light only at lowBatteryPct + this, so it doesn't flap
  allowGenerous: true, // Auto may pick Generous
  generousNeedsCharging: true, // Generous only on unmetered and charging
  hot: 'severe', // thermal level that forces Light: 'moderate' or 'severe'
  dwellMs: 5 * 60 * 1000, // least time between changes towards more use; changes towards less use are immediate
  plugDebounceMs: 30 * 1000 // a charging change counts only once it has lasted this long (loose cables)
}

const THERMAL = { none: 0, light: 1, moderate: 2, severe: 3, critical: 4, emergency: 5, shutdown: 6 }
const RANK = { light: 0, balanced: 1, generous: 2 }

/**
 * [phone]: { metered, charging, chargingSinceMs, batteryPct, thermal, batterySaver, dataSaver }.
 * [prev]: the last result, or null. Returns { name, reason, sinceMs }.
 */
export function auto (phone, settings = AUTO_DEFAULTS, prev = null, nowMs = Date.now()) {
  const s = { ...AUTO_DEFAULTS, ...settings }
  const charging = phone.charging && nowMs - (phone.chargingSinceMs ?? 0) >= s.plugDebounceMs
  const heat = THERMAL[phone.thermal] ?? 0
  const lowLine = prev?.name === 'light' && prev.reason?.startsWith('battery') ? s.lowBatteryPct + s.recoverBand : s.lowBatteryPct
  let pick
  if (phone.batterySaver) pick = { name: 'light', reason: 'Battery Saver on' }
  else if (heat >= THERMAL[s.hot]) pick = { name: 'light', reason: 'phone hot' }
  else if (!charging && phone.batteryPct <= lowLine) pick = { name: 'light', reason: `battery ${phone.batteryPct}%` }
  else if (phone.metered && phone.dataSaver) pick = { name: 'light', reason: 'Data Saver on' }
  else if (s.allowGenerous && !phone.metered && (charging || !s.generousNeedsCharging) && heat < THERMAL.moderate) {
    pick = { name: 'generous', reason: charging ? 'on Wi-Fi and charging' : 'on Wi-Fi' }
  } else pick = { name: 'balanced', reason: phone.metered ? 'on mobile data' : heat >= THERMAL.moderate ? 'phone warm' : 'on battery' }

  if (!prev) return { ...pick, sinceMs: nowMs }
  if (pick.name === prev.name) return { ...prev, reason: pick.reason }
  // Towards less use: at once. Towards more use: only after the dwell, so a brief plug-in doesn't swing it up and down.
  if (RANK[pick.name] < RANK[prev.name] || nowMs - prev.sinceMs >= s.dwellMs) return { ...pick, sinceMs: nowMs }
  return prev
}
