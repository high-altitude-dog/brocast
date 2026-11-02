# Hand-off 06 (2 Nov 2026): first-use sharing prompt

The user decided:

1. Keep overrides the limit: kept files sit outside the size limit.
2. On first use the listener is asked whether to share downloads, and the default is on.

## What changed

| Item | Change | Needs code? |
|---|---|---|
| `screens/first-use-sharing` | **New.** A bottom sheet that must be answered. It covers why sharing helps, three conditions (Wi-Fi only, while charging and not playing, the allowance), `Share with other listeners` (the default, with initial focus), `Not now`, and an honest footnote about network addresses. | yes |
| `screens/settings` | Renamed to `Share with other listeners`. Peers come from the DHT, not from nearby, so "nearby" was wrong. | text only |
| `ui-spec.md` | New: First use: sharing. Changed: Settings › Delivery (the sharing row); Archive rules (Keep overrides the limit is now confirmed). | — |

"Sync from nearby phones" in the Silent badge keeps its name, because that feature is about phones at the same event.
