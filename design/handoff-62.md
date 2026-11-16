# Hand-off 62: Find a station (15 Nov)

Your FYI 22:01 (brief: design/feedback.md "15 Nov: Find a station").

Canvas: Find a station, Find a station · scan, Find a station · states (live: first-start pulse, radar, scan line). Now Playing header changed (Main); HomeReceiving, NewsNow and the colour boards regenerated with it.
Spec: ui-spec "Find a station (hand-off 62)".

Build:
1. Home header: row 1 Settings (left), Time signals, Headphone lag, Archive, spacer, Find (right). Row 2: nym / station name › (station page) / listening (Swarm).
2. Find button + first-start invite (filled, 2 pulsing rings, radar sweep, tip). Persist "seen" on first tap or first open of Find.
3. Find a station: tabs On the mesh / Scan a code; radar card driven by mesh directory discovery count; Now, Following, On the mesh lists; search + tags; Listen on rows.
4. Scan: camera, brackets, scan line; decode brocast://station/<key>; sheet with four key words, Follow / Listen now; set codes hand off to Pass it on.
5. States: searching, nothing found, offline, no search match.
6. After pick: existing switching flow; colour cross-fade 400 ms; toast with Undo 6 s.
Mesh directory engine side is not built yet (per brief); design assumes signed listings (hand-off 60).

Screens: design/screens/find-station.png, find-station-scan.png, find-station-states.png, now-playing.png, home-receiving.png, news-now-playing.png, station-colour.png, admin-colour.png.
