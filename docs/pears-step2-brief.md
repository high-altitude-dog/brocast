# Pears step 2: brief for a fresh Claude Code session

Written 8 Nov 2026 by the main session. Start Claude from `~/Documents/brocast` so the shared memory loads. Read, in order: this file, `MEMORY.md`, `docs/pears-step1-results.md`, then `docs/pears-step1-brief.md` (its Rules section applies here unchanged).

## Where you work

- **Worktree:** `~/Documents/brocast/pears-step2`, on branch `pears-step2`. The main tree belongs to the main session.
- **Never push.** Commit as you go; the main session merges.
- **Don't touch `android/`.** Use the step 1 test app in `pears/android-test/`.

## Goal

Step 2 of the agreed order: **the same fetch-by-key over the internet through Hyperswarm**, with no LAN shortcut.

- **Station:** the laptop's step 0 station on the **public** HyperDHT (`station.js` without `BOOTSTRAP`).
- **Phones:** started without `bootstrap`, each on **mobile data with Wi-Fi off**. Then one phone on mobile and one on Wi-Fi.

**Done when, for each run:**
- At least one whole set is built and byte-identical.
- These are measured with the step 1 tools (`phone.sh`, `phone-report.js`, `verify.js`):
  - time to find the catalog, first block and all blocks
  - per-block arrival
  - wire overhead
  - which connections were direct, hole-punched or relayed, and what failed to connect

The home NAT and the mobile carrier's NAT may block hole-punching. If they do, that's a finding, not a failure: record it, and find out what Hyperswarm offers (relays, blind peers). Check current docs, and mark what's confirmed against what's inferred.

## Out of scope

- The duplicate-block fix: note its overhead here. It's needed before step 4.
- Wi-Fi Direct, seeding while playing, and the main app.

## Extra rules for this step

- **Mobile data costs the user money.** Keep to the 18.7 MB test library. Say each run's expected data use, and get a yes before it.
- **Reaching the internet:** the station is reachable from the internet while it runs. It stops itself after 10 minutes, and you read its egress log at start and stop, as in step 1.
- **The test server VM:** don't use it in step 2.

## Reporting back

When step 2 is done, or you're blocked, write `docs/pears-step2-results.md`, under about 60 lines. Use the same headings as step 1, and add "connection types". Add a dated block to the `beachsync-current-state` memory.
