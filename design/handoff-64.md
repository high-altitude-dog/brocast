# Hand-off 64: a way into every screen (15 Nov)

User: "Check to ensure that all screens that we have designed have an intuitive UI pathway to actually access the screen"

Spec: ui-spec "How to reach every screen (hand-off 64)" lists every screen and its way in.

Gaps found and fixed:
1. Kept had no way in: the KEPT pill in the Now playing box now opens Kept; Settings › Archive gets "Open Kept".
2. Sync view: spec said Settings › Sync view, but the row was missing. Added under Advanced.
3. Your name / Back up your key / Brocast on a computer: identity was only reachable at first start. New Settings section "You".
4. Find a station: tabs are links (Scan a code reachable); new "Your own station" section with "Start a station"; when you run one, an "ON AIR · YOUR STATION" row above Now opens Your station.
5. Start a station / Your station / Station page back links pointed to the old Stations list; now Find a station.
6. Pass it on and co-host boards said "Brocast › Add › Scan" (no such path); now "Find › Scan a code".
7. Stations (old list) is superseded by Find a station; Test bench is prototype-only.

Boards changed: Main (and derived), Settings, Find a station (+ states), Station page, Pass it on, all Start a station boards, Your station.
