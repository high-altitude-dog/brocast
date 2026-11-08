# Pears step 1: brief for a fresh Claude Code session

Written 7 Nov 2026 by the main session. Read this file, then `MEMORY.md`, then `docs/pears-spike-results.md`, before anything else. Start Claude from `~/Documents/brocast`, so the shared memory loads.

## Where you work

- **This worktree:** `~/Documents/brocast/pears-step1`, on branch `pears-step1`, cut from `dfeca88`.
- **The main tree** (`~/Documents/brocast/beachsync-prototype`) belongs to the main session. Don't edit it.
- **Never push.** Commit on `pears-step1` as you go; the main session merges.
- **Don't touch `android/`,** the main app. Step 1 is a separate test app.

## Goal

Step 1 of the agreed order (spike results, design note §1–§13): **the Pears engine running on an Android phone, fetching a set by key from the laptop on the same Wi-Fi.**

- **Laptop:** the step 0 station (`pears/tools/station.js`) seeds the test library, keyed by an ed25519 keypair.
- **Phone:** a minimal test app hosts the `pears/engine` bundle in a bare-kit worklet. Given only the station key, it:
  - finds the catalog
  - fetches every block, verified against the signed tree
  - builds the set into its files with a BLAKE2b check, as step 0 does on the laptop

**Done when:**
- Both test phones have built at least one whole set from the laptop over the same Wi-Fi.
- The files match the laptop's.
- These are measured:
  - time to the first block, and to the whole set
  - per-block arrival
  - CPU and battery for the run
  - the **swarm wire overhead**, which was 14 to 58% on the laptop and is unexplained; duplicate blocks are suspected

## Scope

- **In:**
  - A separate Android test app: its own Gradle project under `pears/android-test/`, its own package id, so it never replaces the main app.
  - bare-kit and the engine bundle (bare-pack or whatever bare-kit documents now).
  - The host talks to the engine over `bare-rpc`, using the commands in `docs/engine-api.md` that step 1 needs: start, join the station, status, stop.
  - A plain screen showing status: peers, blocks, bytes and the overhead figure. No design hand-off is needed for a test app.
- **Out:**
  - playback and timing (nothing touches flowtime or the audio thread)
  - internet over Hyperswarm (step 2)
  - Wi-Fi Direct (step 3)
  - seeding while playing (step 4)
  - the main app (step 5)
  - the identity wizard and the Keystore

## Before you start

- **Check current docs first.** Read the current bare-kit and Bare documentation and check package versions before choosing a toolchain. Pears tooling moves fast, so say what you confirmed and what you inferred (the `flag-research-confidence` skill fits).
- **Toolchain needs a yes.** If bare-kit needs the Android NDK or another large install, list it with sizes and get the user's yes first.

## Rules: all from the user's memory, all apply

- **Tokens:** short replies, batched work, no tests unless asked (`token-use-and-testing-pause`). Ask before each phone run; the user has to be there.
- **USB drive:** load `usb-cooldown` before any npm install, Gradle build, NDK or SDK install, or large copy. Check `/proc/pressure/io` first.
- **Seed servers:** every seed or station server you start stops itself after 10 minutes (`station.js --minutes`, default 10). Read its `egress-alerts.log` whenever you start, check or stop one, and tell the user plainly (`seed-server-autostop`).
- **Transfers:** compressed only. Sets move as Opus, never raw audio (`compressed-transfers-only`).
- **Sound:** step 1 makes none. If anything ever would: silent until the user says Go, a 10 s countdown, and stop straight afterwards.
- **The test server VM:** don't use it in step 1, as the laptop is the station. Never open port 8766 (`beachsync-server-vm-parked`).
- **Public repo:** no server address, login, key names or server key, and no private keys or artist names in any file (`brocast-public-repo`). Test keypairs are generated at runtime and kept out of git.
- **Branding:** no Pears branding, and nothing that implies endorsement. The user is in touch with the Pears developers.
- **Phones:**
  - Pixel 10 Pro `5A161FDCH00197` and Galaxy A16 `R5CX93ZA1TN`, over adb.
  - If adb says "unauthorized", ask the user to accept the prompt.
  - The main app (`org.beachsync.proto`) is installed on both phones and may be analysing beats in the background: don't uninstall it or clear its data.

## Reporting back

When step 1 is done, or you're blocked, write `docs/pears-step1-results.md` in this worktree. Keep it under about 60 lines, because the main session reads only that file. Include:

- what works, with the measured numbers
- what failed or is still unproven
- the toolchain and versions, as confirmed
- decisions made, and which ones the user approved
- the files and commits on `pears-step1`
- what step 2 and the main app need next, with exact entry points
- open questions, including any for the Pears developers

Add a dated block to the `beachsync-current-state` memory.
