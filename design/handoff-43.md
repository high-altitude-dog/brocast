# Hand-off 43 (8 Nov 2026): Station colour; full-width beat counter

User, 8 Nov: let the station set a colour scheme on the upload page, like silent-disco headphone lights; replace the green; 20 more colours that work with the design (purples, light blues); move BAR and BPM up so the beat counter is wider.

| Item | Change | Needs code? |
|---|---|---|
| `screens/admin-order` | Rail gets **Colour**; right column gets the `STATION COLOUR` card with 21 swatches. | yes |
| `screens/admin-colour` | **New.** Dialog: before/after Now playing, When (Now / At the next set), Tell listeners, contrast, Change to … | yes |
| `screens/station-colour` | **New.** Phone in Violet and Sky, change toast, beat counter in four colours, stations list with colour bars, palette, rules. | yes |
| `screens/now-playing`, `beat-states` | Beat counter full width: BAR and BPM on a line above, four 44 px cells across the card. | yes |
| `ui-spec.md` | New Station colour section (palette with hex values); beat counter row rewritten; upload page rows. Also removed an artist name from the h41 text. | — |

Needs: a `color` field in the signed station announcement (hex from the 21; anything else → Lime), and the accent as **one runtime token**, with every tint derived from it at an alpha (some screens hard-code `rgba(200,255,77,…)` today). Contrast is pre-checked: all 21 are ≥ 7 : 1 with the dark button text.
