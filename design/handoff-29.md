# Hand-off 29 (7 Nov 2026): Beat flash as an option

User: make the flash at the start of each bar a setting.

| Item | Change |
|---|---|
| `screens/settings` | SCREEN gets **Flash on each new bar**, off by default, between No flashing and Screen level. Disabled while No flashing is on. |
| `screens/beat-states` | New frame: beat 1 with the flash on (border accent 120 ms). Default frame says no flash. |
| `ui-spec.md` | Beat counter row and Settings table updated. |

Reduced motion never flashes, whatever the setting.
