# Desktop seeder: spec and high-level design

7 Nov 2026. A small app that lets anyone with an always-on computer carry Brocast's bandwidth. It is the main way Pears users who aren't phone listeners can help (design note §10). Part 1 says what it must do; part 2 says how. The screens belong to Claude Desktop; suggestions for them are in `design/feedback.md`.

# Part 1: Spec

## Why

While the audience grows slowly, few phones are online together, so phones can't yet carry each other. Long-lived, well-connected, unmetered peers matter most, and a home computer or a small server is exactly that. Each seeder takes load off the station, keeps the library available when the station is offline (the unplug demo), and keeps the station's DHT record alive.

## Who

1. **The station owner (you).** Home machines, and any spare server. This is the first and main user.
2. **Fans and Pears users** who want to help a station they like.
3. **Later:** venue operators at beach events. A venue seeder is an ordinary global seeder; it is not limited to its own site (user, 7 Nov). Phones on the venue's Wi-Fi reach it over the LAN automatically, because HyperDHT tries local addresses when peers share a public IP, so the free local path comes without a special mode.

## Must

| # | Requirement |
|---|---|
| S1 | **Start with one command,** `pear run pear://<seeder key>`, on Linux, macOS and Windows. Also a **headless mode** for servers and small boards (for example a Raspberry Pi), with no screen needed. |
| S2 | **Seed the station's library:** every set in the current edition, verified block by block against the station's signature, as phones do. Optionally the previous editions, up to a disk cap. |
| S3 | **Know only the station's public key.** Find the catalog through the DHT record, as the unplug demo does. |
| S4 | **Resource controls, with sensible defaults the user can change:** disk cap (default 20 GB), upload cap (default none), hours of the day (default always), "pause on a metered network" (default on), and low priority for CPU and disk. |
| S5 | **Give back to the network:** run as a full (persistent) DHT node when reachable, refresh the station's DHT record hourly, and optionally act as a blind relay for phones whose hole punching fails (default off). |
| S6 | **Bridge shards:** announce on several topic shards (design note §12), so seeders spread evenly and every shard has some. |
| S7 | **Updates and rollback:** stay current with the Pears modules automatically, and let the user pin or roll back to an earlier version (design note §11). |
| S8 | **Show what it's doing,** for very bright users (the same audience as the phone app): what it serves, to how many peers, per shard; data per day and month; proof that every block is verified; versions in the swarm; and whether it's reachable directly or only through relays. |
| S11 | **Never local-only (user, 7 Nov):** every station is reachable globally by anyone who selects it. A seeder always serves the whole internet; the LAN is just a faster path when one exists. |
| S9 | **Safe by design:** it stores and serves only blocks signed by the station key it was given, nothing else. It can't be used to spread other content. |
| S10 | **Honest about privacy:** peers can see the computer's network address, as with any peer-to-peer sharing, and the app says so on first run. It stores no data about listeners. |

## Won't

- Play audio. It's a seeder, not a player; a "listen" link could open the phone app later.
- Accounts, sign-ups, payments or rewards.
- Its own upload of music. Only the station publishes.
- Pears branding or anything implying endorsement, until the user says otherwise. It runs on the Pear runtime and the footnote says so, as a plain fact.

## Done when

1. A fresh computer runs the command and serves its first block to a phone within 2 minutes.
2. With the station offline, a new phone gets the whole library from seeders alone.
3. The caps hold: disk never above the cap; no upload on a metered network while paused there.
4. A bad update can be rolled back in two clicks (or one command, headless), and the library survives it.
5. The status page shows real numbers, matching what the phones see.

# Part 2: High-level design

## Shape

```
pear://<seeder key>            the app, signed by the station's release key and updated by the Pear runtime
├── ui/                        desktop window (screens by Claude Desktop), reads the status snapshot only
├── cli.js                     headless mode: status in the terminal, or as JSON at --status-json
└── seeder.js                  the host side: settings, slots, health gate
    └── pears/engine           the same engine module as the phone, run with role: 'seeder'
```

