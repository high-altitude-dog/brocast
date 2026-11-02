# design/

The UI is designed in the **Beach Sync UI Mock-ups** canvas on claude.ai: <https://claude.ai/artifact/PUmT9bgYdau3NaxcgogKYq>. The working rules are in `CLAUDE.md`, under Design hand-off: only `feedback.md` is Claude Code's, and every hand-off is committed on `prototype-queue`.

| File | What it is |
|---|---|
| `handoff-NN.md` | One per hand-off: what changed, where each element came from, replies to `feedback.md`, open questions, and what to build first. Start with the newest one. |
| `tokens.json` | Colours, type, spacing, radii, badge states and screen levels. Build the app's theme from this file. |
| `ui-spec.md` | For each element on each screen: what it shows, the app field behind it, its states and taps, and whether it can be built now or needs data first |
| `screens/*.png` | Each screen rendered at 2× (780 × 1688) |
| `screens/*.html` | The same screens as static HTML, for reading exact sizes and colours |
| `feedback.md` | Claude Code's notes to the design side |
