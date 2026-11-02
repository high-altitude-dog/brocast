# Beach Sync App Design

Nov 1, 2026 · @Alex

## Summary and scope

Each listener's Android phone plays a time-stamped stream exactly D seconds behind the source, so every phone everywhere plays the same sample at the same instant, with no equipment on the beach. Teddington NPL time is the master timekeeper, reached through each phone's own GPS chip. One system runs a 24-hour radio station and silent discos on many beaches. The target is within about 5 ms of plan with wired headphones and satellites on; Bluetooth listeners will be looser, about 30–60 ms, and phones with satellites off about 5–10 ms with a nearby time server (estimate).

- **In scope:** Android 10 or later phones, with satellites on or off; a 24-hour station and silent discos on any number of beaches; pre-recorded audio first and live shows later; any country; millions of listeners with no per-listener traffic cost to the host; wired, USB-C and Bluetooth headphones.
- **Out of scope:** iPhones for now (see Backlog), any hardware at the beach, video.
- **Related:** the same idea as Synced Video and Audio Playout Design (one shared clock plus a published timeline), but with the clock in each phone instead of in a site server.

### Where each decision stands

As of 2 November 2026. Decided items are settled, Proposed items await agreement, and To test items are the chosen direction but unproven on real phones.

| Area | Position | Status |
| --- | --- | --- |
| Product | A 24-hour radio station and multiple beach silent discos on one system | Decided |
| Reach | Not limited to the UK; everything except the time reference is non-geographic | Decided |
| Time reference | Teddington NPL time is the master timekeeper | Decided |
| Scale | Millions of listeners, with no per-listener traffic cost to the host | Decided |
| Source | Pre-recorded to begin with; live may follow | Decided |
| Audio format | Opus at 160 kbps, 48 kHz stereo, from the packager to every phone | Decided |
| Source music | Any format or quality, transcoded once at ingest | Decided |
| Track files | Each track or set stays one whole file all the way to the phone, played from a signed schedule | Decided |
| Archive | Listeners can opt in to keep files and replay them after flowtime; the music can be cleared for this | Decided |
| Buffer and D | 60 seconds | Decided |
| Use satellites | An On/Off switch available at any point | Decided |
| No usable GPS | Keep playing and show the larger error; events limit the error rather than require GPS | Decided |
| Rate nudge | Auto by default; Manual has a log slider set to a recommended period, and Go | Decided |
| Names | No pkdns; domain names only for DHT starter nodes and NTP | Decided |
| Moving location | Streams and seeds can change location periodically | Decided |
| Screens | Jetpack Compose, designed in Claude Desktop and handed over through `design/` | Decided |
| Delivery | Signed blocks through a peer swarm with fixed-capacity seeds, replacing the CDN | To test |
| Transport | Pears building blocks first (Hyperswarm, HyperDHT, Hypercore), with Iroh as the comparison | To test |
| Block size | 1 second, fetched in bursts | Proposed |
| Download ahead | Phones fetch ahead on Wi-Fi and seed only while charging and not playing | Proposed |
| Automatic screen levels | Each phone steps its visuals down for heat, missed frames, underruns and battery | Proposed |

## Design rule: a lean timing path

The goal is to drive the spread (standard deviation) of every listener's audio lead or lag toward 0 ms. Lean code helps only where it touches timing, so the rule applies to the timing path: GPS time service, flowtime, playout callback, output.

- The audio callback (C++, on the AAudio thread) only reads flowtime, works out the sample position and copies samples that are already decoded. No memory allocation, locks, logging, network, file access or calls into Kotlin.
- Fetching, decoding, fitting the time line, UI and telemetry run on other threads, ahead of time, and hand results over through lock-free buffers.
- Changes to flowtime (a new GPS line, a change of source) are slewed in, never stepped.
- Everything else (UI, telemetry, server directory, calibration) can be ordinary code, as long as it stays off that path.

### Decisions flagged against the goal

| Decision | Effect on the spread | Option |
| --- | --- | --- |
| Allow Bluetooth headphones | Largest: 30–60 ms uncalibrated, about 5–15 ms calibrated, and it drifts after connecting | Decided: always compensate (reported, model default, measured) and show the delay and its uncertainty; the badge's ± includes it |
| Play on NTP before the first GPS lock and after long outages | 5–30 ms while it lasts | Decided: a strict mode toggle for listeners, off by default (see What the listener sees) |
| Accept Silver phones | 1–5 ms, against under 1 ms for Gold | Decided: no exclusion by label; the strict-mode limit and the organiser maximum use the sync error estimate |
| Dead band in the playout engine | At 1 ms it left up to ±1 ms uncorrected on every phone | Adopted: proportional-integral rate control, no dead band. The 0.1 ms dead band was dropped when simulation showed it parks each phone about 0.1 ms off |
| 20 minutes on the holdover clock before amber | Up to about 6 ms at 5 ppm drift | Decided: amber whenever the sync error estimate passes 5 ms; the holdover clock's growing estimate triggers it by itself |
| Wired output through USB-C adapters | An adapter may buffer audio outside the timestamp; varies by adapter (not measured) | Offer the microphone calibration for wired routes too |
| Phone sound effects (equalisers, spatial audio) | May add delay outside the timestamp; varies by model (not verified) | Use low-latency exclusive output, which may bypass them; check and calibrate per phone model |
| AAudio timestamp quality | Some phones may report coarse or jumpy positions (not measured) | Smooth timestamps with a line fit; measure per model in the GPS time probe |
| Stratum-1 and stratum-2 NTP | Small, and only while NTP is in charge: a stratum-2 server adds its upstream hop's error | The ranking counts root distance, so a stratum-2 server wins only when it is likely more accurate |
| UI updates, telemetry, event log | None while kept off the timing path; heavy CPU load can still delay the callback | Cap the UI at 10 updates a second; batch telemetry |
| Manual rate nudge | A held gap is deliberate sync error, for as long as the listener leaves it open | Decided: Auto by default; a held gap counts in the sync error estimate |
| Swarm work on the phone | Competes with the audio for CPU, and a crash would stop the audio if it shared the process (not measured) | Proposed: separate process at low priority, seeding only when not playing; the spread must not widen on the rig |
| Visuals and heat | Short audio-thread stalls cause clicks, worst on a cheap phone in the sun with the screen on (not measured) | Proposed: automatic screen levels that cut visuals before audio is affected |

## Clocks and terms

Three ideas carry the whole design. **Flowtime** says what time it truly is. **Android's output time stamp** says when, on the phone's own clock, a sample leaves the phone. **Headphone lag** says how long that sample then takes to reach the listener's ear. Everything else either feeds these three or displays them.

&#91;embedded content: how one sample finds its moment · five steps, one owner each\]

### Definitions

