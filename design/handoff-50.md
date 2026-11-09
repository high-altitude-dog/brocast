# Hand-off 50 (9 Nov 2026): station name in the header, colour per set, news sources, boards caught up

| Item | Change | Needs code? |
|---|---|---|
| `screens/now-playing` | Header: nym eyebrow, **`Beach Sync Radio ▾`** (opens Stations), `● ≈ 1.2 M listening` (opens Swarm). Icons 6 px apart. | yes |
| `screens/admin-order`, **`admin-set-colour`** (new) | Running order **COLOUR** column; per-set popover with Use the station colour + 21 swatches; draft until Publish. | yes |
| `screens/station-colour` | New section: colour change at the set boundary (beat 1, 2 s fade, crowd together); rules. | yes |
| `screens/news-settings`, `news-states`, `news-now-playing` | **BBC World Service is its own option** (built into the app with NPR), station's choice only pre-selects; `Latest: 10:00 bulletin` line; Finding the latest rule (newest pubDate, enclosure, guid cache). | yes |
| `screens/swarm-peer`, `swarm-peer-states`, `settings` | Matched to h46 as built: Holding = % of the library, Shared since they connected, Settings › Delivery › Blocked listeners · Unblock all. | small |
| `screens/satellites-states` | Very accurate verdict: one paragraph, no second line (user's change). | — |

User, 9 Nov: "I don't see BBC News in 'News on the button'… the app needs to find the latest bulletin from the RSS." Answers the h47/49 gaps: no "hasn't chosen yet" / "not set up" states needed (BBC is built in; station None has its own line). BBC URL with key: bake in from a gitignored file (`comms/news-feeds.local.md` has it), never commit.
Set colour needs: per-set `color` in the running-order edition; accent switch scheduled on the set's first downbeat at ear time.
