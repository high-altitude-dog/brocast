# Hand-off 31 (7 Nov 2026): Camera test card

Your 21:33 ASK and 21:35 addendum.

| Item | Change | Needs code? |
|---|---|---|
| `screens/test-card-setup` | **New.** From Settings › Calibration: label, channel, flash on, click; warning; filming tips. | yes |
| `screens/test-card-dark`, `test-card-flash` | **New.** The card between flashes and on a flash frame. | yes |
| `screens/test-card-rules` | **New.** Timing and behaviour rules. | yes |
| `ui-spec.md` | New: Camera test card. | — |

Decisions to confirm with the user: Stereo click = 2 kHz left ear + 3 kHz right; flash rate capped at 3 a second (every other beat above 180 BPM); No flashing must be switched off for the test and comes back on after. Flowtime on the card shows the last flash's time and changes only at a flash, so video frames between flashes are identical.
