# Hand-off 18 (4 Nov 2026): channel control (Left · Stereo · Right)

The user decided: rename Both to **Stereo**; choose Left, Stereo or Right from the home screen; **Stereo at every app start**; protect against accidental pocket taps. Also applies your 13:50 FYI: no pairing.

| Item | Change | Needs code? |
|---|---|---|
| `screens/now-playing` | Channel chip right of the title (`STEREO` by default). Kept is now a small bookmark on the artist photo. | yes |
| `screens/stereo-states` | **Now "Channel control".** Stereo, tap shows a hint only, press and hold 0.8 s (ring fills), pick a side (sheet), changed with Undo (6 s), pocket guard, headphones. | yes |
| `screens/stereo-pair` | Simplified: no pairing, partner card or image bar; options Left, Stereo, Right; Stereo each start. | yes |
| `ui-spec.md` | Rewritten: Stereo pair, Channel control (replaces Stereo states). Changed: Now playing row, Settings › Stereo pair row. | — |

Pocket guard: ignore the chip while the screen is off, locked or covered (proximity), and for 1 s after screen-on; no channel control on the lock screen or notification. Channel is not persisted across app starts.
