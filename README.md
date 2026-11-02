# Beach Sync prototype: the two-phone sync test

This measures how far apart two phones play the same sound. The answer is a gap in milliseconds; a positive gap means phone B is later. Each test measures one of two things:

- **Tick mode** tests the clock path on its own. Each phone generates a click on every whole second of flowtime, so the network stream plays no part.
- **Stream mode** tests the whole system: the stream server, the network, buffering and the rate nudge.

Each mode can run with flowtime from GPS (Auto), or from NTP only for comparison.

| Folder | What it is |
|---|---|
| `server/beachsync_server.py` | The stream server: a time-stamped 48 kHz stream in 1 s segments, either clicks or your own music |
| `android/` | The phone app, as source code. You build it once (step 1). |
| `measure/beachsync_measure.py` | Reads a recording of the two phones and reports the gaps |
| `simtest/SimTest.kt` | Desktop tests of the app's timing code (optional; results are at the end of this guide) |

You need: the two phones, a Debian machine, and the rig from the diagram. The rig is a USB audio interface with a stereo line input (such as a Behringer UCA222), two identical USB-C-to-3.5 mm adapters and two 3.5 mm-to-RCA cables. If you don't have an interface, use the acoustic test near the end of this guide.

---

## 1. Build the app (once)

Install the tools:

```bash
sudo apt install default-jdk-headless unzip adb alsa-utils python3-numpy ffmpeg
```

If you already have Android Studio, your SDK is probably at `~/Android/Sdk`. Skip the download below and use that path in `local.properties`.

Then get Android's command-line SDK. Go to <https://developer.android.com/studio>, find **Command line tools only**, and download the Linux zip. Then:

```bash
mkdir -p ~/android-sdk/cmdline-tools
unzip ~/Downloads/commandlinetools-linux-*_latest.zip -d ~/android-sdk/cmdline-tools
mv ~/android-sdk/cmdline-tools/cmdline-tools ~/android-sdk/cmdline-tools/latest
export ANDROID_HOME=~/android-sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Build:

```bash
cd beachsync-prototype/android
chmod +x gradlew
echo "sdk.dir=$HOME/android-sdk" > local.properties
./gradlew assembleDebug
```

The first build downloads Gradle and the Android plugins, which takes a few minutes. The app ends up at `app/build/outputs/apk/debug/app-debug.apk`. If you'd rather use Android Studio, open the `android` folder and press Run.

The app couldn't be built where it was written, because Google's package servers weren't reachable from there. All the Kotlin was compiled against the Android 15 API and the timing code was tested on a desktop, so a failed build is most likely a set-up problem. Paste the error back to Claude.

## 2. Install it on both phones

First, turn on USB debugging on each phone:

1. Tap **Build number** seven times. On the Pixel it's under Settings › About phone. On the Galaxy it's under Settings › About phone › Software information.
2. Turn on **USB debugging** in Developer options. On the Pixel that's Settings › System › Developer options. On the Galaxy, Developer options is at the bottom of Settings.

Then plug in each phone in turn:

```bash
adb devices                               # the phone asks whether to allow: tap Allow
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Unplug the phone afterwards, because the audio adapter needs its USB-C port.

## 3. Start the stream server

Run this on the Debian machine, on the same Wi-Fi as the phones:

```bash
python3 server/beachsync_server.py --clicks
ip -4 addr | grep inet                    # this machine's address, for example 192.168.1.23
```

Check that the phones can reach it: open `http://192.168.1.23:8080/manifest.json` in each phone's browser. If the page doesn't load, the firewall is the usual cause; if you use ufw, run `sudo ufw allow 8080/tcp`.

The server's own clock doesn't affect phone-to-phone sync, because every phone uses the same stream start time.

**Real-world variant:** you can run the server on your UK Debian VM on Google Cloud instead. Open TCP port 8080 in its VPC firewall, use its external address in the app, and put the phones on mobile data.

## 4. Wire up the rig

