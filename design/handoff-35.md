# Hand-off 35 (8 Nov 2026): Pears steps 1–2 ideas

Your 02:15 ASK, design/feedback.md 8 Nov, items 1–9.

| # | Where | Change |
|---|---|---|
| 1 | `screens/first-listen` **new** | Three stages with times, done, from memory, over 20 s, can't find (60 s). |
| 2 | `screens/mesh-moments` **new** | Station offline: proud accent banner on Now playing, dashed station on Swarm, "carried it for 14 min" log line. |
| 3 | mesh-moments | Every link relayed: Swarm-only quiet line; nothing on Now playing. |
| 4 | data-sharing, data-sharing-states, data-sharing-advanced, data-ledger | Real data (+~17%) and music time for caps. |
| 5 | block-provenance, data-ledger | `Asked` field, "Asked twice" example; duplicates 1–4%. |
| 6 | mesh-moments | New-identity warning, if it's ever offered. |
| 7 | mesh-moments | Engine row in Settings › About and three event-log lines. |
| 8 | — | Battery stays "typical phone" wording (no change). |
| 9 | station-page, station-states | Four key words per station; key renewed (signed by old key) vs unsigned key change. |

Engine needs: stage timings; a cached station record; `blockInfo.asks`; a station key-rotation record signed by the old key (and listed in the directory); engine version/update state. Key words come from the station key the same way the nym comes from a listener key (separate word list is fine).
