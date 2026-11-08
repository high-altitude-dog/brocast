# Hand-off 38 (8 Nov 2026): Co-listener countries

User: show which countries co-listeners are in. Their choices: from IP addresses; each phone off by default, asked once.

| Item | Change | Needs code? |
|---|---|---|
| `screens/countries` | **New.** Swarm countries card and list column, mesh-card line, ask-once sheet, Settings row, rules. | yes |
| `ui-spec.md` | New: Co-listener countries. | — |

Engine: a Show-my-country flag in each phone's announcement; an offline IP-to-country table (shipped, updatable over the mesh); per-peer country for direct, hole-punched and same-Wi-Fi peers only (relayed = unknown); the 3-listener minimum (proposal).
