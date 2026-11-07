# Engine API contract, version 1

7 Nov 2026. This is the contract between the app (the **host**: Kotlin, bare-kit, native add-ons) and the **engine** (the JS bundle in the worklet). It is the boundary from design note §11: a new engine bundle can change anything behind it. Status field names here are the ones screens should use. Design only; nothing in it is built yet.

## 1. Transport and encoding

- `bare-rpc` over the bare-kit worklet's IPC stream. Each request has a command name and a reply; events flow from the engine without a request.
- **Control, status and events are JSON**, so either side can add fields within a major version. **Every reader ignores unknown fields.** Mainline DHT has lasted 20 years on that rule.
- **Blocks are binary:** an 8-byte header (timeline second `g` as u64 LE), then the 1 s block in `opus-20ms-1s` format.
- **Units are always in the name:** `…Ms`, `…Sec`, `…Bytes`, `…Bps` (bytes a second), `…Pct`. Keys are 64-character hex. Times are flowtime in ns as strings (`…Ns`), since JSON numbers can't hold them exactly.

## 2. Versioning

- `hello` is always first. The host offers its API majors, the engine picks the highest one both support, and a mismatch is a refusal, not a guess.
- **Within a major:** new commands, fields and enum values only. A host that receives an enum value it doesn't know shows it neutrally, as `other`.
- **A new major** is needed to remove or rename anything, change a meaning or change a unit. Engines should support the current major and the one before it.
- The engine's manifest (`ENGINE` in `pears/engine/index.js`) also declares `wire`, `storage` and `native`. The host checks `native` against the add-ons in its APK before it starts the bundle at all.

## 3. Threads and timing (the one rule)

- The host never calls the engine from `Playout`, `Renderer` or the UI thread. Block requests come from the `stream` thread (`StreamClient` via `MeshSupply`). Everything else goes through one host thread, `mesh-rpc`.
- Status events arrive on `mesh-rpc`. The host publishes each as one immutable snapshot through a volatile reference, as `NudgeState` is, and Compose reads only that.
- `block` answers within its `timeoutMs` or fails with `NOT_HELD`. A late block plays silent and stays in sync, as today.
- **Playback order of choice:** a set built in `music/` plays from the file (`LocalSupply`), with no engine call. `MeshSupply` is used only for sets still building. With the engine stopped, built sets still play.

## 4. Commands (host to engine)

| Command | Request | Reply | Notes |
|---|---|---|---|
| `hello` | `{ api: [1], host: { app, native: {...} } }` | `{ api: 1, engine: { version, wire, storage, native } }` | Within 5 s of boot, or the health gate fails. |
| `start` | `{ seed, nymShuffle, stationKey, dirs: { store, music }, profile, phone }` | `{ publicKey }` | `seed` is 32 bytes in hex. The host owns it and the engine never writes it to disk. `dirs.store` is per storage version (`store-v1/`). |
| `setPhone` | `{ metered, charging, batteryPct, thermal, dataSaver, playing, privacy, screen }` | `{}` | Sent on every change. `privacy: true` is acknowledged only after uploads, announces and listener connections have stopped. |
| `setProfile` | `{ name: light\|balanced\|generous\|auto, overrides }` | `{ effective }` | `overrides` uses the policy fields from design §6. |
| `tick` | `{ g, flowtimeNs }` | none (notify) | Once a second: the playhead in timeline seconds. The engine schedules from it. |
| `block` | `{ g, timeoutMs }` | binary block, or error | A 1 s block. The engine splits transport blocks (4 s by default). |
| `blockInfo` | `{ g }` | see §6 | Provenance for one block, on tap. |
| `keep` | `{ setId, keep }` | `{}` | A kept set is never cleared from the store. |
| `sketch` | `{}` | `{ registers, sharers }` | Raw registers (base64, 1024 bytes each), for the heatmap. |
| `shuffleNym` | `{ nymShuffle }` | `{}` | The host stores the counter. The engine sends it in its hello to peers. |
| `subscribe` | `{ hz: 0\|1\|4, arrivals: bool }` | `{}` | The status rate follows what's on screen: 4 on Buffer or Swarm, 1 elsewhere, 0 with the screen off (state changes only). |
| `suspend` / `resume` | `{}` | `{}` | Suspend stops the swarm and lets the radio sleep. The engine also suspends itself at the high watermark (design §6). |
| `stop` | `{}` | `{}` | Closes cleanly within 2 s. The host may kill the worklet after that. |

