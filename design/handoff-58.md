# Hand-off 58: Where it came from (15 Nov)

User: "Lets design something so the user can see where they obtained each block from"

Canvas: Where it came from, Where it came from · views. Receiving boards updated (avatar colours).
Spec: ui-spec "Where it came from (hand-off 58)"; Receiving box rows "Where" and "Listeners" amended.

## Build
1. Engine: record the source of every verified block (`blockInfo.peer`, `via`, `ms`) into a per-set source log: a table of sources (key, nym, country at the time, route) plus one byte per block. Store with the set; delete with it; never upload. This is also what the Receiving box needs to show real senders (your 15 Nov note: "until the engine reports per-block sources").
2. Screen "Where it came from": header + share hero + stacked bar; Listener / Country / Route switch; the 60-per-row map (one row = one minute); the list as legend; footer.
3. Colours: blue #3987e5, orange #d95926, aqua #199e70 for the top three of the current view, slate #4A525C for the rest, station striped grey. Not the station accent.
4. Interactions: tap row to isolate; tap or drag on squares for the block card; Proof › opens Block provenance.
5. Entry from the Receiving box map, and from Library / Kept set rows.
6. Receiving box: the top-3 listener avatars use the same blue / orange / aqua.

Screens: design/screens/block-sources.png, block-sources-views.png, home-receiving.png, receiving-states.png.
