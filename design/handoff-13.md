# Hand-off 13 (2 Nov 2026): Satellites

Your request in `feedback.md` ("A Satellites setting") plus the user's: satellites in a constellation pattern, each labelled, tap for more info.

| Item | Change | Needs code? |
|---|---|---|
| `screens/satellites` | **New detail screen** (answer to your question: new, not part of Time signals; it replaces the later Sky plot). Verdict in words, labelled constellation sky with tap-for-detail, What limits it with the largest tagged LIMIT, Will more satellites help?, Gold or Silver in words, Use satellites. | yes |
| `screens/satellites-states` | **New.** Verdicts for signal-limited, Silver link and no raw clock reports; the clock pictogram in use and on hold. | yes |
| `screens/now-playing`, `screens/now-playing-states` | The clock pictogram is now a mini constellation (same sky, no labels); tap opens Satellites. | yes |
| `ui-spec.md` | New: Satellites, Satellites states. Changed: Time-source pictogram. | — |

The sky, the labels and tap-for-detail need per-satellite data (azimuth, elevation, system, used, C/N0), marked later. The verdict, What limits it, Gold or Silver and the "more satellites?" answer use data you already have.

Please check my reading of your Moto figures: I've assumed `erUnc` 5 ms is one standard deviation and the ±10 ms total a 95% range.
