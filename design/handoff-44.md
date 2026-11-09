# Hand-off 44 (8 Nov 2026): real satellite data

User, 8 Nov: "Data still needed: see if you can find something." Answers h42 build gaps 1–3 (design/feedback.md).

| Item | Change | Needs code? |
|---|---|---|
| `design/data/gnss_table.json` | **New.** IGS satellite metadata as a per-PRN table: craft, name, launch date, mass in orbit, plane/slot, NORAD id. 150 satellites, 6 systems. | ship it |
| `design/data/parse_snx.py`, `README.md` | Rebuild script; source, terms (open incl. commercial, credit the IGS), refresh plan; CelesTrak GP + SGP4 for live positions. | yes |
| `screens/satellites`, `satellites-3d` | Card uses real values: `GPS IIR-M · NAVSTAR 59 · launched Nov 2006`, weight `1,080 kg in orbit today` (was a made-up 2,030 kg at launch), fleet from the table. Second sample card now E30 (E24 has no signal number today). | yes |

Notes: QZSS (Japan) and NavIC (India) are in the table; phones report them, so give them marks when you add them. Your 22:49 changes (flags, orbit line per satellite, no snap, no auto-Flat) stand; the boards still show code chips and lines to you.
