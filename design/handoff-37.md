# Hand-off 37 (8 Nov 2026): Mesh card on Now playing; counted split

User: yes, split "Be counted as a listener" from "Use satellites"; and a section on the home screen showing the mesh's activity.

| Item | Change | Needs code? |
|---|---|---|
| `screens/now-playing` | Mesh card under the Auto row: live mini map, Connected, In, Passed on; tap opens Swarm. Use satellites text: `GPS time only · your position stays on this phone`. Board 1030 tall. | yes |
| `screens/home-mesh` | **New.** Card states: busy, you and one other, station only, receive-only, station offline, starting. | yes |
| `screens/settings` | Listening: new `Be counted as a listener` switch (default on); Use satellites no longer means "not counted". | yes |
| `ui-spec.md` | Now playing row, Mesh card states, Settings split. | — |

Engine: being counted must follow the new switch, not the satellites switch. Wording stays generic ("Mesh", "peer-to-peer"); no library or product name on screen.
