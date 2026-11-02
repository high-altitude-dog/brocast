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

Press **Start**. The phones need to see the sky, so a windowsill works. Wait until the panel shows **SOURCE GPS** and **GPS tracking · Gold** (or Silver), and the big clock is running. That takes about a minute, or longer for the first fix of the day.

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

1. Set phone A's **Tick pitch** to 1000 Hz and phone B's to 3000 Hz. Use tick mode with no adapters, so each phone plays through its own speaker.
2. Place the phones side by side, the same distance (about 30 cm) from the computer's microphone, in a quiet room.
3. Record and analyse:

```bash
arecord -f S16_LE -r 48000 -c 1 -d 120 tones.wav
python3 measure/beachsync_measure.py ticks-tones tones.wav --fa 1000 --fb 3000
```

Sound takes 29 µs to travel each centimetre, so precision depends on the distances being equal. If phone B is further from the microphone, pass the extra distance with `--extra-b-cm`.

The same works in **stream mode**, which tests the whole stream path (segment fetch, ring, position and rate nudge). Start the server with `--clicks-split`, which sends a 1000 Hz click on the left channel and a 3000 Hz click on the right. Set both phones to **Stream**, phone A's **Stream channel** to **Left only** and phone B's to **Right only**, then record and analyse exactly as above. Music (tests 4 and 5) still needs the interface.

## What the status panel shows

| Line | Meaning |
|---|---|
| **SOURCE** | Which source flowtime is using, with its 95% error estimate. The source switches only when the other is 20% better for 10 s. |
| **GPS** | Gold (the chip stamps the boot clock) or Silver (the report is stamped on arrival). Also shows satellites used, the scatter of the fit, and how fast the phone's boot clock runs in ppm. **Delivery** is how late GPS reports reach the app; it matters for Silver phones. |
| **NTP** | Server, stratum, round trip and error bound. **NTP−GPS** shows how far NTP's answer is from GPS, which tells you how good NTP alone would be. |
| **OUT** | The audio route, which should say *USB headset* or *USB audio* with the adapter in. Also: how far ahead the app writes, how much the output time stamps jitter, and underruns. |
| *(stream)* | Position error, rate nudge, jumps, and frames missing because a segment arrived late |
| **STREAM** | The server's newest segment, seconds buffered, fetch errors, and the server's clock minus flowtime |

## The event log

The newest event always shows under the Start button. Tap it, or press **Show event log**, to see the latest 200, newest first. The log stays readable after you press Stop, until the next run starts.

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
- **GPS fit:** the data had 3.2 ppm of drift and 30 ns of scatter. The fit measured the drift to within 0.0001 ppm and tracked to within 23 ns. It ignored a one-off 5 ms outlier, and reset once on a real 2 ms step. After 60 s of holdover it was 6 ns out, against a bound of ±1.3 µs.
- **Playout loop:** with the audio clock running ±80 ppm off, the heard-time error settled at 2–3 µs. With ±200 µs of jitter in the output time stamps it was 13 µs rms.
- **Server and measure script:** checked against synthetic recordings with known gaps.