Reserved for later: `publishHeadsetResult` (design §7).

## 5. Events (engine to host)

| Event | Payload | When |
|---|---|---|
| `status` | the snapshot in §6 | At the subscribed rate, and at once on any state change |
| `arrivals` | `[{ g, bytes, peer, from: station\|listener, via, ms }]` | Batched at most 4 times a second, only while subscribed. Feeds the Buffer tiles in flight. |
| `built` | `{ setId, path, sha256 }` | A set has been verified and moved into `music/` |
| `warning` | `{ code, text }` | Recoverable, for example `MISMATCH` (a built file failed its hash and is being rebuilt) |
| `fatal` | `{ code, text }` | The engine can't go on. The host counts it against the health gate and may roll back. |

## 6. Status snapshot

```
{
  at: { flowtimeNs, uptimeSec },
  engine: { version, api, wire, storage },
  me: { publicKey },                                   // the host makes the nym from this and nymShuffle
  profile: { chosen, effective, reason },              // reason e.g. "battery 18%", shown as MESH · LIGHT · battery 18%
  network: { kind: wifi|metered|none, lan: bool },
  sharing: { state: sharing|waiting|paused|off|privacy, why },   // why: not on Wi-Fi | not charging | playing | battery low | mobile cap reached
  swarm: {
    connected, relayed, foundNotConnected, shard, shards,
    stations: [{ key, via, rttMs, toYouBps }],
    peers: [{ key, via: lan|direct|holepunched|relayed, rttMs, toYouBps, fromYouBps, engine, wire, sinceSec }]  // busiest 16
  },
  buffer: {
    playheadG, aheadSec, holes: [[g, n]], fillX,       // fillX: arrival rate as a multiple of real time, null when steady
    state: filling|full|thin|missing|offline,
    watermarks: { lowSec, highSec }, burst: { active, sinceSec },
    radio: { awakeSec, asleepSec, windowSec }         // over the last 15 min
  },
  library: { edition, sets: [{ id, state: building|built|missing, pct, kept }] },
  ledger: {                                           // per network, since local midnight and this calendar month
    today: { wifi: { downBytes, upBytes }, metered: { downBytes, upBytes } },
    month: { wifi: {...}, metered: {...} },
    overheadPct, duplicatePct, dhtBytes
  },
  listeners: { estimate, errorPct, sharers, censusSharers }   // censusSharers null until shards exist
  pears: { dhtNodesToday, relaysUsed, blindPeers }             // help from the wider Pears network (design §10)
}
```

**`blockInfo` reply:** `{ g, setId, index, transportSec, bytes, peer, from, via, ms, verified: true, proof: { nodes, bytes }, signedBy, treeLength }`. `verified` is always true, because an unverified block is never stored; the field is there to be shown.

**Screens:** `swarm` feeds Swarm, `buffer`, `arrivals` and `blockInfo` feed Buffer, and `listeners` feeds the header count. `ledger`, `profile` and `sharing` feed Settings › Delivery. `engine` together with the host's slot data feeds the Engine panel. Each `DemoMesh` value maps to a field: `NYM` to `me`, `LISTENERS` to `listeners.estimate`, `peers` to `swarm.peers`, `AHEAD_TEXT` to `buffer.aheadSec`, `SENT_TODAY_MB` to `ledger.today.*.upBytes`.

## 7. Errors

`NOT_HELD` (the block isn't here yet), `BAD_REQUEST`, `NOT_STARTED`, `API_MISMATCH`, `NATIVE_MISMATCH`, `STORAGE_MISMATCH` (the engine needs a fresh store; the host gives it a new `store-vN/`), `SUSPENDED`, `SHUTTING_DOWN`. Each reply carries `{ code, text }`. Hosts act on the code, never on the text.

## 8. The health gate (host-side, design §11)

A candidate engine passes only if all of these hold:

1. `hello` replies within 5 s.
2. `start` replies.
3. `status.library.edition` appears.
4. At least 20 blocks are verified (`arrivals`).
5. 10 minutes of playback pass with no `fatal` and no worklet crash.

Failing any step rolls back to the last good engine, and that version is blocked on this phone. The host owns the slots, rollback and pinning; the engine only reports.

## 9. Privacy and secrets

The seed crosses the IPC once, in `start`, and is never logged or echoed. `status` never contains network addresses: peers are listed by key, and `via` says only how they're connected. Under `privacy`, the engine reports `sharing.state: privacy` and no listener peers.
