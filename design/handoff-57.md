# Hand-off 57: Receiving box under the clock (15 Nov)

User: "When the app is receiving / syncing music, the user should see this in a box that appears just below the Teddington/Greenwich clock. This will be a showcase app for [the P2P stack], so lets make this really awesome."

Canvas: Now playing · receiving (home screen with the box), Receiving · states.
Spec: ui-spec "Receiving box (hand-off 57)".

## Build
1. A `ReceivingBox` between the clock and the beat strip, present only while a set transfer is active; tap → Swarm.
2. Block map from the store's block bitfield for the set due soonest: 160 squares (group blocks if more), colour by source (listener / station), plus just-arrived (2 s), in-flight (requested), missing, failed (amber).
3. Top 3 sending listeners with flag, nym, same-Wi-Fi icon, rate bar; then the station's share. Swap rows only after 10 s.
4. Header rate + 60 s sparkline; footer verified-block count + ETA.
5. States: finding listeners, station only, block failed check, paused (mobile data), ready (folds after 4 s). Reduced motion and Minimal level as spec.
6. On screen say "peer-to-peer" / "listeners" / "the station". Never the library's name.

## Also, user 15 Nov: strip YouTube IDs from names
"The Youtube string is currently showing in squared brackets in the playlist. We don't need this. Ensure it's stripped in the future, and delete it where it appears in existing entries."
- On upload / tag read: remove a trailing `\s*\[[A-Za-z0-9_-]{11}\]$` from track and set titles (yt-dlp style). Leave other brackets (`[Live]`, `[Remix]`) alone.
- One-off migration: apply the same to every existing entry in the library/schedule, then republish so phones pick up the clean names.
- Spec: ui-spec "Track and set names: no YouTube IDs".

Screens: design/screens/home-receiving.png, receiving-states.png.
