# Hand-off 08 (2 Nov 2026): live buffer

The user asked for a screen that shows the live state of the buffer: the waveform moving through a thin playout line, track and set separation, BPM, and the tail still to play being built from blocks flying in.

## What changed

| Item | Change | Needs code? |
|---|---|---|
| `screens/buffer` | **New.** Waveform around the playhead (4 bars a second), one 12 px tile per 1 s block ahead, then a log scale out to the frontier and on to +24 h. Blocks fly in at the frontier (solid from the station, outline from listeners). Set band, track and file lines, beat dots, BPM, three tiles and an Up next list. | yes |
| `screens/buffer-states` | **New.** Filling, Thin, Missing block (with a silent second marked), Offline, plus motion by screen level. | yes |
| `screens/now-playing` | The AHEAD tile is now a link to Buffer. No visual change. | small |
| `ui-spec.md` | New: Buffer (axis, elements, motion, needs from code) and Buffer states. Changed: Now playing tiles row. | — |

## Needs from code

1. A **waveform summary** per file in the signed file record (for example one byte per 250 ms, about 14 KB an hour), made by the packager.
2. **BPM per track**, and a beat grid if possible, from the packager. It ties in with the light-track beat detection question.
3. **Download state:** the frontier, holes ahead, the arrival rate, and each block's source.

Without 1 and 2 the screen still works: show the tiles and lines without bars, and hide BPM and the beat dots.