- **One engine, two hosts.** The seeder imports the same `pears/engine` as the phone. The seeder host plays the part the Kotlin host plays on the phone, through the same API contract (`docs/engine-api.md`), with `start({ role: 'seeder' })`.
- What the seeder role changes: no playhead, so no `tick` and no `block` calls; a fetch-everything schedule; shard bridging; the record refresher; and the optional relay. No audio code runs in the seeder at all.

## What the seeder does

1. **Find the library:** read the station's DHT record (`mutableGet` with refresh), open the catalog, and follow it for new editions. The record is refreshed once an hour after that.
2. **Fetch:** download every set core of the current edition in full, in timeline order. Blocks arrive verified. It doesn't build files: a seeder serves blocks and never plays them, so it needs only the store. That halves its disk use compared with a phone.
3. **Keep:** the current edition, then older editions newest first while under the disk cap. Older blocks are cleared (`core.clear`) oldest edition first.
4. **Serve:** answer any peer's requests. Upload stops while the network is metered, if that setting is on, by leaving the topics and closing listener connections, the same way as Privacy mode.
5. **Announce:** on its own shard plus up to 7 others, chosen by `hash(its key ‖ hour)`. The choice rotates hourly, so seeders cover every shard over time without overloading any.

## Network role

- **DHT node:** HyperDHT's adaptive mode makes a node persistent after about 20 minutes of stable, reachable running. A home computer usually qualifies, so it helps route everyone's lookups, Brocast's and other Pears apps'.
- **Relay (optional, off by default):** a blind relay (`blind-relay`) for phones on carrier-grade NAT. It costs the seeder's bandwidth, so it gets its own upload cap.
- **Identity:** the seeder's own Ed25519 keypair, seeded from a file in the Pear app's storage. It has a nym like a phone. Its hello to peers says `role: seeder`, so Swarm can show seeders as their own kind, alongside station and listener.

## Updates and rollback

- **The app itself** updates through the Pear runtime. A Pear link can also name an exact release (`pear://<fork>.<length>.<key>`), which gives pinning and rollback without extra machinery. This needs confirming against the current Pear runtime before building.
- **The engine** follows the phone's design-note §11 rules, with slots, a health gate and automatic rollback. The library store is per storage version, so a rollback never touches the blocks.
- **Release signing:** the same offline release key as phone engine releases. Its security design is on the backlog (`docs/work-queue.md`).

## Upload cap

Hypercore has no upload rate limit (step 0 finding). Until it has one, the seeder caps upload by limiting connections and requests in flight per peer, and by pausing announces once over the daily cap. A true rate cap is a fourth ask for the Pears developers.

## Status (for the UI and headless mode)

The engine API status snapshot, plus a `seeder` block:

```
seeder: {
  edition, setsHeld, setsTotal, storeBytes, capBytes,
  servedTodayBytes, servedMonthBytes, peersServedToday,
  shards: [{ shard, announced, peers, servedBytes }],
  record: { lastRefreshSec, seq },
  dht: { persistent, reachable: direct|relayed|unknown },
  relay: { on, sessions, bytesToday }
}
```

## One codebase: station seed servers are headless seeders (user, 7 Nov)

Yes. The station's own seed servers run this app in headless mode, with `role: 'station-seed'`. This also splits the station in two, which is safer:

- **Publisher:** ingests uploads, signs blocks, the catalog and the DHT record, and holds the station key. It runs on a machine you control, can stay offline between editions, and serves nobody.
- **Seed servers:** headless seeders that hold **no secret key**. A stolen cloud server can't sign anything. They're listed in the catalog's `seeds` with a cost class (next section), signed by the publisher.

## Seeding priority: lowest cost first (user, 7 Nov)

Cloud platforms charge for data leaving them (egress). The cheapest byte is one a peer sends over a network that's already paid for. Every source has a **cost class**:

| Class | Source | Marginal cost |
|---|---|---|
| 0 | Peers on the same LAN | none |
| 1 | Listeners sharing on Wi-Fi, home and venue seeders on flat-rate broadband | none to the station |
| 2 | Station seed servers on flat-rate hosting | fixed, already paid |
| 3 | Station seed servers on egress-billed cloud | per GB |

