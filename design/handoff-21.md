# Hand-off 21 (7 Nov 2026): Data and sharing

From the Pears spike's 7 Nov notes (design/feedback.md on `pears-spike`) and the user's OK: profiles first, with detailed descriptions so listeners understand the behaviour; sharing per network; Share while playing; the listener estimate with ≈.

| Item | Change | Needs code? |
|---|---|---|
| `screens/data-sharing` | **New.** Profile (Auto, Light, Balanced, Generous) with Auto's reason; What it does (detailed); By network columns (On Wi-Fi / On mobile data, today's figures, "Never uses your mobile data to share"); switches (mobile opt-in, Share while playing, party-only); Measured today; Advanced link. | yes |
| `screens/data-sharing-states` | **New.** Light and Generous descriptions, Auto's reasons, moving between networks, the mobile-data opt-in sheet with a log-scale daily cap, safety floors. | yes |
| `screens/settings` | DELIVERY is now: Share with other listeners, and a Data and sharing row. Download ahead on mobile data, party-only and the allowance moved into Data and sharing. | yes |
| `screens/first-use-sharing` | Conditions: Wi-Fi only, never mobile data; not under 30% battery, music plays as normal; up to 4 GB. | text |
| `screens/sharing-states` | No "not playing"; paused reasons are on mobile data, battery under 30%, or playing with Share while playing off. | text |
| `screens/now-playing` | Header: `≈ 1.2 M listening`. | small |
| `ui-spec.md` | New: Data and sharing, Data and sharing states. Changed: Settings › Delivery, First use conditions, Sharing states, Header. | — |

Next: the Advanced tuner (predicted against measured cost) and the frontier ideas, starting with block provenance and the per-network data ledger.