| Term | In plain words | Precisely |
| --- | --- | --- |
| UTC(NPL) | The UK's official atomic time | The national time scale generated by NPL's hydrogen masers and caesium clocks in Teddington, kept within a few nanoseconds of world UTC. Flowtime's target. |
| Flowtime | The app's best answer to “what time is it, really?” | The app's very best attempt to match UTC(NPL): the boot clock plus a correction (a gap and a drift rate) from whichever source has the smallest error estimate (GPS, the holdover clock or NTP). The only true time the audio engine and the displays use. |
| Sync error estimate | How far off this phone's sound might be | A 95% bound: flowtime's error estimate plus the uncertainty in the headphone lag. The one number every decision uses: the badge colour, the listener's strict-mode limit and the organiser's maximum. Measured live by each phone. |
| Boot clock | The phone's stopwatch since it was switched on | Android's `elapsedRealtime`: counts forward from switch-on, never jumps, keeps counting in sleep. Every time in the app is measured on it, and flowtime is a correction on top of it. |
| System time | The clock in the status bar | Android's wall clock. The network or the user can reset it at any moment, so the app never uses it. |
| Holdover clock | GPS time, remembered | The line from the boot clock to UTC fitted to recent GPS readings (gap and drift rate). It keeps running when GPS drops out and is one of flowtime's sources. Its error estimate grows the longer GPS is gone. |
| GPS chip clock | The GPS receiver's own clock | Mapped onto the boot clock by the phone's GPS link. |
| Gold, Silver | Labels for how a phone links GPS time to its boot clock | Gold: the GPS chip stamps each reading on the boot clock itself (typically under 1 ms). Silver: the app stamps the reading when it arrives (about 1–5 ms, narrowed by a per-model correction). Descriptive only: no decision uses the label, only the sync error estimate. |
| Device table | What we know about each phone type | Cloud table keyed by phone model, chip, Android version and firmware build: starting guesses and per-model corrections (Silver delay, miclag, screenlag, time-stamp trust). Each phone's own measurements always override it. |
| Stream stamp | When a sample was captured | The UTC time the packager gives each sample, counted from the sample number so it never jitters. |
| D | How far behind live everyone plays | The fixed playout delay: a sample stamped t is heard at flowtime t + D, on every phone and every beach. 60 s by default. With blocks published ahead, a phone can buffer further ahead than D. |
| Audio clock | The sound chip's own tick | The sample clock that drives the phone's audio output. It runs slightly fast or slow, which the playout engine corrects with its rate nudge. |
| Android output time stamp | When a sample leaves the phone | A pair reported by Android's audio system: frame F reached the audio hardware's output at time T on the phone's clock. The engine converts T to the boot clock, then reads flowtime at that instant. |
| Headphone lag | The extra delay after the sound leaves the phone | Time the sound reaches the ear minus the time Android's output time stamp says it left the phone. It covers the Bluetooth link, the headset's buffering and decoding, a USB dongle, and the DAC wherever it sits outside the phone; for a 3.5 mm jack it is close to zero. Measured per phone model, headset model and codec. Listeners see the same name on screen. |
| Miclag | Microphone delay | Time Android's input time stamp reports minus the time the sound reached the microphone, including the ADC. Measured per phone model with a wired loop, and subtracted in every microphone calibration. |
| Brocast clock | Any clock the listener sees | The app's very best attempt at displaying flowtime: each screen frame shows flowtime at the instant that frame lights up, to the nearest few nanoseconds, in UK time (GMT or BST). Never used for timing. |
| Screenlag | Screen delay | Time a point on the screen actually lights minus the frame's expected presentation time. It depends on where on the screen the point is and on the panel type, and is measured per phone model. |

### Worked example

One sample, followed from Android to the ear:

1. Android's output time stamp says frame 4,800,000 leaves the phone at boot-clock 1,000.000 s.
2. Flowtime at that instant is 22:00:00.000 GMT.
3. Headphone lag for this phone and Bluetooth headset is 182 ms, so the sound reaches the ear at flowtime 22:00:00.182.
4. With D = 60 s, the sample due at the ear then is the one stamped 21:59:00.182.
5. The playout engine puts that stream sample into frame 4,800,000. Every other phone does the same sum with its own numbers and lands on the same sample at the same instant.

**One owner per number.** Android owns the output time stamp, the time service owns flowtime, calibration owns headphone lag, and the controller owns D. So when headphones change only headphone lag changes, when GPS drops out only flowtime's source changes, and when Android resizes its buffers only the time stamp changes. Each error stays in one place and can be fixed on its own.

**Why headphone lag starts at Android's time stamp.** The moment the app writes a sample jitters by milliseconds and moves whenever Android resizes its buffers, while the output time stamp is the most precisely known instant and follows those changes. Each calibration also records the full write-to-ear delay. If a phone model's time stamps prove unreliable, the app switches it automatically to the write position with a fixed buffer, and the cloud's device table records which models need this.

## System overview

A sample captured at UTC time t plays at exactly t + D on every phone. The stream brings the samples; the phone's GPS chip brings the clock that decides when each one plays.

&#91;embedded content: system overview · cloud, phone, satellites\]

Phones never wait for each other or for the network: a late segment becomes silence, and the phone rejoins at the right point.

The diagram above still shows the CDN. Peer delivery replaces it, as Publishing and delivery describes below; the phone and satellite parts still hold.

## Publishing and delivery

The packager publishes each track or set once as a signed Opus file, with a signed schedule saying when it plays, and peers and seed servers carry both to every phone. The stamps need not be exactly UTC: an error there shifts every phone by the same amount, so only the phones' agreement matters. Delivery is the chosen direction, but it is unproven on real phones.