1. Connect **phone A (Galaxy A16)** through its adapter and cable, with the **white** plug in the interface's **left** input.
2. Connect **phone B (Pixel 10 Pro)** the same way, with its **white** plug in the **right** input.
3. Leave both red plugs loose, so each phone feeds in the same music channel.
4. Plug the interface into the Debian machine by USB.

Set both phones to about 60% volume, then find the interface and check the levels:

```bash
arecord -l                                               # find "USB Audio CODEC", e.g. card 1
arecord -D plughw:1,0 -f S16_LE -r 48000 -c 2 -V stereo /dev/null   # level meter; Ctrl-C to stop
```

Aim for peaks somewhere between a third and three-quarters of the meter, never touching the end. If arecord says the device is busy, wait five seconds and try again. The desktop sound system releases devices it isn't using.

## 5. Run the tests

On **both** phones, open **Beach Sync proto** and set:

- **Stream server:** `http://<address>:8080`
- **Mode** and **Time source:** as each test below says
- **Tick pitch:** 2000 Hz
- **Headphone lag:** 0

Press **Start**. The phones need to see the sky, so a windowsill works. Wait until the top card shows **GPS ±** in green and **Gold** (or Silver), and its clock is running. That takes about a minute, or longer for the first fix of the day.

Record each test for 5 minutes, then analyse it:

| # | Phones | Server | Record and analyse |
|---|---|---|---|
| 1 | Tick, Auto | `--clicks` | `arecord -D plughw:1,0 -f S16_LE -r 48000 -c 2 -d 300 t1.wav` then `python3 measure/beachsync_measure.py ticks-stereo t1.wav --csv t1.csv` |
| 2 | Tick, NTP only | `--clicks` | the same, as `t2` |
| 3 | Stream, Auto | `--clicks` | the same, as `t3` |
| 4 | Stream, Auto | `--source song.flac` | `... t4.wav` then `beachsync_measure.py music-stereo t4.wav --csv t4.csv` |
| 5 | Stream, NTP only | `--source song.flac` | as test 4, as `t5` |

To change settings, press **Stop** on both phones, change them, then press **Start**. Whenever you restart the server, press Stop and then Start on both phones too. Use music you own or that is licensed for free use.

**Reading the result:**

- **Mean:** a steady offset between the phones, such as a difference in adapter or USB delay, or a Silver phone's stamping delay.
- **Std dev:** jitter.
- **95% of |gap| under:** the figure that best describes what a listener would notice.

To remove a steady offset, enter the mean as phone B's **Headphone lag** and run the test again; the mean should then come out near zero. The sign works directly: if phone B is 0.8 ms late, enter 0.8.

Under 1 ms between two GPS-locked phones would be a great result. A few milliseconds means something needs work, and the logs show where.

## 6. Collect the logs

Plug each phone back in:

```bash
adb pull /sdcard/Android/data/org.beachsync.proto/files/ logs-phoneA/
```

Each run writes five files:

- `run-…-info.txt`: the phone model, Android build, GNSS chip and settings
- `run-…-status.csv`: one row per second
- `run-…-gps.csv`: every GPS clock report
- `run-…-ntp.csv`: every NTP exchange
- `run-…-events.csv`: everything in the event log

Send them to Claude with the `t*.csv` files from the measure script.

---

## Without an interface: the acoustic test

The quick way needs both phones on USB, either side of the centre of the laptop's screen and level with each other, facing it. The phones stay silent while they get a GPS lock, then click for about 10 s. Both play the same pitch, phone B 60 ms after phone A, and the pitch steps through 500, 1000, 2000, 4000 and 8000 Hz. So the clicks never overlap, the order says which phone is which, and the result shows the gap at each pitch. It only tells the phones apart at gaps under 30 ms. With a two-microphone laptop, averaging both microphones cancels the difference in distance too.

```bash
python3 measure/quicktest.py --prepare   # silent: start the phones and wait for GPS
python3 measure/quicktest.py             # 10 s countdown, recording starts, about 10 s of ticks, silence, then the gap at each pitch
python3 measure/quicktest.py --finish    # end the runs when you're done
python3 measure/test_pattern.py          # tests the analysis on synthetic recordings
```

By hand, one click a second:

