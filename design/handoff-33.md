# Hand-off 33 (7 Nov 2026): Station directory

Your 13:48 ASK (no hand-off 32: that number went to a channel line only).

| Item | Change | Needs code? |
|---|---|---|
| `screens/stations` | **New.** Now, Following, signed Directory with search and tags, add by code or link. | yes |
| `screens/station-page` | **New.** Station header, on now, figures, next 12 hours, who runs it with key, on this phone. | yes |
| `screens/station-states` | **New.** Header entry (station name line ▾), switching, added by link, name clash, quiet, offline, share code. | yes |
| `ui-spec.md` | New: Stations. | — |

Decisions for the user: the home header gains a station-name line; switching waits for 5 s of the new station before cutting over; the directory is signed by a Brocast key the app ships. Station names here are invented examples.
