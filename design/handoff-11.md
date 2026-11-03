# Hand-off 11 (2 Nov 2026): Earcup result redesign

From `feedback.md`, "2 Nov: Earcup test result screen is unclear". All seven points covered.

| Item | Change | Needs code? |
|---|---|---|
| `screens/earcup-result` | **New.** Headline in words with no signs (`play 182 ms later than the phone's speaker`), confidence in words, a two-click timeline, a labelled "What gets saved" breakdown ending `Brocast will send audio 202 ms early`, the renamed Mic check, Share this result, Save and Try again. | yes |
| `screens/earcup-result-states` | **New.** Unusual direction (your real Galaxy run: 39.7 ms earlier, so 19.7 ms late), the three confidence levels, share-switch states, portable speaker wording. | yes |
| `screens/earcup-test` | 15 s, playback stops; step 3 now points to Earcup result. No Apply now. | text |
| `screens/calibration-prompts`, `screens/headphone-lag` | No Apply now; 15 s. | text |
| `ui-spec.md` | New: Earcup result, Earcup result states. Changed: Calibration (Applying a value), Earcup test steps 1 and 3. | — |

Rule for this screen: no signs. A positive saved value reads `early`, negative reads `late`; the measured gap reads `later` or `earlier`.

Confidence thresholds (5 and 15 ms) are starting values: change them if the tests suggest others.
