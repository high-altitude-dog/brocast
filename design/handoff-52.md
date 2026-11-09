# Hand-off 52 (9 Nov 2026): Time signals sources as a picture

User, 9 Nov: the Time signals pictogram looked like a mesh with 4 nodes; use a vastly exaggerated Alexandra Palace-style radio tower for NTP, and show sunrise and sunset for the user's location.

| Item | Change | Needs code? |
|---|---|---|
| `screens/time-signals` | SOURCES node diagram replaced by a scene: satellites (GPS), lattice mast on a brick tower (NTP), phone on the ground (FLOWTIME) with an hourglass (hold), sun on its arc, sunrise/sunset times. Board 930 tall. | yes |
| `screens/time-signals-scene` (new) | GPS in charge (day), internet time in charge, holding (night), the clock-face mini mast, rules. | yes |
| `ui-spec.md` | Time signals › Sources as a picture. | — |

Needs: sunrise/sunset from position + date on the phone (NOAA solar equations are enough; no network); fallback to the time zone's main city with "approx." Same taps as before.
