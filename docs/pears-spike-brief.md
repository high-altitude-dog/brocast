# Pears spike: brief for a fresh Claude Code session

Written 7 Nov 2026 by the main session. Read this file, then your memory index (`MEMORY.md`), before you do anything else. Start with `beachsync-current-state` and `beachsync-product-direction`.

## Where you work

- **Worktree:** `~/Documents/brocast/pears-spike`, on branch `pears-spike`, cut from `edaade6`.
- **Main tree:** `~/Documents/brocast/beachsync-prototype` belongs to the main session. Don't edit it, and don't commit on its branch.
- Commit on `pears-spike` as you go. **Never push.** The main session merges your work after the user has looked at it.

## Goal

Prove that Pears can carry Beach Sync's library: Opus sets fetched by public key from peers rather than from the server, then played in sync with flowtime. Then put the real values behind the four demo items: nym, listener count, Swarm and Buffer (`DemoMesh`, in `android/app/.../ui/NowPlaying.kt` and `DemoScreens.kt`).

This should become the "unplug demo": take the server offline and phones keep getting the library from each other.

## Agreed order (from the 2 Nov plan)

Build it in a separate test app, not inside the main app.

0. **Laptop only:** two or more local processes. One seeds the library, keyed by an ed25519 keypair; another fetches it by key. Measure block arrival.
1. Same network: phones on the same Wi-Fi.
2. Internet, via Hyperswarm (HyperDHT is already proven: see `server/dht/`).
3. Wi-Fi Direct.
4. Seeding while another phone plays.

Block length: keep 1 s for now. Test 1, 2 and 4 s within the spike.

**Stop after step 0 and report.** The user hasn't yet said to start the phone part.

## First deliverable: a short design note, before any code

Write it as `docs/pears-spike-design.md` and get the user's OK. It should cover:

- **Stack:** which Pears pieces you'll use (Hyperswarm, Hypercore or Hyperdrive, Corestore, Bare or bare-kit on Android) and how the Android test app hosts them.
- **Library mapping:** how `library.json` and the Opus files map onto the stack.
- **Identity:** the server keypair, the per-listener keypair, and the colour-fruit-animal nym made from that keypair.
- **Real values:** where the listener count, Swarm and per-block Buffer data come from.
- **Privacy mode:** turning it on stops all sharing.
- **Headset delays:** a later Hyperdrive indexed by public key. Design it now, but don't build it.

## Rules, from the user's memory: all of them apply

- **Transfers:** compressed only. No raw PCM over any network, in tests or tools (`compressed-transfers-only`).
- **Sound:** silent until the user says Go. Give a 10 s countdown, and stop the sound immediately afterwards (`test-sound-etiquette`).
- **Tokens:** short replies, batched work, and no tests unless the user asks for them (`token-use-and-testing-pause`).
- **Server:** touch only the `brocast-test`, `brocast-admin` and `brocast-dht` containers, and stop them between tests. Never open port 8766 (`beachsync-server-vm-parked`). The step 0 work shouldn't need the server.
- **Public repo:** no server IP, login, key names or server public key in any file. Use `<server-ip>`, `<user>` and `<key>` instead (`brocast-public-repo`). No private keys in the repo, ever. No artist photos or artist names.
- **Pears branding:** no Pears branding, and nothing that implies endorsement.
- **Design:** Claude Desktop owns `design/`. You may edit only `design/feedback.md`. Tell the user about every design decision (`share-design-decisions`, `flag-design-code-work`).
- **Disk:** this machine runs on a USB drive. Load the `usb-cooldown` skill before any npm install, Gradle build or large copy.
- **Repo rules:** follow the repo's `CLAUDE.md`.

## Reporting back

When you finish, or stop at the step 0 checkpoint, write `docs/pears-spike-results.md` in the worktree. Keep it under about 60 lines, because the main session will read only that file. Include:

- what works and what was measured, with numbers
- what failed or is still unproven
- the decisions you made, and which of them the user approved
- the files you added or changed, and the commits on `pears-spike`
- what the main app needs in order to adopt the spike, with exact entry points
- any open questions for the user

Also add a dated update block to the `beachsync-current-state` memory.
