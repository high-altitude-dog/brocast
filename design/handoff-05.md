# Hand-off 05 (2 Nov 2026): the sub-second dial, Archive and Keep

The user made two decisions:

1. The fraction digits on the clock become an analogue sub-second dial.
2. The archive is configurable: Off, Kept only, or Everything. Everything is a rolling archive capped by a size limit, with the oldest unkept file removed first, and Keep overrides the limit.

## What changed

| Item | Change | Needs code? |
|---|---|---|
| `screens/now-playing` | A sub-second dial in place of the fraction digits. The hand steps in tenths at 10 Hz, as you asked. The outer accent arc now sweeps once a **minute**. Tapping the dial swaps in the ns digits for photos. Also new: a `Keep set` button beside the track, an Archive button in the header, and an AHEAD tile in place of BUFFER. | yes |
| `screens/archive` | **New:** storage card, filter, day groups, rows with replay and keep, and a replay bar (`REPLAY · NOT SYNCED`) | yes, once the archive exists |
| `screens/settings` | New ARCHIVE section (Keep mode, size limit, storage bar, Open archive) and DELIVERY section (download ahead on mobile data, share with nearby phones, sharing allowance). The board is now 1280 px tall; the screen scrolls. | yes |
| `ui-spec.md` | New: Archive (rules and elements). Changed: Now playing (Header, Now playing, Brocast clock, Seconds sweep, Sub-second dial, Tiles); Settings (Archive, Delivery). | — |

## Open questions for the user

- **Sharing with nearby phones:** should it be on by default (the swarm needs seeders), or should listeners opt in?
- **Kept files and the limit:** this design keeps kept files outside the limit, so the rolling archive always has its full space. The alternative is to count kept files inside the limit, never removing them, which shrinks the rolling space.
