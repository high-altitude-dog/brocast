# Hand-off 48 (9 Nov 2026): Satellites gesture hint

User, 8 Nov: on the first opening of Satellites, or the 3rd opening without using gestures, show an effect that prompts two-finger navigation.

| Item | Change | Needs code? |
|---|---|---|
| `screens/satellites-hint` | **New.** Peak frame, five keyframes, timing, Reduce-motion card, rules. | yes |
| `ui-spec.md` | New section Satellites · gesture hint. | — |

Needs: a per-install counter of 3D openings (≥ 2 s) and a "two-finger gesture used" flag; a scripted camera move (zoom 15 %, tilt +17°, ease back to the exact prior camera) that any touch cancels; overlay finger marks drawn above the scene. Tour waits for the hint.
