---
name: desktop-check-channel
description: Claude Desktop only (the design side). Use when the user says "check the channel" in this project; reads new lines from Claude Code and replies on the live channel. Claude Code ignores this skill.
---

# Check the channel (Claude Desktop)

These steps are for the design session that reaches this repo through the bridge to the user's computer. The repo is at `~/Documents/brocast/beachsync-prototype`. The protocol itself lives in `CLAUDE.md` › Live channel with Claude Desktop; read only that section, and only if it may have changed.

## Steps

1. **Read.** Stage `comms/to-desktop.log` and print only the lines after the last one already handled. In a new session with no memory of a cursor, use `tail -n 20`.
2. **Act on each new line by its tag:**
   - `BLOCK`: Claude Code is waiting on you. Handle it first.
   - `ASK`: answer in one line.
   - `CLAIM`: leave those screens or files alone.
   - `DONE` or `FYI`: note it. Act only if it changes your work.
3. **Reply** only when something changes Claude Code's work. Never send an acknowledgement.
   - Format: one line per message, under 200 characters, `HH:MM TAG text`, with the time from the user's clock.
   - Tags: `BLOCK`, `CLAIM`, `DONE`, `ASK`, `FYI`.
   - Point to files instead of pasting their contents.
4. **Append safely.** The bridge can only write whole files, so:
   1. Stage `comms/to-code.log` and note its `mtimeMs`.
   2. Copy it to a **new, uniquely named** file in the outputs folder, then append your lines. Reusing a path can send a stale copy.
   3. Commit with `expectedMtimeMs` set to the value you noted.
   4. Stage it again and check that the size grew.
5. **Before starting work on a screen,** post a `CLAIM` line. Don't start on anything Claude Code has claimed.
6. **After a hand-off lands in `design/`,** post `DONE` and name what changed: screens, tokens, and the spec sections. Claude Code commits the hand-off and checks it against the app.
7. **Disagreements go to the user** in one line. Don't debate in the logs.
8. **If there's nothing new,** tell the user in one short line and stop.

## Limits

- Only `design/`, `comms/to-code.log` and this skill are the design side's to write. `design/feedback.md` belongs to Claude Code.
- No git, and no background wait. The user starts each turn with "check the channel".
