# Hand-off 45 (8 Nov 2026): Satellites in 3D re-work

Brief: design/feedback.md "8 Nov: Satellites in 3D, re-work please". All four points answered.

| # | Ask | Design |
|---|---|---|
| 1 | Globe overflows | Box full width, edge to edge, 470 tall (420 on Orbits / Whole Earth). Camera fits the whole Earth at every level. |
| 2 | Dome smaller than the Earth | Dome radius 1.55 × Earth (was 0.55), camera 7° above your horizon. The sky is a bowl over the planet; low satellites sit beside it. |
| 3 | Too many lines | The tour: one random satellite's path every 6 s (400 ms fade out, 800 ms draw rise → set, label pill moves); stops on tap; × resumes after 3 s; no tour with Reduce motion. Full rules in ui-spec "Satellites in 3D: re-work". |
| 4 | Chips in the box | `YOUR SKY` + dots and `Dome not to scale · distances are real` moved under the box; tour caption under them. Controls stay in the box. |

Kept: Flat only from its switch, no snap, flags (card row renamed **Built in**; Galileo = United Kingdom, Germany, run by the EU Space Programme).
Screens: `screens/satellites.png`, `screens/satellites-3d.png`. Board tour example: C21; selected example: G12.
