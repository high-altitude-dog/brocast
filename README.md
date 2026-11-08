# Beach Sync

**Listen to the same music, at the same instant, anywhere in the world.**

Beach Sync is an Android app that plays music in sync across any number of phones. Everyone hears the same beat at the same moment, within milliseconds and aiming for better, whether they're on the same beach or on opposite sides of the planet. Think of a silent disco with no limit on size or distance, or a 24-hour radio station where every listener is on the same beat.

The first channel is **electronic dance music sets**.

## How it works

**One shared clock.** Every phone works out "flowtime", a precise shared timeline, and plays each part of the stream at its flowtime. Flowtime comes from two sources:

- **Satellites (GPS).** The phone reads time straight from GNSS satellites. The more satellites in view, the tighter the sync, and the app shows it tightening.
- **NTP, benchmarked to the UK National Physical Laboratory (NPL) in Teddington.** The default fallback for phones without GPS, and for anyone who turns satellites off for privacy. Listeners can switch "Use satellites" on or off at any point, even mid-song.

**Hardware delay compensation.** Sound takes a different time to leave different devices, and the app measures and corrects for it:

- the phone's built-in speaker
- wired headphones and external speakers
- Bluetooth headphones and speakers, including codec-dependent delay (for example LDAC, SBC and AAC differ by tens of milliseconds)

Calibration screens measure the delay of your own device, and results can be shared so other listeners with the same gear get a head start. Sharing is on by default and off in Privacy mode.

**Drift correction.** The player continuously nudges its playback rate so it stays locked to flowtime without audible glitches.

**Delivery that scales.** Sets are compressed (Opus), downloaded once and played locally in sync with flowtime. A peer-to-peer mesh shares the files between phones on the same network, over direct phone-to-phone links and across the internet, so reaching millions of listeners doesn't mean paying for millions of streams.

## Status

Early and in active development. Library playback, GPS and NTP flowtime, the satellite view and the delay-calibration tools are built. Sync accuracy on real hardware is still being measured, so the figures above are design targets, not published results. The measurements, including the wired two-phone rig, are in the [prototype test guide](docs/prototype-test-guide.md).

## Repository

| Folder | What it is |
|---|---|
| `android/` | The Android app (Kotlin, Jetpack Compose) |
| `server/` | The stream server and library tools |
| `measure/` | Scripts that measure how far apart phones play the same sound |
| `simtest/` | Desktop tests of the app's timing code |
| `design/` | Screen designs and hand-offs |
| `docs/` | Design notes and the [prototype test guide](docs/prototype-test-guide.md) |

## Licence

MIT. See [LICENSE](LICENSE).

The app's IP-to-country table (`android/app/src/main/assets/countries-v4.bin`, built by `pears/tools/country-table.js`) comes from [IP Geolocation by DB-IP](https://db-ip.com), licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