1. Set phone A's **Tick pitch** to 1000 Hz and phone B's to 2500 Hz. Don't use 3000 Hz: it's the third harmonic of 1000 Hz, so a loud phone can be measured against itself and the gap reads close to zero. Use tick mode with no adapters, so each phone plays through its own speaker.
2. Place the phones side by side, the same distance (about 30 cm) from the computer's microphone, in a quiet room.
3. Record and analyse:

```bash
arecord -f S16_LE -r 48000 -c 1 -d 120 tones.wav
python3 measure/beachsync_measure.py ticks-tones tones.wav --fa 1000 --fb 2500
```

Sound takes 29 µs to travel each centimetre, so precision depends on the distances being equal. If phone B is further from the microphone, pass the extra distance with `--extra-b-cm`.

The same works in **stream mode**, which tests the whole stream path (segment fetch, ring, position and rate nudge). Start the server with `--clicks-split`, which sends a 1000 Hz click on the left channel and a 2500 Hz click on the right. Set both phones to **Stream**, phone A's **Stream channel** to **Left** and phone B's to **Right**, then record and analyse exactly as above. Music (tests 4 and 5) still needs the interface.

## What the test bench shows

The top card shows flowtime in UK time, with local time beneath when it differs, and the source in use with its 95% error estimate plus any held gap. The source switches only when the other is 20% better for 10 s.

| Row | Meaning |
|---|---|
| **OUTPUT** | The audio route, which should say *USB headset* or *USB audio* with the adapter in. Also: how far ahead the app writes, how much the output time stamps jitter, and underruns. |
| **PLAYOUT** | Position error, rate nudge, jumps, and frames missing because a segment arrived late |
| **GAP** | Any gap between the stream and flowtime, its nudge mode, and when it closes |
| **NTP** | Server, stratum and round trip. **NTP−GPS** shows how far NTP's answer is from GPS, which tells you how good NTP alone would be. |
| **GPS** | The scatter of the fit, how fast the phone's boot clock runs in ppm, and **delivery**, how late GPS reports reach the app, which matters for Silver phones |
| **STREAM** | In stream mode: the server's newest segment, seconds buffered, fetch errors, and the server's clock minus flowtime |
| **LOG** | The name of this run's log files |

Gold means the chip stamps the boot clock, and Silver means each report is stamped on arrival. They show in the top card with the satellite count.

An error adds one amber line under its row, cut to one line. Tap it to read the event log for that lane.

## Use satellites

This switch works at any point, including during a run, and the audio keeps playing. **Off** stops GPS straight away, and the last GPS fit carries on as the holdover clock until NTP's error estimate is smaller. **On** asks for location permission if the app doesn't have it, then starts a fresh GPS fit. If you say no, the switch goes back to off. With the switch off, Start doesn't ask for location at all.

This is also the quick way to run the 10-minute GPS dropout test.

## Rate nudge

The audio clock is always tracked, with a fixed period of 3 s, because it can't wait. These controls decide how a gap closes when flowtime itself moves by more than 0.1 ms. Smaller corrections are absorbed quietly. To reach them, tap the **Rate nudge** row on the test bench.

| Control | What it does |
|---|---|
| **Auto** | The default. A gap starts closing straight away at the chosen or recommended period. Above 20 ms it jumps instead. |
| **Manual** | A gap is held and shown. **Go** starts closing it, **Pause** stops, and **Jump** moves straight there. A held gap doesn't grow by itself, and it counts in the error shown in the top card. |
| **Period** | A log slider from 1 s to 5 min. It can't go faster than the 0.09% rate limit, so a 5 ms gap takes at least 5.6 s. The readout shows the real time and pitch change. |
| **Recommended** | Returns to the recommended period: 60 s, or longer if the gap needs it. |

`status.csv` records the held gap and its closing rate.

## The event log

The newest event always shows under the Start button. Tap it to see the latest 200, newest first. The log stays readable after you press Stop, until the next run starts.

It checks once a second and records each change once: time source changes, GPS first report, loss and return, underruns, jumps, missing audio, stream and NTP failures and recoveries, audio output changes and audio errors. Times are flowtime in UK time, or `--:--:--` before there is a time source.

