# Hand-off 24 (7 Nov 2026): Identity wizard

The first-run identity wizard asked for in design/feedback.md (7 Nov), from docs/keys.md on `pears-spike`, including the "as many sensors as possible" addition and the 24-word backup decision.

| Item | Change | Needs code? |
|---|---|---|
| `screens/id-welcome`, `id-gather`, `id-own-random`, `id-mix`, `id-nym`, `id-backup` | **New.** The six steps. Gathering fits any number of sources, lists dropped ones, and offers camera/mic opt-in. | yes |
| `screens/id-gather-states` | **New.** Camera/mic permission, machine-source fault (stops), Privacy mode (not used), few sensors (still enough). | yes |
| `screens/id-backup-states` | **New.** 24 words, seeder (recommended), publisher (required, 3-word check). | yes |
| `screens/id-desktop` | **New.** Seeder and browser control page: rail, mouse/keys pad, sources panel. | yes |
| `ui-spec.md` | New: Identity wizard and its states. | — |

Needs from `makeSeed`'s report, per source: id, plain name (or a host lookup table), bytes, state (ok, gathering, dropped with reason, off with reason, fault). Plus touch-point and move/key counts for step 3.
