# Hand-off 23 (7 Nov 2026): Block provenance, Data log

The first two frontier ideas from design/feedback.md on `pears-spike` (7 Nov, items 1 and 3), using the `blockInfo` and `ledger` fields in docs/engine-api.md §6.

| Item | Change | Needs code? |
|---|---|---|
| `screens/block-provenance` | **New.** Tap a near-zone block on Buffer: sheet with sender, route diagram (same Wi-Fi, direct, hole-punched, relayed via blind relay, station), ms, size, and a five-step chain of trust (station key → signed tree → proof → block hash → plays). All details lists every `blockInfo` field. | yes |
| `screens/data-ledger` | **New.** Data log: today/month, totals in GB and listening hours, Wi-Fi vs mobile cards, where the bytes went (music, proof and protocol, duplicates, DHT), November day bars. | yes |
| `screens/data-sharing` | New Data log link row above Advanced. | link |
| `ui-spec.md` | New: Block provenance, Data log; Data and sharing row. | — |

Needs from the engine: the block's own hash in `blockInfo` (for the chain), and daily history for the month chart (`ledger` has today and month totals only).
