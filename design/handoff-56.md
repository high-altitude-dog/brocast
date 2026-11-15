# Hand-off 56: artist country flag (15 Nov)

User: "Lets add a country flag that corresponds to the artist. Find out the country of existing artists, and allow country to be selected when new artists are added."

Canvas boards: Station · artists and countries (upload page), Artist country on the phone; Now Playing (Main) updated.
Spec: ui-spec "Artist country flag (hand-off 56)".

## Build
1. Artist entry: add `countries` (array of 0–2 ISO alpha-2 codes, default empty). Include it in the schedule the phones fetch.
2. Upload page → Artists: Country column with a picker (search by English name or code, "Used by your artists" group, Clear, optional second country). New-artist card from an upload gets the same field, optional.
3. Phone: flag before the artist name in the Now Playing box (14 px emoji, one space); flag + country name under the name on the full-screen artist photo. Nothing when empty. Hidden at Minimal, lock screen and notification.
4. Accessibility: artist line label "[Artist], from <Country>, [set]".
5. Seed data for the four real artists: `comms/artists.local.md` (gitignored; keep names out of the repo). Two stay empty.

Screens: design/screens/admin-artists.png, artist-flag.png, now-playing.png (re-rendered), news-now-playing.png, station-colour.png, admin-colour.png.
