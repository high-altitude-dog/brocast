# Hand-off 25 (7 Nov 2026): Beat counter

Your 14:20 ASK: a beat counter on Now playing, from `SetBeats.kt`.

| Item | Change | Needs code? |
|---|---|---|
| `screens/now-playing` | Beat strip under the clock: BAR, four beat cells (beat 1 wider), BPM, phase line in the lit cell, border flash on beat 1. Board is now 912 tall (fits a Pixel 10's 915 dp; scrolls on shorter phones). Tap opens Buffer. | yes |
| `screens/beat-states` | **New.** One bar as it plays; working out the beats (percent), no steady beat, tempo change, new set, reduced motion. | yes |
| `ui-spec.md` | New Now playing row: Beat counter; new section Beat counter states. | — |

Timing: switch beats at flowtime + output lag (when the listener hears it), on the display frame, so phones agree visually. Needs: analysis progress percent, and a "no steady beat" flag per region from SetBeats.
