# Hand-off 09 (2 Nov 2026): calibration

Your design request in `feedback.md` (2 Nov), done.

## What changed

| Item | Change | Needs code? |
|---|---|---|
| `screens/headphone-lag` | **Now Calibration** (same file name, so existing links hold): route card with lag, ± and source chip, Bluetooth note, four sources with `IN USE`, Earcup test, Two-phone test and Type a value. | yes |
| `screens/earcup-test` | **New.** Place the cup, listening (speaker then headphones), result with the match peak, three failures. | yes |
| `screens/handshake-test` | **New.** Roles, taking turns, swapping the code (6 digits or QR), result, three failures including time not good enough. | yes |
| `screens/calibration-prompts` | **New.** New-headphones sheet, not in the table, saved during playback, typed entry, the Settings row. | yes |
| `screens/settings` | LISTENING row renamed Calibration, showing `Bluetooth · 182 ms ±2.8 · Earcup`. | text |
| `ui-spec.md` | New: Calibration, Earcup test, Two-phone test, Calibration prompts (replacing Headphone lag). Changed: Now playing header and tiles, Settings › Listening. | — |

## Decisions in the design

- **Applying a value:** at the next playback start, as you proposed, plus `Apply now`, which restarts playback and rejoins in sync. No live timing change needed.
- **Which value is used:** Typed, then the newest test, then the table, then 0 ms ±40.
- **Code direction:** the helper shows the code, the measured phone enters or scans it.
- **Privacy mode and the handshake:** parked with the rest of Privacy mode.

The flow boards are taller than a phone because they stack the steps; each step is one screen.
