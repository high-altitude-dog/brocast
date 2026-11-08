# Hand-off 39 (8 Nov 2026): Sync view line mode

Your 13:32 ASK (user's idea).

| Item | Change | Needs code? |
|---|---|---|
| `screens/sync-line` | **New.** Full-screen line mode: start mark, mm rulers in ms, 0.2 mm/ms line with a 3-frame trail, end line. | yes |
| `screens/sync-line-states` | **New.** Reading two phones (behind = higher line), why 0.2 mm/ms, beat or second, controls with per-phone screen lag, card check for density. | yes |
| `ui-spec.md` | Sync view Dial choice gains Line; new section. | — |

Physical speed from xdpi/ydpi (ydpi for the vertical sweep), corrected by the card check when saved. Line drawn at the display's presentation time for flowtime + screen lag. 124 BPM leaves 484 ms (about 97 mm) per beat.
