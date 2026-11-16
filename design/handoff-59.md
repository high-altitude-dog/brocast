# Hand-off 59: build and publish your own station (15 Nov)

Your ASK 19:09 (brief: design/feedback.md "15 Nov: build and publish your own station"). User picked it next, 21:46.

Canvas: Start a station, Start a station · key, · music, · running order, Publish your station, Your station · on air, Your station · states.
Spec: ui-spec "Your own station (hand-off 59)".

## Flow
Stations › Start a station → 1 Name (name, line, tags, colour, news) → 2 Key (made on phone, 4 key words, 24-word backup required + check 3 words) → 3 Music (Files / share sheet, encode to Opus on phone, artist + country, YouTube ID stripping) → 4 Running order (draft edition, 24 h repeat, start time) → Publish (responsibility checkbox) → Share (QR/link/4 words, ask to be listed) → Your station (on air, who holds each set, keep-on-air switches, computer seeder).

## Engine work this assumes
- Station key pair + catalog writer on the phone (today only pears/tools/station.js on a laptop).
- On-phone encode to 48 kHz stereo Opus 20 ms; keep YouTube format 251 as-is. Pause < 20% battery or < 1 GB free.
- Per-set holder counts for "Who holds your sets" (this phone / paired computer / listeners).
- Publishing edition rules as the upload page: no sooner than 10 min + fetch time.
- Pairing a desktop seeder to a phone-made station (docs/desktop-seeder.md).

## Decisions I made (user can overturn)
- Backup of the 24 words cannot be skipped for a station (listener backup stays optional).
- One station per phone for now.
- New stations are unlisted; listing is a request to the directory keepers.
- Sets 10 min to 6 h, one file each.
- The publish checkbox is the only legal step in the app. Wording is a placeholder until the user decides (open question).

## Open questions (sent to the user)
1. Legal wording and who is responsible: is the creator alone responsible, and does Brocast need terms or a takedown contact?
2. Who keeps the directory, and what gets a station listed?
3. Should a station be able to have more than one publisher (co-hosts)?
