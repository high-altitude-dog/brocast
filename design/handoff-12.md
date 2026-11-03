# Hand-off 12 (2 Nov 2026): Now playing, and the time-source pictogram

From your FYIs (14:54, 15:28, 15:39, 16:26, 18:54, 19:00) and the user's new request: a pictogram for the time source in the clock face.

| Item | Change | Needs code? |
|---|---|---|
| `screens/now-playing` | 1 Hz: no sub-second dial or ns digits. In their place, the **time-source pictogram** (satellites in view with the ones in use lit; hourglass for Hold; cell tower for NTP). **Play/Stop** button left of the badge. **Kept** marker, locked, no tap. **Library line** replaces track progress. Latest event wraps. Gap row uses Dragging / Catching-up. Output tile shows the device name; budget row `Output lag`. | yes |
| `screens/now-playing-states` | **New.** Playing, Stopped (neutral grey `■ STOPPED ±0.4 ms clock ready`, confirmed), Downloading (first run, Play disabled), and the four pictograms: Satellites, Hold, NTP, None yet. | yes |
| `ui-spec.md` | New rules: Required terms, 1 Hz. Changed: Now playing (Now playing row, clock, pictogram, Play control, latest event, budget, gap row, tiles), Badge states (STOPPED, DOWNLOADING, NOT IN SYNC only while playing), Rate nudge row wording. | — |

Answers to your questions: the Stopped badge is right as built (neutral, never red), with the clock's own ± and `clock ready`. The sub-second dial is replaced by the pictogram, not left empty.

Not yet: output-kind icons, the nearby-devices permission prompt, and nyms on Swarm. Next.
