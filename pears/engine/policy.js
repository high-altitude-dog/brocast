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
