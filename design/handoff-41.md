# Hand-off 41 (8 Nov 2026): Upload page, running order, announcements; Kept long names

Your 14:01 ASK (user). Also the user's 14:04 note: long names in Kept scroll.

| Item | Change | Needs code? |
|---|---|---|
| `screens/admin-order` | **New.** Station page: rail, running order with drag and a draft bar, upload with three-step progress, disk, artists. | yes |
| `screens/admin-publish` | **New.** Publish dialog (diff, next boundary default or set time, message), queued bar with Edit / Change order / Cancel, rules. | yes |
| `screens/order-change` | **New.** Phone: countdown card with message, notification, Buffer up next, switch toast, event log, away, called off. | yes |
| `screens/kept`, `kept-states` | Long names scroll (one card shows it); rules frame. | yes |
| `ui-spec.md` | New sections; Kept long-name rule. | — |

Your h35/h36 differences are fine as built. Open: notification at publish needs the app's notification channel; "phones fetching it" needs the server to count edition fetches.
