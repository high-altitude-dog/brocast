# Station head: requirements from main (24 Nov 2026)

User, 24 Nov: "Stations always have a head with good UI." Today a station is a headless script (`pears/tools/station.js`, 135 lines) that prints its state as lines in a terminal. The web session owns the head. These are main's requirements. How it looks and how it's built are up to web and Claude Desktop.

## What the station is today
- Run: `node tools/station.js --dir D --seed-file F --library DIR --library-json FILE --visuals DIR --artists DIR --minutes N [--relay KEY]` from `beachsync-prototype/pears`.
- It prints one line per event on stdout:
  - `PHOTOS n`
  - `VISUALS n of m sets`
  - `READY <corestore key> <station key>`
  - `STATION-URL brocast://station/<key>[?relay=<key>]`, plus a terminal QR
  - `QR-PNG <path>`
  - `NAT {json}`
  - `CONN {peer, side, outcome, path (lan / holepunch / relay), ms, remote, punches, relaying}`
  - `AUTOSTOP after n min`
  - `SHUTDOWN`
  - `SUMMARY`
- A head can wrap it as a child process and parse these lines. That's the cheapest start. Ask main if you need more events, such as per-peer progress, and main adds them to station.js. Don't change Pears code (`pears/node_modules`, see the Pears isolation rule). Our own code in `pears/tools` and `pears/engine` can change, through main.

## Must have
1. **Station QR, big and scannable**, with the `brocast://station/…` text and a copy button. When a relay is included, say so. This is the main thing a host shows people.
2. **Start / stop**, plus a countdown to auto-stop. The laptop station is capped at 60 min per start (user, 19 Nov); every other server stays at 10 min. Show why it stopped (auto-stop, error, or the user).
3. **Library at a glance:** the sets in the catalog (title, artist, length, size), with a visuals ✓ (`.viz` + `.nodes`, plus `ft.nodes` when present) and a photo ✓ per set. Totals: n sets, n of m visuals, n photos, GB.
4. **Listeners:** one row per connected peer, with its nym if known, path (LAN / hole punch / relay), when it connected and whether it's still connected. Per set: blocks sent to it, and whether its visual and photo have gone. Overall: "Pixel has 11 of 17 sets". Needs new events from main (see below).
5. **Verbose Pears status** (user, 23 Nov: "assume the user is a smart developer"):
   - DHT ready, and the station's NAT (firewalled / randomized / consistent)
   - each connection attempt with its path and outcome (open, PEER_CONNECTION_FAILED, HOLEPUNCH_ABORTED…)
   - bytes per peer and per path
   - a raw log pane with every line
6. **Errors in plain words**, such as a missing library dir, a seed file not found, or the port in use.

## Rules
- Runs on the laptop, on localhost only; no remote access unless the user asks. It must not need Google services.
- No raw audio over any network, and no playing sound.
- Keep server addresses, keys and the relay key out of anything pushed to the public repo (high-altitude-dog/brocast).
- Before starting a station with a new `--dir`, warn the user: it rebuilds the cores, which comes to about 1 GB. Reuse `work/main-station` by default (user's 100 MB rule).
- Coordinate in comms/web.log. Tell main before anything touches `pears/tools/station.js`.

## Events main can add on request
These would go into station.js as more stdout lines:
- `PEER {peer, nym, connected|closed}`
- `SENT {peer, set, blocks, of}` (throttled to about 1 per second per peer)
- `VISUAL-SENT {peer, set}`
- `PHOTO-SENT {peer, set}`
- `CATALOG {sets:[{id, title, artist, seconds, bytes, visual, ftNodes, photo}]}`

Tell main which you need.

## Later (not now)
- Picking the library / visuals / artists folders in the head instead of flags.
- Showing a station that runs on the VM (the server stations), with the same screens.
