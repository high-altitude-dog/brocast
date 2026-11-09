# Hand-off 42 (8 Nov 2026): Satellites in 3D

User, 8 Nov: make the Satellites graphic 3D, the Earth's curve with you at top centre, satellites above, pinch to zoom like Google Earth on a tablet; add country, orbit, weight and current distance to the satellite box.

| Item | Change | Needs code? |
|---|---|---|
| `screens/satellites` | Sky plot replaced by the 3D dome view with overlays; the satellite card gains craft, live distance, country, orbit, speed, weight, fleet. | yes |
| `screens/satellites-3d` | **New.** Zoom levels (Orbits, Whole Earth), Flat, gestures, other-country cards, below-horizon card, access, rules. | yes |
| `ui-spec.md` | Satellites rows rewritten; new Satellites in 3D section. | — |

Your call: the 3D renderer (GL, Filament, Compose canvas with your own projection — the boards were drawn with a plain perspective camera and spherical Earth). Needs: a bundled land outline (world-atlas 50 m land, ~100 kB, public domain), a satellite table (system + number → craft, launch year, weight, operator, country), and per-satellite position from the broadcast orbit so distance and rate update each second. Earlier per-satellite az/el/C/N0 need still stands.
