# Work queue

Work agreed but not started. Newest first. Move an item out when it is built.

## Server side

- **Artist registry** (4 Nov, from design hand-off 16): one entry per artist with a canonical name, photos (focus point and credit) and alias tags. The names and photos are held by the user and stay out of this repo.
- **Matching at ingest:** case-insensitive, trim and collapse spaces, strip accents, treat `&`, `and`, `x` and `feat.` alike. Anything unsure is held for a person to confirm at upload, never guessed on a phone.
- **Artist id in the signed file record,** and per track in cue lists for sets with several artists.
- **Photos travel like other small files** (compressed, fetched once, kept). Until then they are copied to the phones by hand (see `artist-photos/` in `.gitignore`).
- **Upload page:** a field for the artist and a place to confirm unsure matches.

## Phone side, waiting on the Pears spike

- Real nym from the listener's keypair, listener count and Swarm; Buffer from per-block arrival data. These are built with marked demo data until then.
