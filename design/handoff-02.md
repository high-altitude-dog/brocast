# Hand-off 02 (1 Nov 2026): the Teddington · Greenwich clock, and strict mode moved

The user made three decisions:

1. Clocks lead with UK time, branded **Teddington · Greenwich** as a deliberately quirky touch, with local time beneath.
2. Strict mode leaves the main screen. It moves to Settings › Advanced and applies **at events only**.
3. The next design round is the notification and lock screen (hand-off 03).

## What changed

| Item | Change | Needs code? |
|---|---|---|
| `screens/now-playing` | The dial's bezel reads `TEDDINGTON · GREENWICH`, curved along the top, with an accent meridian tick at 12 o'clock. `FLOWTIME ±0.4 ms` runs curved along the bottom. Inside: `GMT`, the time, its fraction, and `23:43:51 CET · local`. The dial is 206 px. Strict mode is gone; Use satellites gets a full-width row. The header gains a Settings button. | yes |
| `screens/settings` | **New:** Listening (Use satellites, Headphones, Local time under the clock), Screen (No flashing, Screen level), Advanced (Strict mode, events only, with My limit) | yes |
| `screens/now-playing-minimal` | Label `TEDDINGTON · GREENWICH · GMT`; local time beneath | yes |
| `screens/test-bench` | Label `TEDDINGTON · GREENWICH`; `GMT · local 23:43:51 CET` under the time | yes, small |
| `screens/event-log` | The footnote says event times are Teddington · Greenwich time | text only |
| `tokens.json` | New: `type.clockZone`, `clockLocal`, `bezelTop`, `bezelBottom`. Clock 30 px, fraction 15 px. | yes |
| `ui-spec.md` | Changed: Rules › Times, Now playing (Header, Brocast clock, Precision ring, Use satellites). New: Settings. Strict mode removed from Now playing. | — |

The example local time uses CET. The real line follows the phone's time-zone setting and hides itself when its offset matches UK time.
