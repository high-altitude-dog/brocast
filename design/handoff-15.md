# Hand-off 15 (4 Nov 2026): Stereo pair

The user asked for a stereo mode: one phone plays Left, the other Right. Timing needs nothing new (both follow flowtime); the Test bench's Stream channel already does the channel split.

| Item | Change | Needs code? |
|---|---|---|
| `screens/stereo-pair` | **New.** Picture, This phone plays (Left, Both, Right), Partner card once paired, Stereo image bar (combined ± of the two phones), set-up advice. | yes |
| `screens/stereo-states` | **New.** Now playing chip (`L LEFT`), not paired, both on one side, partner stopped (falls back to Both), too loose, mono music. | yes |
| `screens/settings` | New LISTENING row: Stereo pair. Board now 1408 tall. | small |
| `ui-spec.md` | New: Stereo pair, Stereo states. Changed: Settings › Listening. | — |

Needs from code: the channel setting outside the Test bench; pairing (reuse the two-phone code exchange) and a small link carrying each phone's side, ± and liveness; whether a file is mono. Without pairing, the screen still works as a plain channel choice.