A node's class comes from the catalog for station seeds, or from its signed hello for everyone else. A peer can claim a cheaper class than its real one, but not a station seed's place.

**How the order is enforced with today's APIs:**

1. **Class 3 seeds don't announce on any topic.** Phones find classes 0 to 2 through the swarm, and dial a class 3 seed by key (`joinPeer`) only under **deadline pressure**: buffered audio has fallen below the low watermark and no cheaper peer has the next missing block. They disconnect (`leavePeer`) once a cheaper peer covers them. So cloud egress pays only for music that would otherwise go silent.
2. **Seed the seeders first:** when a new edition appears, class 2 and 3 seeds serve home and venue seeders (class 1) before any phone. Then the free tier carries it to listeners.
3. **Super-seeding on paid seeds:** a class 3 seed tries to send each block once to a peer that will pass it on (a sharer or seeder), and refuses receive-only phones unless they're under deadline pressure. Receive-only phones never pass blocks on.
4. **A hard egress cap** per class 3 seed: a daily budget, by default the provider's free monthly allowance divided by 30. You asked for a cap under the free 200 GiB earlier. Past the budget, the seed serves only phones under deadline pressure, up to a hard stop at 120% of the budget.
5. **Measured, not assumed:** the ledger counts bytes served and received per cost class. The station's dashboard shows today's cloud egress against the budget and the cost per listener-hour.

**Gaps:** Hypercore picks peers itself among those holding a block, so we control cost at the connection level (who we're connected to), not per request. Rate limits and a per-request priority hook would make this exact. Add both to the asks for the Pears developers.

## Resource safety: never hang the machine (user, 7 Nov)

A seeder must stay a good guest on a computer someone is using, including this one, which runs from a USB drive.

- **Low priority by default:** nice 19 and the idle I/O class on Linux; background QoS on macOS; below-normal priority on Windows.
- **Hard budgets, with sensible defaults the user can change:**

  | Budget | Default |
  |---|---|
  | Memory (RSS) | 512 MB |
  | Connections | 32 |
  | Requests in flight | about 300 KB |
  | Disk writes | 20 MB/s |
  | Free-disk floor | 10%, and at least 5 GB |
  | Upload | uncapped on unmetered connections |

- **Back off under pressure (Linux, PSI):** the same rules as the `usb-cooldown` skill. If I/O pressure stays above 70% for 2 minutes, the swarm suspends for at least 60 s, and resumes only once pressure is under 30%. CPU and memory pressure work the same way. On macOS and Windows, use event-loop lag and the OS's thermal state.
- **Watchdog:** if the event loop lags by more than 500 ms, halve the connections and requests in flight. If memory nears the cap, shrink caches first, then connections.
- **Throttled persistence:** batch syncs to disk, and never sync once per block, so a cheap drive isn't hammered.
- **Visible:** the status page names any back-off and its reason (`PAUSED · disk busy 82%`), the same way phones name their Auto reason.

## A browser page on a local machine (user, 7 Nov: may seed or co-seed from one)

Today's stack can't seed from inside a browser tab (checked 7 Nov): Hypercore's storage needs native RocksDB, and the transport is native UDP. A browser peer would need an old Hypercore, a different storage layer and a WebSocket bridge to the DHT (`@hyperswarm/dht-relay`), and that bridge is itself a server to pay for. It also stops when the tab closes.

**The design instead:** the seeder runs as a local process (headless mode), and the **browser page is its control panel and dashboard**. It's served by the seeder at `http://127.0.0.1:<port>/`, using the same status snapshot as the desktop UI. So "seeding from a browser page" means opening a page, and closing the page doesn't stop the seeding. The local web UI listens on loopback only, checks the `Host` header, and needs a per-run token in the URL, so other websites can't drive it.

## Open questions

1. Does the current Pear runtime still support `pear run` with pinned links and the update flow described above? Check before building.
2. Who runs the first seeders besides you? (Settled, 7 Nov: no local-only mode. Any station must be reachable globally by anyone who selects it.)
3. A seeder doesn't build files. Should it offer "export the library as files", for backups?
4. Which cloud provider and egress price should the default budget assume? (Your earlier note: stay under the free 200 GiB.)
