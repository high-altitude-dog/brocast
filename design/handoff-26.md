# Hand-off 26 (7 Nov 2026): Kept

User, 7 Nov: tapping Kept on the home screen shows everything complete and stored, with BPM, length, artist, thumbnail and more.

| Item | Change | Needs code? |
|---|---|---|
| `screens/kept` | **New.** Summary, tempo map with a BPM range filter, sort chips, one card per item with a fingerprint (energy band, tempo line, track ticks), still-finishing row. | yes |
| `screens/kept-states` | **New.** Entry from the bookmark, a set opened (track list, scrub, replay), no track list, beats still being worked out, unkeep (after the beta), empty. | yes |
| `ui-spec.md` | Now playing: the Kept bookmark is its own 44 px target and opens Kept. New: Kept, Kept states. | — |

Data: library built items; waveform summary (energy); SetBeats (BPM range, median, tempo over time); cue list (track ticks and list; ShazamKit on the backlog could fill it). KEY, PEAK and LOUDNESS are later; hide until analysed. Artist photos stay out of git (placeholder in exports).