| Part | Job | Notes |
| --- | --- | --- |
| Packager | Transcodes each track or set once into an Opus file of 20 ms packets, cuts it into 1 s blocks, and builds the schedule (see Library and ingest) | Runs ahead of time with no live input, so it can move between batches. Publishing about 24 hours ahead gives blocks time to spread. |
| Timeline | A fixed start time, set once against NPL time. Sample k of a scheduled file is heard at start + (the file's start sample + k) / 48 kHz + D. | Counted from samples, never re-read from a clock, so moving or restarting a machine can't shift it |
| Signed log | One schedule log per stream, listing each file's start sample, and one shared content log holding each file once, as 1 s blocks | The publisher's Ed25519 key is the stream's identity, and the signed Merkle tree replaces a manifest and separate hashes. One active writer only, because two writers or a stale backup could fork the log. |
| Seed servers | Hold signed blocks and no secret key | Fixed capacity with a hard cap, from any provider. To move one: start the new seed, let it sync and announce, then retire the old one. |
| Swarm | Peers find each other by topic over HyperDHT and share blocks directly | Seeders are phones on Wi-Fi, charging and not playing. Sharing is on by default. A listener can turn it off, or limit it to scheduled Brocast global parties. It stays off by default in Privacy mode. Phones on mobile data download only. Many sub-topics avoid one overloaded DHT topic. |
| Starter nodes | First contact with the DHT | Saved peers first, then domain names across several domains, then built-in IP addresses |
| Controller | Holds each event: stream, D, state, stop commands, announcements and an optional maximum sync error | A small signed state. Its polling rate needs setting for millions of phones. |
| Time servers | NPL for the packager; regional servers that follow NPL time for phones without GPS, chosen from a signed list | Phones can't poll NPL directly at this scale: 62,500 queries a second per million phones at the prototype's 16 s interval |
| Telemetry (opt-in) | Receives each phone's sync quality, buffer health, errors and measured headset delays | No location is sent, only time quality |
| Live ingest (later) | Takes a DJ feed over SRT or RTMP, decodes it and locks its rate to 48,000 samples a second | Needed only for live shows. A standby would continue the same numbering, and a planned handover is needed to move it. A live show would be one file that grows as it plays. |

At Opus 160 kbps stereo a phone downloads about 72 MB an hour, so 1 million listeners need 160 Gbps, or 72 TB an hour. The swarm is how that avoids a per-listener cost: capacity grows with listeners, while seed servers stay a fixed cost. As arithmetic, a phone seeding at 1 Mbps for 8 hours supplies about 50 listener-hours. Published offload figures of about 55–78% are vendor claims for large live streams, and the same vendor reports peer connectivity below 30–40% for mobile-heavy audiences, and no data exists yet for this download-ahead design. The host also keeps the NTP server directory and its health probes, the headphone lag table for headsets, the miclag and screenlag tables for phone models, and the device table. D is 60 s, which sets the timeline offset and how long a live show would lag.

### Library and ingest

Every track or set is transcoded once, at the source, into one 48 kHz stereo Ogg Opus file at 160 kbps, whatever format or quality it arrived in. Phones only ever receive these files, so there is one sample rate and one decoder path. Transcoding can't add quality: a poor source stays poor, and a lossy source loses a little more, so lossless masters are preferred where they exist.

| Step | What happens | Why |
| --- | --- | --- |
| Keep the master | The host keeps the original file unchanged | Any later re-encode starts from the original, so quality is never lost twice |
| Check | Records each track's clearance, and flags clipped, silent or very low-bitrate sources for a person to decide | Only cleared tracks are published, because listeners can keep and share them |
| Convert | Decodes any supported format and resamples to 48 kHz stereo | The timeline counts 48,000 samples a second, and a phone never resamples |
| Encode | Encodes with libopus to 20 ms packets, at a 160 kbps average | The bitrate can vary, because blocks are counted in packets, not bytes |
| Tag | Writes the title, artist and set into the file's tags, and into a signed file record with a cue list for sets | The app reads the record. Other players read the tags. Both come from one source. |
| Cut | One Ogg page per second becomes one 1 s block of 50 packets | Blocks join back into the exact file, checked by its hash. Second b of a file is block b, so joining mid-file needs no index. |

### Schedule and track names

The signed schedule says which file plays when, and the files never change. Each entry gives a file's hash and its start sample on the timeline. Files follow each other to the sample, each with its own decoder state. There are no crossfades between files in the first version: a mix is published as one set file.

- **Track names:** the file record gives the title, artist and set, and a set's cue list stamps each track by sample. A phone switches the name when that sample reaches the ear.
- **Joining mid-file:** the phone starts decoding slightly before the point due now and discards that audio, so playback begins clean.
- **Repeats:** a track that plays again uses the same file, so it is stored and seeded once.
- **Schedule changes:** a later signed entry can replace the plan for time not yet played, without touching any file. The new file must already have reached phones.

### Archive

Listeners can opt in to keep files and replay them after flowtime. A kept file is byte for byte what the packager published, checked by its hash. Replay is ordinary offline playback with no flowtime or sync, so it stays outside the timing path.

- **Storage:** the archive is separate from the fixed seeding allowance, and the listener sets its size limit. Kept files stay outside that limit, so total storage can exceed it. A track takes about 3.6 MB for 3 minutes at 160 kbps.
- **Tracks joined part-way:** the phone fetches the rest of the file on Wi-Fi.
- **Other players:** some older players and car stereos can't play Opus. An export to AAC or MP3 can cover them later, outside the swarm.

## App architecture

One Android app (Kotlin) with its timing-critical audio path in C++. Eleven modules, each with one job:

| Module | Job | Android building blocks |
| --- | --- | --- |
| Eligibility | On first run, checks the phone is Android 10+ and whether its GPS chip reports raw measurements. It turns nobody away: other phones run on internet or nearby-phone time. After the first fix, it labels the GPS link (Gold or Silver). | `GnssCapabilities`, `GnssClock` flags |
| GPS time service | Computes flowtime, the app's best match to UK national time UTC(NPL), from GPS, the holdover clock and NTP. GPS runs only while Use satellites is on. | `GnssMeasurementsEvent`, `GnssClock` |
| Swarm client | Finds peers, fetches 1 s blocks ahead in bursts, checks the log's signatures and stores them. Seeds only on Wi-Fi, charging and not playing. Runs in a separate Android process at low priority, away from the audio. | Hyperswarm and Hypercore on Bare, through Bare Kit (to test) |
| Decoder | Keeps blocks stored as Opus and decodes only a few seconds ahead into 48 kHz PCM, with the same bundled decoder on every phone | libopus |
| Archive | Opt-in: keeps whole files and replays them offline, with no flowtime or sync. Never touches the audio thread of a live session. | libopus; plain file storage |
| Playout engine | Works out when each output buffer will reach the headphone socket and fills it with the stream samples due at that moment | Oboe over AAudio |
| Gap control | Decides how a gap closes when flowtime moves: Auto, or Manual with Go. Publishes one immutable state that the audio thread reads. | Plain Kotlin |
| Output route | Detects wired, USB or Bluetooth output, applies its reported delay, the model default or the microphone calibration, and the person's manual nudge | `AudioDeviceCallback` |
| Session service | Keeps playback, networking and, when on, GPS alive with the screen off | Foreground service: `mediaPlayback`, adding `location` only when Use satellites is on |
| Event log | Turns changes in the app's counters into plain-language events, on a low-priority thread | Plain Kotlin |
| Screen levels | Steps visuals between Full, Reduced, Minimal and Off for heat, missed frames, underruns and battery | `PowerManager` thermal status and headroom, `Choreographer` |
| UI | Join by QR code or link; shows sync, satellites switch, gap control, buffer, output route, volume and event log | Jetpack Compose, on the UI thread only |

The app never trusts the phone's wall clock, which can be a second off and can jump. All timing runs on the boot clock and the GPS model.

## GPS time service

Once a second the GPS chip reports GPS time and the boot-clock instant it applies to. The service fits a straight line through the last minute of these pairs and hands that line to the audio engine.

```latex
\begin{aligned}
t_{GPS} &= \text{TimeNanos} - (\text{FullBiasNanos} + \text{BiasNanos}) \\
t_{UTC} &= t_{GPS} + 315\,964\,800\ \text{s} - \text{leap seconds} \\
t_{UTC}(t_{boot}) &\approx a + b \cdot t_{boot}
\end{aligned}
```

315,964,800 s is the GPS start date (6 January 1980) in Unix time. GPS runs 18 s ahead of UTC, the leap-second count since 2017.

1. While a session runs, listen for GPS measurement events, with full GPS tracking requested (Android 12 and later) so they arrive every second. A beach gives open sky, so the first fix should take well under a minute.
2. For each event with a full bias value, compute UTC and pair it with a boot-clock time:
   - **Gold:** the chip's own boot-clock stamp (`GnssClock.getElapsedRealtimeNanos`, Android 10+ on chips that support it). Expected well under 1 ms.
   - **Silver:** the time the event reached the app. Expected about 1–5 ms (one developer's estimate), narrowed by a per-model average-delay correction from the device table.
3. Discard an event when the bias uncertainty is large. Reset the line when the chip's discontinuity counter changes.
4. Fit a (the gap) and b (the drift rate) over the last 60 good events, with a robust fit so one bad event cannot drag it, weighting each event by the chip's own uncertainty so a better signal counts straight away. Hand the pair to the audio thread without locking, and save it to storage every minute.
5. Take the leap-second count from the chip when it reports one, else from the controller.

### The holdover clock

The line from step 4 is the app's own GPS-derived clock: the phone's boot clock turned into UTC. It feeds flowtime. It keeps running when GPS drops out, so a phone in a pocket or bag stays in sync for minutes.

- **It remembers the drift rate, not just the gap,** so it keeps correcting for a fast or slow crystal during the outage.
- **It is measured against the boot clock, never the wall clock.** The wall clock can be reset at any moment; the boot clock only counts forward.
- **It survives an app restart.** The saved line is reloaded, and stays valid until the phone itself restarts.
- **It re-locks smoothly.** When GPS returns, the playout engine closes a gap of up to 20 ms with its inaudible rate nudge, not a jump.

Its error grows with how much the crystal's rate changes after GPS is lost, mainly through temperature. 1 ppm of rate change adds 0.06 ms a minute: 0.6 ms after 10 minutes and 3.6 ms after an hour. At 5 ppm that becomes 3 ms and 18 ms. The ppm figures are estimates for the bench test's 10-minute dropout to measure.

The holdover clock's error bound grows at 5 ppm, or 5,000 ns a second, as a starting value until phones measure real drift. The first version assumed 20 ns a second, which would have delayed NTP's takeover by days to months and badly understated the error.

### Choosing a time source

**Flowtime** is the app's very best attempt to match the UK's national time, UTC(NPL), kept by NPL's atomic clocks: the time from whichever source currently has the smallest error estimate, carried on the phone's boot clock. It is the only time the audio engine ever reads. Every source carries an estimate of its own error, and flowtime always follows the one with the smallest. NTP is never averaged with GPS: over mobile data it is off by milliseconds to tens of milliseconds, against under 1 ms for a Gold phone.

| Source | Error estimate (95% bound on the time the audio engine sees) | In charge when |
| --- | --- | --- |
| GPS | the chip's reported 1-sigma uncertainty × 2, plus the clock link (under 1 ms Gold, 1–5 ms Silver) | its estimate is the smallest: normally whenever it has a fix, and even before a full fix if the chip already knows time better than NTP |
| Holdover clock | the last GPS estimate, plus drift rate × time since the last GPS fix, with a margin for temperature | GPS is lost or worse, and its estimate is still the smallest |
| NTP over mobile data | half the fastest round trip, tightened by how closely the 4–5 servers agree; typically 5–30 ms | its estimate is the smallest: usually at start-up before any GPS time, or after a very long outage |

**Like for like.** Every estimate is the same kind of figure: a 95% bound on the error of the time the audio engine sees, with the clock link included. Mixing kinds misleads, because a pessimistic worst case loses to an optimistic typical figure even when it is the more accurate source.

**No flapping.** A source takes over only when its estimate beats the current one by at least 20% for 10 seconds, and the change is slewed in with the rate nudge, never stepped. Both figures are starting values for the tests.

NTP also does two jobs while GPS is in charge:

- **Safety check** every few minutes. A gap over about 50 ms between GPS and NTP means something is broken (a wrong leap-second count, a bad bias, a faulty chip), not just noisy. If two or more NTP servers agree with each other and not with GPS, the app switches to NTP and reports it.
- **Fleet monitoring** through opt-in telemetry: GPS-vs-NTP gaps from many phones show a system-wide fault, such as a wrong leap-second value from the controller.

Do not mix leap-smearing servers (Google's) with non-smearing ones. Cost: a burst of pings at start-up, then a few packets every few minutes.

### Without satellites

Some people keep GPS off for privacy, which is very reasonable, so this is a first-class mode with the best experience we can design. A phone with no usable GPS keeps playing and shows its true, larger error.

These estimates assume filtering leaves uneven network delay with a standard deviation of 30% of half the round trip. Takeover times come from a Monte Carlo simulation of 300 phones per row, through the real fit code, with no network changes and no shared carrier bias. None of this is measured on phones yet.

| Round trip to time server | Standard deviation between phones | About 95% within | NTP takes over after (median) | Best possible with 1 ppm drift |
| --- | --- | --- | --- | --- |
| 30 ms | 4.5 ms | ±9 ms | 71 min | 67 min |
| 60 ms | 9 ms | ±18 ms | 2.4 h | 2.1 h |
| 100 ms | 15 ms | ±30 ms | 3.9 h | 2.7 h |
| 150 ms | 22 ms | ±45 ms | 5.8 h | 3.8 h |
| 300 ms | 45 ms | ±90 ms | 11.5 h | 6.2 h |

These ways of improving on that are all proposed, and the nearby-phone method is the largest gain:

- **Nearby phones with GPS:** at events, sync from phones close by over Wi-Fi Aware, with a target of 1–3 ms. Allow two hops at most, and compare at least two peers against NTP so one bad peer can't shift the time. Peers learn only that a phone is nearby.
- **Holdover after peer sync:** carry that time on between contacts, growing at about 0.06 ms a minute per ppm.
- **Network correction table:** GPS phones report the NTP-versus-GPS gap by network, as opt-in telemetry. Other phones download the whole table and pick locally, so nothing is revealed.
- **Longer averaging:** reduces noise for radio listening, but not bias.

Turning GPS off doesn't hide a rough location if swarm peers can see the phone's IP address. So this mode defaults to no seeding or announcing and a new key each session, offers download from seed servers only (subject to the capacity cap), and states plainly what peers and servers can see. Social features, once built, are also off in this mode. These sharing rules are parked until the Pears proof of concept is validated.

### Choosing NTP servers

The app uses stratum-1 servers (wired directly to a GPS or atomic reference) and stratum-2 servers (one hop from one), chosen near the listener and ranked by their likely error.

1. **Directory.** The publisher keeps a signed list of public stratum-1 and stratum-2 servers and any regional servers that follow NPL time, with their stratum and access policy, and probes each one every few minutes. Servers whose policy rules out app traffic, or that fail the probes, are left out. The list can change as servers move.
2. **Location.** None needed. The app downloads the whole list and uses no GPS fix or IP-based region, so picking a server reveals nothing.
3. **Candidates.** Try a sample of the list, then concentrate on the fastest. Geography is only a hint, because network routes can be long.
4. **Ping by NTP.** Query each candidate a few times. The NTP round trip is the ping time that matters, because half of it bounds the error. Discard any reply that is not stratum 1 or 2, any kiss-o'-death, and any server that disagrees with the rest.
5. **Keep 4–5.** Rank by half the round trip plus the server's root distance (its own reported error against the reference clock, which covers a stratum-2 server's extra hop). Use the best 4–5, so one bad server can be outvoted even while another is unreachable. Keep the rest as spares.
6. **Re-rank** every 30 minutes and whenever the phone changes network, because round trips change.
7. **Be a polite client.** Never query one server more often than once every 4 seconds (NIST's published rule), back off on kiss-o'-death, and spread the fleet so millions of phones never land on one server.

**Why stratum 2 is allowed.** Stratum counts hops from a reference clock; it does not measure accuracy at the listener. There the uneven network path, bounded by half the round trip, dominates, so a nearby stratum-2 server often beats a distant stratum-1 server. Allowing stratum 2 also gives far more servers to choose from in every region, and the ranking in step 5 still counts its extra hop.

**Teddington is the master timekeeper.** Flowtime's target is UTC(NPL), the UK's national time scale, generated at NPL in Teddington from hydrogen masers and caesium clocks and kept within a few nanoseconds of world UTC. This is the one fixed reference. Everything else is non-geographic, and listeners can be anywhere.

- **GPS already delivers it, anywhere.** GPS broadcasts UTC as kept by the US Naval Observatory, and Galileo as kept by five European national labs. These differ from UTC(NPL) by nanoseconds (BIPM publishes the figures monthly), so a phone's GPS time matches Teddington far more closely than the app needs. Strictly, phones follow NPL time through GPS, not from NPL itself.
- **NPL's own NTP servers set the reference.** NPL runs five free public stratum-1 servers, ntp1 to ntp5.npl.co.uk at three locations, synchronised to NPL's atomic clocks independently of GPS and kept within ±50 ns of UTC(NPL). The packager uses them. Phones can't all poll them at scale, and NPL publishes no rate limit or app policy, so the project should agree any app traffic with NPL first.
- **The listener sees Teddington time.** Every brocast clock shows flowtime in UK civil time, GMT or BST, with the label, wherever the listener is.

## Playout engine

Every audio callback asks one question: in flowtime, when will this buffer's first sample reach the ear? It then fills the buffer with the stream samples stamped that time minus D.

```latex
n = (t_{flow,\,ear} - D - t_{stream\,start}) \times 48\,000
```

1. Open an Oboe output stream on AAudio in low-latency mode, 48 kHz float stereo, so Android does no resampling of its own.
2. In each callback, read the AAudio timestamp: frame F reached the output at monotonic time T. The buffer's first frame, number W, then leaves at T + (W − F) / 48,000 s.
3. Convert monotonic time to boot-clock time. The gap between the two only changes when the phone sleeps, so read it once a second.
4. Add headphone lag (see Clocks and terms) plus the person's nudge to get the ear time. Turn that into flowtime, then into stream sample n, less any gap being held.
5. Compare n with where playback actually is:
   - under 20 ms off: nudge the playback rate, up to 0.1%, which is inaudible. The nudge is proportional to the gap, plus an integral part that learns how fast this phone's audio clock runs, so the gap settles at zero rather than at an offset
   - the tracking period is 3 s. In the prototype's simulation it settles at about 2.5 µs, or about 7 µs rms with ±200 µs of jitter in Android's output time stamps, half the jitter of a 1 s period
   - no dead band: a 0.1 ms dead band left each phone parked about 0.1 ms off
   - over 20 ms off (start, after a dropout, after a headphone change): jump to n with a short fade
6. If the samples for n have not arrived, play silence for that buffer and keep counting. Never wait and never play late.

The 0.1 ms and 20 ms thresholds are starting values for the tests.

### Closing a gap

Tracking the audio clock and closing a gap are separate jobs. A phone's audio clock can be 80 ppm off, or 4.8 ms a minute, so tracking is always on. A gap appears when flowtime itself moves, and it closes in Auto straight away or in Manual on Go.

- **No step:** when flowtime moves, the new fit plus a held gap gives exactly the same stream position, and the gap then closes at a constant rate over the chosen period.
- **No overshoot:** the closing rate goes straight into the playback rate, so tracking never has to chase it. In simulation, 5 ms and 15 ms gaps closed with no jumps, staying within 15 µs of target.
- **Rate limit:** closing stays at or below 0.09%, leaving room to track the audio clock. A 5 ms gap takes at least 5.6 s.
- **Small corrections:** routine GPS and NTP updates under 0.1 ms are absorbed silently.
- **Large gaps:** above 20 ms, Auto jumps with a fade. Manual lets the listener slide or jump.
- **Audio thread:** it reads one immutable state holding the fit and its gap, with no locks or allocation.

**Output delay by route.** For wired headphones the AAudio timestamp should already cover the phone's output path. For Bluetooth, Android adds the headset's own delay report to that timestamp only when both the phone and the headset support it, so the app adds two more layers.

### Headphone lag for wireless headsets

The app always compensates a wireless headset's delay (Bluetooth, or a headset with its own USB radio dongle) by sending its audio that much earlier. Compensation starts the moment a headset connects, using the best layer available, and the app asks for a calibration the first time it sees a headset. The three layers, each overriding the one before:

1. **Reported:** whatever Android's timestamp already includes from the headset's delay report.
2. **Model default:** the app reads the headset's name and looks it up in a delay table kept in the cloud, keyed by headset model and phone model, because the same headset can differ between phones. The UI suggests that offset and says where it came from.
3. **Measured:** a microphone calibration. The listener holds an earcup or earbud against the phone's microphone and taps Calibrate. The app plays a short test sound through the headset and records it with the phone's built-in microphone. The output timestamp says when the sound left the app; the input timestamp of the matching moment in the recording says when it arrived. The difference is the real delay. While music is playing, the music itself can be the test sound, matched against the stream the phone is already playing, so calibration never interrupts the set; the sweep is the fallback for quiet passages.

- **Test sound:** a rising sweep (chirp) or a noise burst, repeated a few times and averaged, not a plain beep. A beep repeats itself, so the match can lock onto the wrong cycle; a sweep or noise burst gives one sharp match, even in wind and crowd noise.
- **Microphone path:** this is miclag, the phone's own recording delay including its ADC. Check that once per phone model against a wired loop and store it in the miclag table. Sound in air adds only about 0.03 ms per centimetre.
- **Keep the headset in music mode:** record from the built-in microphone in normal recording mode, never call mode, so Android does not switch the headset to its low-quality call profile. Test this per phone model.
- **Re-check:** Bluetooth delay can change after reconnecting and in the first minutes of use (one developer measured AirPods falling from about 220 ms to 155 ms over 20–30 minutes). The app offers a re-check after each reconnect and after 30 minutes.
- **Feeds the table:** with opt-in telemetry, measured delays update the model defaults, so later listeners start from better suggestions.

**Peer calibration.** A neighbour's phone can do the measuring: the listener holds an earbud or earcup against the other phone's microphone while the music plays.

- **What it measures:** everything the listener hears, end to end: flowtime, the audio path and the headphone lag. The measuring phone knows which stream sample should be playing at each flowtime and reports the gap.
- **When it counts as a calibration:** only when both phones' flowtime error estimates are small (under 1 ms) and the measuring phone's microphone path is calibrated for its model. Then the gap becomes the listener's headphone lag, labelled peer-measured. Otherwise it is shown as a check only, because the gap would also contain clock error.
- **Getting the result across:** the listener's phone shows a short code, the other phone scans or types it, and the result travels through the cloud.
- **Recording rules:** the measuring phone records from its built-in microphone in normal mode, so its own Bluetooth headset stays in music mode.

## Sync error budget

With wired headphones a Gold phone should land within about 2–5 ms of plan; the headphone route, not the clock, sets the limit. Every figure below is an estimate for the tests to confirm.

| Stage | Gold phone, wired | Silver phone, wired | Any phone, Bluetooth |
| --- | --- | --- | --- |
| Stream stamps vs UTC | same for every phone, so 0 between phones | same | same |
| GPS time to boot clock | under 1 ms | 1–5 ms | as the phone's grade |
| Engine (timestamp to buffer, rate correction) | about 1 ms | about 1 ms | about 1 ms |
| Output path after the timestamp | a few ms | a few ms | 30–60 ms with the reported delay alone; about 5–15 ms just after a microphone calibration |
| **Total vs plan** | **about 2–5 ms** | **about 3–10 ms** | **about 10–60 ms, depending on calibration** |

The Bluetooth row comes from one developer's AirPods measurements on iPhone, where the reported delay was 161 ms and the real one drifted from about 220 ms down to 155 ms over 20–30 minutes. Android headsets are untested. During a GPS outage the holdover clock adds the drift error described above. Before the first GPS lock, and after very long outages, a phone runs on NTP at about 5–30 ms.

Phones with satellites off or no usable GPS follow the figures in Without satellites. A gap being held adds to the sync error estimate until it closes.

## Event flow and failure handling

A radio listener opens the app and hears the station within seconds. At an event, a listener scans a code and joins the same timeline. A phone plays at once on the best time it has, and no failure ever makes it play late: it plays silence and rejoins instead.

1. **Before listening:** install the app. On Wi-Fi, the phone can fetch ahead and seed while charging, unless the listener turns sharing off. Nothing asks for location at install.
2. **Choose:** Use satellites and no satellites are offered as equal choices. The explanation appears once, and the app never asks again.
3. **Join:** open the app for the station, or scan an event's QR code or tap its link. The event may be the main station or, if allowed, its own stream (open question).
4. **Play:** output starts within seconds at the stream point due now, usually mid-track, on the best time available. With strict mode on, it waits until the error is under the listener's limit.
5. **Live:** the app keeps 60 s of audio in hand, and with satellites on the error tightens as GPS locks. The screen shows the sync grade and any tightening.
6. **End:** an event's stream ends on a stamped sample, so every phone stops on the same sample. The station carries on.

| What happens | What the app does | What the listener sees |
| --- | --- | --- |
| GPS lost | Runs on the holdover clock; switches to NTP once NTP's error estimate is smaller | “Holding”, then “Internet time” |
| Use satellites turned off | GPS stops at once; flowtime carries on from the last fit, then the best source | “Privacy mode”; the music doesn't stop |
| Use satellites turned on | Starts a fresh GPS fit; the gap closes under Auto or waits for Go | Tightening, once the estimate improves |
| Flowtime moves | Holds the gap, then closes it by Auto or Go | The gap and time left |
| Mobile data or peers lost for under 60 s | Plays on from the buffer and looks for other peers | nothing |
| Mobile data lost for over 60 s, with nothing stored | Silence, then rejoins at the right point | “reconnecting” |
| A seed server moves or fails | Finds another seed or peer by topic | nothing |
| No peer can supply a block and seeds are at their cap | Waits for a peer (proposed; open question) | “Waiting for audio” |
| Headphones changed | Re-reads the route delay, jumps to the right point with a fade | new route badge |
| Phone call or another app takes the audio | Stops output, rejoins at the right point afterwards | music back in sync |
| Phone's clock or time zone changed | Nothing: timing never uses the wall clock | nothing |
| App killed by the system | The foreground service makes this rare; on restart it rejoins | a short gap |
| Stream source fails, later for live shows | Standby ingest carries on the same numbering; otherwise stamped silence | silence |

## What the listener sees

One badge answers “am I in sync?” at a glance, always in words with an estimated error, never by colour alone. Beneath it, every correction, check and exception the app makes is shown, not hidden.

**Where screens are designed.** Screens are designed in Claude Desktop and built in Jetpack Compose. This document sets what each screen must do. Layout and visuals belong to `design/ui-spec.md` in the code repository, which overrides the mock-ups below where they differ.

**UI direction.** Give listeners as much information as possible, presented with frontier, instrument-grade visuals rather than settings lists. The badge stays readable at a glance; everything behind it is dense, live and honest about its own error. Proposed visuals:

- **Error budget bar:** a live stacked bar of the listener's ± figure split into flowtime, headphone lag and screenlag, on a log scale from nanoseconds to tens of milliseconds.
- **Sync trace:** an oscilloscope-style trace of this phone's offset from flowtime over the last minute, with its confidence band, so rate nudges and jumps are visible as they happen.
- **Precision ring:** a ring around the brocast clock whose width is flowtime's current error estimate; the nanosecond digits fade where the ring says they stop meaning anything.
- **Sky plot:** a live polar plot of satellites by direction and elevation, coloured by system and glowing with signal strength, with the ones used for time marked.
- **Source field:** GPS, the holdover clock and each NTP server as nodes sized by their error estimate, with the one in charge of flowtime lit and hand-overs animated.
- **Server map:** the NTP servers in use on a map, with live round-trip lines and their stratum and reference.
- **Calibration scope:** during a headphone-lag calibration, the recorded sound and the correlation peak that gives the measurement, so the listener sees exactly what was measured.
- **Crowd histogram:** a live histogram of every phone's sync error at the event (opt-in telemetry), with a “you are here” marker.

All of it runs on the UI thread, off the audio path, and animates only while visible. Anything that flashes follows the three-flashes rule.

&#91;embedded content: status screen mock-up · badge, five status rows, event log\]

The values are examples. The badge's words and colour follow the table below.

| Badge | Colour | Shown when |
| --- | --- | --- |
| In sync ± n ms | green at 5 ms or under, amber above | GPS is in charge of flowtime |
| Holding ± n ms | green at 5 ms or under, amber above | the holdover clock is in charge; it turns amber by itself once its growing estimate passes 5 ms |
| Internet time ± n ms | usually amber | NTP is in charge |
| Rejoining | amber | a data gap longer than D, or a jump of more than 20 ms |
| Silent: over the limit ± n ms | amber | the sync error estimate is above the listener's strict-mode limit or the organiser's maximum |
| Clock check failed | red | GPS and NTP disagree by more than 50 ms |
| Not in sync | red | no time source, or no stream |
| Privacy mode ± n ms | neutral | Use satellites is off. It is a choice rather than a fault, so it shows the true error without amber fault wording |

Every badge's ± figure is the phone's sync error estimate, and the colour follows that one number. So an uncalibrated wireless headset shows amber even on a perfect GPS clock, and a phone that loses GPS turns amber exactly when its drift starts to matter.

Always visible beneath the badge, under a brocast clock (flowtime shown in UK time, GMT or BST, to the nearest few nanoseconds; see Brocast clock below):

- **Clock source:** GPS with satellite count and drift rate (ppm); the holdover clock with time since GPS was lost; or internet time with its round trip. Tapping it opens the Time signals screen.
- **Rate nudge:** a small gauge of the current speed correction (up to ±0.1%) and the gap still to close, in ms. Jumps go to the event log.
- **Safety check:** the last GPS-vs-NTP result, its gap and how long ago. A failed check turns the badge red.
- **Buffer:** seconds of audio in hand, out of D.
- **Output:** the route (wired, USB, Bluetooth), the delay applied, where it came from (reported, model default, measured or peer-measured) and any manual nudge, with a Calibrate button for wireless headsets; tapping it opens the headphone lag view.
- **Event log:** every exception with its time, newest first, in plain words.

While an exception lasts, a banner says what is happening and what to do:

| Exception | Banner |
| --- | --- |
| GPS lost for over a minute | “No GPS: keeping time from the last GPS fix. Hold the phone in open sky to re-lock.” |
| GPS not yet locked | “Waiting for GPS: playing on internet time. Step into open sky.” With strict mode on: “Strict mode: silent until GPS locks. Step into open sky.” |
| Mobile data lost | “No mobile data: n s of music left in the buffer.” |
| Data gap longer than D | “Rejoining: the stream was lost for n s.” |
| Clock check failed | “Clock check failed: GPS and internet time differ by n s. Using internet time; reported to the organisers.” |
| Bluetooth headphones | “Bluetooth: using the typical delay for this headset. Tap Calibrate for tighter sync.” |

In Privacy mode, none of the GPS banners appear and nothing asks the listener to turn satellites on. Over an organiser's limit, the screen offers what could help, such as “Sync from nearby phones”.

Tapping the badge opens a details view with the sync error budget (for example ±3.2 ms = flowtime ±0.4 + headphone lag ±2.8) and the raw numbers (offset, drift, error estimates, satellites, NTP round trips, buffer, output delay, app version) and a button to copy them for support.

**Strict mode** is a listener setting, off by default.

- **Off:** the app plays whenever it has flowtime, including on NTP before the first GPS lock and after a very long outage. The badge shows the sync error estimate and its colour.
- **On:** the app plays only while the sync error estimate is under the listener's own limit, set with a slider from 1 to 50 ms (5 ms when first switched on). Over the limit it stays silent, shows the estimate and the fix (open sky, wired headphones, calibrate), and rejoins at the right point once it is back under.
- **Organiser maximum:** an event can set a maximum sync error through the controller. It works like strict mode for every phone at that event, and a listener's own limit can only be tighter.

### Use satellites

An On/Off switch sits on the main screen, one tap away, and works at any point, including during playback and at events. It is never locked or changed automatically, and the setting persists between sessions.

| Change | What happens | What the listener hears |
| --- | --- | --- |
| On to Off | GPS stops at once and position data is discarded. Holdover carries on, then NTP takes over once its error is smaller. | Nothing |
| Off to On, first time | Android's permission prompt appears only now. If it's denied, the switch stays Off and the music carries on. | Nothing |
| Off to On, with permission | GPS starts a fresh fit, and flowtime changes source by the usual rule: 20% better for 10 s | Usually nothing |

If Location is off for the whole phone, the app says so and links to those settings. If permission is permanently denied, it links to the app's settings page.

### Rate nudge control

The control sets how a gap closes. Auto is the default, so nobody has to understand any of this to be in sync.

- **Auto:** a gap starts closing straight away at the chosen or recommended period.
- **Manual:** a gap is held and shown. **Go** starts closing it and becomes **Pause** while closing. **Jump** moves straight there.
- **Period:** a log slider from 1 s to 5 min, set to a recommended period. The readout shows the real time left and the pitch change, such as 5 ms over 60 s at 0.14 cents.
- **Recommended period:** at events, the fastest allowed. Listening alone, about 1 minute for a typical gap. These are starting values to test.
- **Honest error:** a held gap counts in the badge. A held gap doesn't grow by itself, and it clears on the next rejoin.
- **Event limits:** if a held gap takes the phone over an organiser's limit, it goes silent under the existing rule and offers Go.

### Tightening

When the error estimate improves by at least 20% and holds for 10 s, the screen shows the phone tightening and the event log records it, such as “Sync tightened from ±3.2 to ±0.8 ms, 9 satellites”. The trigger is the estimate, never the satellite count.

- **Tightening is in flowtime:** the stream already tracks flowtime to microseconds, so a better flowtime opens a gap, and the stream then follows it. The screen shows both.
- **Where it shows:** a satellite lights up in the sky plot as it starts being used, the precision ring and the sync trace's band narrow, and the rate nudge gauge moves until the gap closes, then the label reads “Tight”.
- **Real change only:** if satellites arrive and nothing improves, the count updates quietly with no animation.
- **Likely size (estimates):** 5–30 ms down to under 1 ms on Gold from NTP to first lock, up to several ms when GPS returns from holdover, and often only microseconds from few satellites to many once locked.
- **Satellites off:** the same display works for NTP and nearby-phone sync, without the sky plot.

### Event log

The event log is one tap away and is built as an instrument rather than a plain list.

- **Opening it:** the newest event sits under the badge, and tapping it opens the log. One swipe up does the same, tapping a banner jumps to its event, and the playback notification has an “Event log” action.
- **Timeline scope:** lanes for CLOCK, AUDIO, NETWORK, OUTPUT and SCREEN run over the sync error trace, so you can see what caused each change in sync. Pinch to zoom from one minute to 24 hours, with repeats grouped, such as “Underrun × 3 in 2 min”.
- **Linked list:** the list under the scope follows it, and each selects events in the other.
- **Event detail:** what happened, how it affected the sound, what the app did and what you can do, with the numbers before and after.
- **Precise times:** timestamps use the clock's digits, fading where they stop meaning anything, and are labelled as Teddington time.
- **Staying safe:** a low-priority thread reads the audio thread's counters, so the log can't add jitter. The scope draws only while visible and falls back to plain text at Minimal.
- **Storage:** events stay on the phone in a rolling store, such as 7 days, and leave only if the listener copies them or has opted in to telemetry.

The log must open in two taps or fewer from any screen, and a new event must appear within about a second.

### Automatic screen levels

Each phone chooses its own level, so visuals give way before audio is affected. These are proposed, along with the thresholds below as starting values.

| Level | What the phone shows | When |
| --- | --- | --- |
| Full | Clock at display rate, live traces, sky plot, full light show if opted in | Capable phone, cool, no audio problems |
| Reduced | Slower updates, no animated visuals, simple full-screen colours for the light show | Warming up, low battery, or missed frames |
| Minimal | Badge and status text, about once a second, with the light show off | Hot, battery saver, or audio underruns |
| Off | Nothing drawn | Screen off or app not visible |

- **Fast down, slow up:** drop a level at once on an underrun or heat warning, and go back up after a clear period, such as 60 s.
- **Order of cuts:** defer non-urgent swarm work first when the buffer is healthy, then cut visuals. Audio is never reduced.
- **Light show:** the controller can reduce or remove flashes but can never turn the show on without opt-in. It also switches off when the sync error is too large, because an off-beat flash looks worse than none.
- **Clock honesty:** a lower level changes how often the clock updates, not its accuracy or its error estimate.
- **Listener control:** listeners can choose a lower level or ask for full, but step-downs for audio underruns and heat still apply.
- **Stays visible:** each change goes in the event log, such as “Reduced visuals: phone is hot”.

### Time signals screen

Every time signal the phone is using is listed with its quality and its role, so a listener or a support person can see why the badge says what it does.

&#91;embedded content: time signals screen mock-up · GPS, brocast clock, NTP servers, safety check\]

Server names and values are examples. S1 and S2 are stratum 1 and 2; PPS and GPS are the stratum-1 servers' reference clocks.

| Signal | What the screen shows |
| --- | --- |
| Flowtime | the brocast clock (flowtime in UK time, GMT or BST, to the nearest few nanoseconds), the source in charge of it (GPS, holdover clock or NTP) and its estimated error |
| GPS | satellites used and visible, count per system (GPS, Galileo, GLONASS, BeiDou), median signal strength (dB-Hz), Gold or Silver grade, leap seconds, time since the last fix |
| Holdover clock | drift rate (ppm), time since the last GPS fix, error estimate |
| NTP servers in use (4–5) | name, city and distance, stratum, reference ID (a stratum-1 server's clock type such as GPS or PPS; a stratum-2 server's upstream address), round trip, offset from GPS time, root distance, time of last query |
| Spare and discarded servers | spares, and discarded servers with the reason: wrong stratum, kiss-o'-death, disagreed with the rest, or no reply |
| Safety check | latest GPS-vs-NTP gap, result and time |

The screen is read-only and has a button to copy everything for support.

### Headphone lag view

The listener always sees how much headphone lag the app is compensating for, where that figure came from and how sure it is.

| Field | Shows | Example |
| --- | --- | --- |
| Headset | the name the headset reports, and the route (Bluetooth, USB radio, wired) | Bluetooth · the headset's own name |
| Delay compensated | headphone lag: how much earlier the app sends the audio | 182 ms |
| Source | which layer set it: reported, model default, measured or peer-measured | measured |
| Uncertainty | the spread expected for that layer, set from the Bluetooth bench test | ±8 ms |
| Last measured | when the microphone calibration last ran, and a re-check prompt after a reconnect or 30 minutes | 4 min ago |
| Manual nudge | the listener's own adjustment on top, if any | +0 ms |

A Calibrate button sits on the view, with two choices: this phone's microphone, or a neighbour's phone. The example values are illustrations; the real uncertainty figures come from the bench test.

### Brocast clock

The brocast clock is the app's very best attempt at displaying flowtime: every frame shows flowtime at the instant that frame actually lights up the screen, not when it was drawn.

1. **Draw on the display's own beat.** Draw once per screen refresh from Android's display callback (Choreographer), never from a timer.
2. **Aim at the moment of display.** From Android 13, each frame comes with its expected presentation time (`Choreographer.FrameData`), and the clock shows flowtime at that instant. Older phones fall back to the refresh beat plus a per-model pipeline delay.
3. **Add screenlag.** After presentation the panel lights from top to bottom over one refresh (up to 17 ms at 60 Hz, 8 ms at 120 Hz), so the delay depends on where the clock sits on screen. LCD panels also respond more slowly than OLED. Both go into the per-model screenlag table, measured in the bench tests.
4. **Show nanoseconds, honestly.** The clock shows hours down to nanoseconds (HH:MM:SS.mmm µµµ nnn). Digits finer than flowtime's current error estimate are faded: at ±0.4 ms, everything below the 0.1 ms digit is faded.
5. **Keep it off the audio path.** It runs on the UI thread, skips frames under load, and renders only while visible to save battery. The audio engine never waits for it.

**What it can and cannot do.** Each frame holds its value for a whole refresh (8–17 ms), so the clock is exactly right at one instant per frame and the fast digits cannot be read by eye; they are for photos, screen recordings and side-by-side checks. Its accuracy is flowtime's (under 1 ms on a Gold phone) plus the error in its screenlag. The same compensation lets every phone in the crowd flash its screen on the same beat.

## Screen light show

Every phone can light its screen on the same beat on every beach, turning the crowd into one display. It uses the same timing as the brocast clock, so a flash leaves every screen within flowtime's accuracy plus the screen-delay error: a few ms on a Gold phone (estimate).

1. **Light track.** The packager publishes a light track alongside the audio: cues (colour, brightness, pattern) stamped on the same timeline as the audio samples. Cues come from beat detection when packaging, or live from a lighting operator. Each cue is a few bytes.
2. **Timing.** A cue stamped t lights at flowtime t + D. The app draws it on the frame whose expected presentation time is nearest, corrected for that model's screenlag, exactly as the brocast clock does.
3. **Patterns.** Full-screen colour and brightness, plus patterns that depend on where the phone is on the beach, such as a wave rolling across the crowd. Each phone works out its own part from its GPS position, which never leaves the phone. With satellites off, these fall back to whole-screen patterns or a section the listener picks.
4. **Off the audio path.** The show runs on the UI thread with the brocast clock. The audio engine never waits for it, and the show skips a frame rather than delay one.

**Safety.** Flashing light can trigger seizures in people with photosensitive epilepsy, so the show follows the WCAG “three flashes” rule:

- No more than three flashes in any one second, and extra care with saturated red, which people are more sensitive to.
- A warning before the first show, the show off until the listener opts in, and a “no flashing” setting that turns flashes into slow fades.

**Battery.** Full-brightness screens drain batteries quickly, so the show runs only after opting in, and dims between cues.

## Permissions, privacy, battery

The app asks for precise location only if the listener turns Use satellites on, because Android requires it to read GPS measurements. The phone's position never leaves the phone.

- **Permissions:** notifications (for the session service's notice), internet, microphone (asked only when the listener starts a calibration), Bluetooth connect (to read the headset's name), and foreground service type `mediaPlayback`. Precise location and the `location` service type are added only while Use satellites is on. Nearby-phone sync would use Android's nearby-device permissions, declared as never used for location (to confirm). Because GPS runs only inside a visible session service, the app should not need background location; confirm this against Google Play policy.
- **Store listing:** Google Play asks apps to justify location use. The wording: “used only to read satellite time; your position is not stored or sent”.
- **Privacy:** the app may have to request GPS position updates to keep the chip running; it discards the positions. Swarm peers can see a phone's IP address, which gives a rough location, so the app says so plainly and Privacy mode limits it (see Without satellites). Peers use a new key for each day's swarm, so a stable key can't track a phone. Calibration recordings never leave the phone and are discarded after the measurement. Telemetry is opt-in and carries time quality, buffer health, errors and measured headset delays, nothing else.
- **Battery:** GPS, data and audio together for hours. Not yet measured; set a target before the field trials. GPS may not need polling continuously, especially for radio listening; this is being kept in view, with drift and re-lock time to measure first. Output chunks of 5 ms mean 200 wake-ups a second, and longer chunks may be possible for radio, if the rig shows the spread stays the same.
- **CPU:** average load looks low: Opus decode is an estimated 2% of one core or less. Short stalls on the audio thread are the risk. On a Pixel 10 Pro release build, playing ticks with the test bench on screen and no decode yet, the app used about 15% of one core: screen 10%, audio thread 2.7%. The weaker Galaxy isn't measured yet.
- **Data:** about 72 MB an hour at Opus 160 kbps. A full day is about 1.7 GB, and a 60 s buffer about 1.2 MB. A phone that still holds a file doesn't fetch it again when it repeats.

## Test plan and build phases

Prove the GPS clock on real phones first; everything else rests on it. Each phase ends on a measured result.

1. **GPS time probe.** A small app logs each phone's grade and how steady its GPS-to-boot-clock link is. Run it on 10 or more popular Android models, across the target countries. Done when every model has a grade and a measured spread.
2. **Two-phone bench test.** Play the same stream on two phones and record both wired outputs into one two-channel recorder; measure the gap by cross-correlation. Run it first with raw audio as the baseline, then with Opus files decoded on the phone, which must not widen the spread. Done when a Gold pair stays within 5 ms for an hour, including a 10-minute GPS dropout, which the Use satellites switch makes one tap. The same rig checks the 3 s tracking period, Manual and Auto gap closing, and the 5 ppm holdover figure against real drift. It also measures each model's screenlag with a light sensor on the screen, or slow-motion video for a first look.
3. **Without satellites.** Log the app's NTP-versus-GPS gap outdoors on mobile data and Wi-Fi, against nearby servers, including network changes. Run a full session with permission denied and with Location off, with no prompts or GPS banners. Then sync one phone from another with satellites off, and measure the spread.
4. **Bluetooth test.** Same rig, a microphone on each earcup of several common headsets. Done when the leftover delay per headset is known, the model defaults are set, and both in-app calibrations (the phone's own microphone and a neighbour's phone) agree with the rig to within a few ms.
5. **Transport test.** Embed Bare, Hypercore and Hyperswarm in a separate process, with no relays. Record APK growth, direct connection rate, time to connect and time per block across home Wi-Fi and mobile data, with Iroh as the comparison. Mobile-to-mobile is the known weak spot. Done when the swarm's connection results decide the transport.
6. **Load test.** On the Galaxy, as the weaker phone, compare underruns and spread for at least an hour: baseline, swarm running, and background load, with the screen on and off and Compose screens showing. Done when normal use has no underruns and the spread is no wider than baseline.
7. **Publishing and scale.** Run the packager, signed logs, seed servers and NTP directory for 24 hours, with sources in several formats and sample rates. Move a seed, lose a seed, move the publisher, and resume from a stale copy to see how a fork is detected. Simulate seed capacity, late joiners, churn and sub-topics at hundreds of thousands of peers, including gossip or block-list traffic. Done when stamps hold to the sample and the swarm covers demand.
8. **Field trial.** One beach with 10–20 phones, then two beaches far apart. The test team carries a GPS-timed recorder at each beach to measure the phones; it is a test tool, not part of the product.
9. **Public beta** of the station and a small event, with telemetry on.

## Backlog

Ideas kept for later, not in the first build.

- **Global moments:** a countdown on every brocast clock to a drop that hits every beach at the same instant, with a live count of phones in sync (for example “4,812 phones on 9 beaches in sync”) from opt-in telemetry.
- **iPhone support:** iPhones are half of UK phones but cannot read GPS time, so they would run flowtime on NTP (about 5–30 ms) with an honest, wider sync error estimate; later perhaps tighter via Wi-Fi Aware from nearby Android phones. Parked for now; the reach cost is the main reason to revisit it.
- **Bluetooth control mesh:** a local backup for signed stop commands, announcements and schedule changes at events when the mobile network is congested. It suits small messages, not audio: 100 phones relaying 160 kbps would need about 16 Mbps of transmissions, against about 1.2 Mbps for a good single link. It must be shown not to disturb Bluetooth headphones first.
- **Nearby handover:** a phone that arrives without the download gets it from a neighbour over Wi-Fi, checked against the signed log.
- **Social features:** planned, with details still to decide. Privacy mode disables them.

## Open questions

- [x] Is the source a live DJ or performer, or a pre-recorded or existing stream? Answered: pre-recorded to begin with. A live DJ later would need a monitor delayed by D.
- [x] What playout delay D can the field trial support? Answered: D and the buffer are 60 s, with a lower D possible for live shows later.
- [x] Do the music licences cover a 24-hour station across countries and many beaches at once, stored copies on phones, and phones redistributing audio? Answered: the music can be cleared for this. Ingest records each track's clearance.
- [ ] How do Bluetooth listeners get looser sync explained: a badge only, or a calibration screen?
- [ ] How strong is mobile coverage at the target beaches on a busy day?
- [ ] Google Play distribution or direct download?
- [ ] Battery target for a full session.

* [ ] Which public stratum-1 and stratum-2 operators allow app traffic at this scale, or should the project run its own GPS-fed NTP servers?
* [ ] First headset delay table: measure the 20 most common models in the lab, or start empty and fill it from calibrations?

- [ ] Light track: automatic beat detection, a live lighting operator, or both?

* [ ] Can beach events have their own programme as a separate signed log, or do they play the main station? Recommended: allow both. An event's own schedule log can reuse the same files, so it adds little traffic.
* [ ] When peers can't supply a block and the seeds are at their cap, should a listener wait, or should paid overflow serve them? Recommended: wait, the only option that keeps host cost fixed.
* [ ] How late can a schedule change? Files stay as published and a later signed entry replaces the plan, so the limit is how long a new file takes to reach phones.
* [ ] Which time servers do phones without GPS use, given that they can't all poll NPL? Recommended: regional servers following NPL time, in a signed list.
* [ ] Should GPS poll intermittently for radio listening and close to continuously at events?
* [ ] Do Pears' building blocks hold up on real phones, or does Iroh's connection record beat them? If Iroh wins, its relays are needed for hole punching and raise a cost and a domain name question.
* [ ] Does each seed need a separate provider, and how much fixed seed capacity is enough?
* [ ] What does a phone that can't report raw GPS do? Proposed: run the same way as satellites off, rather than being turned away.
* [ ] Should ingest level loudness across tracks, leaving the levels inside a set alone? Recommended: yes, because mixed sources otherwise jump in volume. The target level is still to choose.
* [ ] What's the lowest source quality ingest should accept, and who decides on flagged tracks?
* [ ] Does one shared content log with whole files hold up in the swarm, or do Hyperdrive's blob store or separate logs per file work better? To test in the transport test.
* [ ] Does 160 kbps cost too much swarm capacity? It's two-thirds more data than the 96 kbps first assumed, and seed capacity is still unmeasured.

## Sources

- [Android Developers: Raw GNSS measurements](https://developer.android.com/guide/topics/sensors/gnss) (mandatory from Android 10; “more than 90% of existing Android phones”)
- [StatCounter: Android version market share, UK](https://gs.statcounter.com/android-version-market-share/mobile-tablet/united-kingdom) (September 2026)
- [UniBW: time-synchronised GNSS/IMU logging on Android](https://athene-forschung.unibw.de/doc/139204/139204.pdf) (the GPS time formula; clock-link support varies by phone)
- [AccuTime feasibility study](https://github.com/avantol/AccuTime/blob/main/feasibility-study.md) (GPS time accuracy estimates by Android API)
- [Android Bluetooth: AVDTP 1.3 delay reports](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/82fc8edc52%5E%21/)
- [Apple developer forum: AirPods delay vs reported latency](https://developer.apple.com/forums/thread/679274)

* [NIST Internet Time Servers](https://tf.nist.gov/tf-cgi/servers.cgi) (no more than one query every 4 seconds)
* [BlueCat: seven best practices for resilient NTP](https://bluecatnetworks.com/blog/seven-best-practices-to-keep-your-ntp-resilient/) (at least four servers)

- [NPL: Internet time](https://www.npl.co.uk/products-services/time-frequency/internet-time) and [NTP user guide](https://www.npl.co.uk/products-services/time-frequency/network-time-protocol/user-guide) (five stratum-1 servers within ±50 ns of UTC(NPL))
- [NPL: Time scales](https://npl.co.uk/time-frequency/time-scales) (UTC(NPL) kept within a few nanoseconds of UTC)
- [Inside GNSS: Linking GNSS data to UTC](https://insidegnss.com/linking-gnss-data-to-utc/) (GPS from UTC(USNO); Galileo from five European labs)

* [Android Developers: Choreographer.FrameTimeline](https://developer.android.com/reference/android/view/Choreographer.FrameTimeline) (expected presentation time per frame, API level 33)

- [W3C: Understanding WCAG 2.3.1, Three Flashes or Below Threshold](https://www.w3.org/WAI/WCAG21/Understanding/three-flashes-or-below-threshold)

* [Pears docs](https://docs.pears.com/), [HyperDHT](https://github.com/holepunchto/hyperdht), [Hyperswarm](https://github.com/holepunchto/hyperswarm), [Hypercore](https://github.com/holepunchto/hypercore), [Bare](https://github.com/holepunchto/bare) and [Bare Kit](https://github.com/holepunchto/bare-kit)
* [Iroh hole punching docs](https://docs.iroh.computer/concepts/holepunching), [Iroh relay docs](https://docs.iroh.computer/concepts/relays) and [Iroh issue 4647](https://github.com/n0-computer/iroh/issues/4647) (direct path dying under upload on Android, with hyperdht as a control)
* [MNTP: Enhancing Time Synchronization for Mobile Devices](https://cs.colgate.edu/~jsommers/pubs/imc16.pdf) (unfiltered SNTP on 4G: 192 ms mean, 55 ms standard deviation)
* [Large-scale measurement of decentralised NAT traversal](https://arxiv.org/pdf/2510.27500) (libp2p hole punching: 70% ± 7.1%)
* [BlazingCDN on P2P CDNs](https://blog.blazingcdn.com/en-us/next-generation-content-delivery-the-potential-of-p2p-cdns) (vendor offload claims)
