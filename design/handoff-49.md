# Hand-off 49 (9 Nov 2026): station news bulletin choice

User, 9 Nov: each station has an optional bulletin, chosen on the upload page: BBC World Service (default), NPR News Now, or None. The BBC URL with its key may be used.

| Item | Change | Needs code? |
|---|---|---|
| `screens/admin-order` | `NEWS BULLETIN` card under Station colour (BBC default / NPR / None), rail item News. | yes |
| `ui-spec.md` | Upload page row; News on the headphone button › Source row updated (None case). | — |

Full feed URLs (BBC includes the key): `comms/news-feeds.local.md`, gitignored. Keep the BBC URL in the server's station config; send it in the signed announcement; never commit it.
