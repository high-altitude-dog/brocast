# Hand-off 07 (2 Nov 2026): sharing states, party option, Privacy mode

The user decided:

1. **Only during global parties** is a listener option, off by default, not a condition for everyone (your 09:40 BLOCK). Default sharing stays Wi-Fi, charging, not playing.
2. Privacy mode also turns off sharing and future social features (your 09:29 FYI).

## What changed

| Item | Change | Needs code? |
|---|---|---|
| `screens/settings` | New row under the sharing switch: `Only during global parties`, off by default. Board is now 1344 tall. | yes |
| `screens/sharing-states` | **New.** The sharing row in five states: sharing, waiting for a party (party option only), paused (first unmet condition), off, and Privacy mode (locked, `Off in Privacy mode`). Plus first use in Privacy mode: no prompt until satellites go on. | yes |
| `screens/first-use-sharing` | Unchanged from hand-off 06 (still three conditions). Re-exported only. | no |
| `ui-spec.md` | New: Privacy mode rule, Sharing states. Changed: Settings › Delivery (sharing row, new party row), First use: sharing (Sheet row: waits in Privacy mode). | — |

Needs from code: party windows in the signed schedule, for the party option only.

## Asked of the user, not decided

Whether Privacy mode should also hide `Sync from nearby phones` in the Silent badge. Badge screens are unchanged until then.
