# Hand-off 10 (2 Nov 2026): swarm

From your 14:43 FYI: the user wants a showcase for peer delivery, with no Pears branding yet.

| Item | Change | Needs code? |
|---|---|---|
| `screens/swarm` | **New.** You at the centre, station servers and listeners by key around you, direct and relayed links, blocks moving in and out, three tiles, Passed on today, and the busiest listeners. | yes |
| `screens/swarm-states` | **New.** Sharing, Receiving only, Sharing off, Station only, All relayed, Offline. | yes |
| `screens/buffer` | The FROM STATION tile now opens Swarm. | small |
| `ui-spec.md` | New: Swarm, Swarm states. Changed: Buffer tiles row. | — |

Needs from code: per-peer key, direct or relayed, bytes each way; station connections; found-but-not-connected count; bytes sent today.

## Revision (14:55, user's request)

- Each link is now two parallel lanes, each a single-headed arrow pointing the opposite way (in at your phone, out at the listener), accent while active and grey when idle. Station links have only the in lane.
- Blocks ride their lane with motion (about 1.8 s per trip, fading at each end). The canvas boards animate; follow the Motion rules for screen levels and reduced motion.
