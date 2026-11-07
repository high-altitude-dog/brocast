# Hand-off 22 (7 Nov 2026): Data and sharing, Advanced

The tuner for every setting in `docs/pears-spike-design.md` §6 (pears-spike branch), with predicted against measured cost.

| Item | Change | Needs code? |
|---|---|---|
| `screens/data-sharing-advanced` | **New.** Cost card (predicted from this phone's last 7 days vs today); Auto's adjustables; Keep ahead two-thumb log range; Wi-Fi share mode and upload limit; mobile download limit and share opt-in; connections, findable, memory, allowance; floors. | yes |
| `screens/data-sharing-advanced-states` | **New.** Custom profile (fifth button, amber dots, reset, Undo); before → after while dragging; running over the prediction with one suggested fix; first-day typical-phone prediction. | yes |
| `screens/data-sharing` | Advanced row now links to Advanced. | link |
| `ui-spec.md` | New: Data and sharing: advanced, and its states. | — |

Needs from the engine: per-setting cost model (battery, data per network, RAM, storage) calibrated from 7 days of this phone's history; today's figures per network. Decision for you or the user: a manual change makes the profile Custom and stops Auto.
