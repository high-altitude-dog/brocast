# Hand-off 34 (8 Nov 2026): Sync view, output icons, headphones' names

Your 2 Nov notes: Sync view (please design and refine), headset name on Now playing, lag labels.

| Item | Change | Needs code? |
|---|---|---|
| `screens/sync-view` | **New design** for SyncScope: readout, two-disc dial (seconds outside, beats inside, tempo-following), Seconds/Beats/Both, flash and click, Screen and Audio nudges, the by-ear reading with Keep as calibration. | yes |
| `screens/sync-view-states` | **New.** Reading two phones, audio-nudged banner, leaving resets with a Keep-it toast, keep-as-calibration sheet, No flashing, getting time, display-delay note. | yes |
| `screens/outputs` | **New.** Seven output icons; the output tile with icon, lag, name and codec; the pre-prompt, Android prompt and not-allowed path for Nearby devices. | yes |
| `ui-spec.md` | New: Sync view, Outputs and headphones' names. | — |

Answers to your five questions: (1) portrait two-disc dial, flash white 2 frames; (2) nudges under the dial, Audio in amber, always reset on leaving with a Keep-it toast; (3) both: an inner beat disc that follows tempo, with a toggle; (4) "This phone, by your ears: X ms behind/ahead" from the nudge's sign, and Keep as calibration; (5) the "Seeing a gap?" card with both refresh rates. Proposal needing engine work: remember a lag per Bluetooth device (by its name/address).
