# Hand-off 54 (15 Nov 2026): Now Playing box

User, 15 Nov: the home screen is busy. Put everything about the track/set in its own box like Event log and Sync, called Now Playing: artist thumbnail, Stereo, Kept, Library bar. Taller, with a larger thumbnail. Stereo shrunk to the size of Kept so the scrolling track name gets more room.

| Item | Change | Needs code? |
|---|---|---|
| `screens/now-playing` | Track block → **NOW PLAYING card**: photo 96 × 96, title full column width (scrolls), artist · set, compact STEREO and KEPT pills (26 px, 44 px touch), Library bar at the bottom. Bookmark badge on the photo removed (KEPT pill replaces it). | yes |
| `screens/news-now-playing`, `station-colour`, `admin-colour` | Re-rendered from the new Now playing. | — |
| `ui-spec.md` | Now playing › Now playing box row (old row kept below it, marked before 15 Nov). | — |

More home-screen tidying is likely to follow; the user said "let's start" with this.
