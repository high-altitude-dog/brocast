# Hand-off 47 (8 Nov 2026): News on the headphone button

User, 8 Nov: play/pause on the headphones while listening fades the music to 20–30 %, plays the latest hourly news bulletin over it, fades back up. On by default; music level and fade speed set by the listener. Non-commercial, open source.

| Item | Change | Needs code? |
|---|---|---|
| `screens/news-now-playing` | **New.** Now playing with the news card (under the track, above the clock). | yes |
| `screens/news-settings` | **New.** Settings page: switch, source, level slider, fade down / up, prefetch on Wi-Fi, preview, fade diagram. | yes |
| `screens/news-states` | **New.** Getting / playing / ending, failures, button rules, rules. | yes |
| `screens/settings` | Listening gets the row `News on the headphone button`. | yes |

Engineering: MediaSession play/pause → news while playing; gain ramp on the music (equal power) with the set still running; second player for the bulletin; RSS parse → newest enclosure; 10 s timeout; prefetch job on unmetered networks.
**Feed:** the user's BBC URL (podcast.voice.api.bbci.co.uk … ?api_key=…) carries a BBC key; keep it out of git. Put the default feed URL in the **station config / signed announcement**, not in code. `podcasts.files.bbci.co.uk/p002vsmz.rss` is 404. NPR News Now (hourly, keyless public RSS) is the listed alternative; check its URL works.
