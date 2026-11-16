# Hand-off 61: Time signals on the beat (15 Nov)

User: "Animate the 'Time Signals' screen so it also synchronises to the bars of the music".

Canvas: Time signals (the scene is now animated) and Time signals · on the beat (live examples for GPS, internet time, holding at night, and no-beat ticks; four stills of one bar; rules). The boards use SVG animation at a fixed 124 BPM; in the app drive the phase from flowtime and the beat grid.

Build:
1. Per frame: beatPhase = ((flowtime - gridStart) / beatLen) mod 1, beatInBar from the bar counter. Pulse position = ease(beatPhase) along each lit line; phone flash = 1 - beatPhase; downbeat ring from barPhase.
2. Mast wave every beat (bright only when NTP is in charge).
3. Four beat dots on the phone pictogram; BAR / BPM label beside it.
4. No beat grid: 1 Hz ticks; stopped/out of sync: static. Reduced motion as spec.
5. Pause the animation when the screen is off or Time signals isn't visible.

Spec: ui-spec "Time signals on the beat (hand-off 61)". Screens: design/screens/time-signals.png, time-signals-beat.png.
