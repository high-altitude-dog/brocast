# Hand-off 46 (8 Nov 2026): Swarm listener details

User, 8 Nov: on Swarm, tapping a peer shows more about them, including their IP-address country (if available) and their nym.

| Item | Change | Needs code? |
|---|---|---|
| `screens/swarm-peer` | **New.** Bottom sheet over Swarm: nym (verified), key, to you / from you / round trip, country, connection, shared today, holding, app; Block. | yes |
| `screens/swarm-peer-states` | **New.** List rows led by nym (proposal), every country case, name cases (verified, clash, checking, no name), peer left, station node, rules. | yes |
| `ui-spec.md` | New section Swarm · listener details. | — |

Needs from the engine: per-peer RTT, bytes each way today, holding (blocks ahead, from their announcement), app/engine version, and the country only when their Show-my-country flag is set (h40 rules). Block needs a local blocklist the engine honours. The IP address is never shown.
