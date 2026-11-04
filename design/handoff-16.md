# Hand-off 16 (4 Nov 2026): artist photos and canonical artist names

The user's request: show the artist's photo while their music plays, tap to enlarge to full screen. Every track or set needs one common artist label, because uploads differ in case and spelling.

| Item | Change | Needs code? |
|---|---|---|
| `screens/now-playing` | 52 px artist photo left of the title; tap opens Artist photo. Names now `[Track title]`, `[Artist] · [Set name]`. | yes |
| `screens/artist-photo` | **New.** Full-screen viewer: photo, × close, name, track and set, credit, dots for more photos. | yes |
| `screens/artist-names` | **New.** One artist entry per artist, the tags that map to each, the no-photo state (initials), screen levels. | yes |
| `ui-spec.md` | New: Artist photo, Artist names. Changed: Now playing row. | — |

Needs from code (ingest and record):
1. An **artist registry**: id, canonical name, photos (with focus point and credit), and alias tags. Artists: three so far; names and photos come from the user, outside this repo.
2. **Matching at ingest**: case-insensitive, trim and collapse spaces, strip accents, treat `&` / `and` / `x` / `feat.` alike; unsure matches held for a person at upload.
3. The signed **file record carries the artist id**, and cue lists carry it per track for sets with several artists. Photos travel like other small files.

**No artist names or photos in this repo** (user, 4 Nov). Exported screens show `[Artist A]` and a placeholder photo. Ask the user for the real ones, and keep them out of git (for example a local, ignored folder).