## Troubleshooting

| Problem | What to do |
|---|---|
| The clock shows `--:--:--` | No time source yet. NTP needs internet (UDP port 123); GPS needs sky. |
| "this phone does not report raw GNSS clocks" | This phone can't supply GPS time. Test it with NTP only. |
| "waiting for the output time stamp" for more than a few seconds | This audio route isn't giving time stamps. Note the route, then try another, such as the speaker. |
| The phone can't reach the server | Check that both are on the same Wi-Fi and the firewall allows port 8080. Try the manifest URL in the phone's browser. |
| `adb` says *unauthorized* | Unlock the phone and accept the prompt. |

## Already tested on a desktop

These tests ran on the app's timing code, outside a phone:

- **NTP parsing:** recovered a known offset to 1 ns, and refused replies with the wrong nonce and kiss-o'-death replies.
- **GPS fit:** the data had 3.2 ppm of drift and 30 ns of scatter. The fit measured the drift to within 0.0001 ppm and tracked to within 23 ns. It ignored a one-off 5 ms outlier, and reset once on a real 2 ms step. After 60 s of holdover it was 6 ns out, against a bound of ±300 µs.
- **Playout loop:** with the audio clock running ±80 ppm off, the heard-time error settled at about 2.5 µs. With ±200 µs of jitter in the output time stamps it was about 7 µs rms.
- **Server and measure script:** checked against synthetic recordings with known gaps.

## Running the server in containers

For tests over mobile data, the server runs in containers on a shared VM, so it stays apart from everything else there. There are two. Their limits are the point: non-root, a read-only filesystem, no Linux capabilities, and capped CPU and memory.

| Container | Port | Library | Job |
|---|---|---|---|
| `brocast-admin` | 8766, on the host's `127.0.0.1` only | read and write | The upload page. It has no password, so reach it only through an SSH tunnel. |
| `brocast-test` | 8765, public | read only | The stream phones play: what's on air, or the test clicks when nothing is |

```
cd server
docker build -t brocast-test .
docker build -f Dockerfile.admin -t brocast-admin .
docker volume create brocast-library
LIMITS="--init --restart no --read-only --cap-drop ALL --security-opt no-new-privileges --pids-limit 64 --log-opt max-size=5m --log-opt max-file=1 --stop-timeout 2"
docker run -d --name brocast-admin $LIMITS --memory 512m --memory-swap 512m --cpus 1 -v brocast-library:/library -p 127.0.0.1:8766:8766 brocast-admin
docker run -d --name brocast-test $LIMITS --memory 128m --memory-swap 128m --cpus 0.5 -v brocast-library:/library:ro -p 8765:8765 brocast-test --clicks-split --library /library
```

Start `brocast-admin` first, the first time, so the new volume takes its ownership. Port 8765 also needs a cloud firewall rule. Set the app's server to `http://<server-ip>:8765`.

### Uploading sets

Open the tunnel, then go to <http://localhost:8766>:

```
ssh -i ~/.ssh/<key> -N -L 8766:127.0.0.1:8766 <user>@<server-ip>
```

Each upload, in any audio format, is transcoded once into a 160 kbps Opus file, ready for when phones decode it, and raw 48 kHz audio, which today's phones play. A set uses about 760 MB of disk an hour, and transcoding ran at about 24 times real time on the VM. The upload is then deleted, so keep your own copy. The disk is shared, so the library stops at 8 GB and always leaves 3 GB free.

**Put on air** starts that set from its beginning, on a loop. Phones follow by themselves, after up to a minute of silence.

Stop `brocast-test` between tests, because each phone uses about 690 MB an hour, and the stream has no password. Restarting it starts a new timeline. `python3 server/test_library.py` tests the page and the stream together, and needs ffmpeg.

## Licence

MIT: see `LICENSE`. The bundled fonts, JetBrains Mono and Space Grotesk, keep their own SIL Open Font License (`android/app/src/main/assets/licenses/`), and the Gradle wrapper keeps its Apache 2.0 licence.
