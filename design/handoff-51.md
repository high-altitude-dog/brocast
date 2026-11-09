# Hand-off 51 (9 Nov 2026): earcup three runs + codecs, busy Swarm, Buffer axis

User, 9 Nov: agreed to all of the queued points (feedback 2 Nov earcup/codec, 4 Nov Swarm lanes and Buffer labels).

| Item | Change | Needs code? |
|---|---|---|
| `screens/earcup-runs` (new) | Three runs in one test, result from their average, confidence from their spread, dot strip, Saved for AAC. | yes |
| `screens/earcup-runs-states` (new) | Thresholds, AAC / LDAC / SBC examples with your 2 Nov numbers, failed run, codec on Calibration, saved per codec, two codec-changed sheets, longer reconnect note. Codec parts **later** until you confirm the codec can be read. | yes |
| `screens/swarm-busy` (new) | 8+ listeners: 6 busiest + `+N`, rate-weighted lines, swap rules. | yes |
| `screens/buffer`, `buffer-states` | Axis labels thinned, frontier label backed. Kept the canvas edits (10 Hz; Minimal = AHEAD tile only). | small |
| `ui-spec.md` | Sections: Earcup test · three runs and codecs; Swarm · busy; Buffer axis. | — |

FYIs noted: BBC default until a station sends its choice; bulletin loudness matched to the music (+18 dB max) — both stand.
