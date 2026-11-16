# Hand-off 60: your own station, user's answers (15 Nov)

User's answers to the h59 questions: "1. Out of scope 2. The Pears mesh 3. Users can share their private key".

1. Legal: out of scope. The publish step loses the responsibility checkbox and the report/takedown lines.
2. Directory: the mesh is the directory. `List it in Stations` (on by default) at publish; listings are signed by the station key, nobody approves them. Stations footnote reworded (board Stations).
3. Co-hosts share the station's private key. New boards: Your station · co-host, Co-hosts · states. Your station gets a Co-hosts row.

Engine notes:
- Key handover: QR carries the station key encrypted with a 4-digit code shown on the giver; one use, 2 min expiry.
- Fork safety: before appending a new edition, sync the catalog to the latest length on the mesh; refuse to publish offline or when behind; rebase the local draft onto the newer edition and ask the user to review.
- Remember which editions this phone published (local only) for the station page's edition list.

Spec: ui-spec "Your own station" rows Publish, Your station, and new rows Directory, Co-hosts, Co-host safety.
Screens: make-station-publish.png, my-station.png, make-station-states.png, make-station-cohost.png, make-station-cohost-states.png, stations.png.
