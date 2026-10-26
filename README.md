# Gyro Steering Wheel

Turn an Android phone into a PC steering wheel. The phone streams gyro-derived
steering over UDP at **100 Hz**; a small Python script on the Linux PC creates
a **virtual joystick** (uinput) that games see as a regular game controller
axis.

```
[Phone: gyro + rotation vector] --UDP:8888--> [PC: steering_receiver_linux.py] --> uinput "Gyro Steering Wheel"
```

No root required.

## Features

- **100 packets/s** UDP streaming — low latency, smooth in-game response
- **Full lock up to 3000°** (multi-turn) — real phase-unwrapped angle, works
  for a phone mounted on an actual 900°/1080°/1800°+ steering wheel
- **Over-turn vibration** — haptic warning when you turn past the chosen
  full-lock angle (both sides), with adjustable strength
- **Steering axis selection** — Z (screen facing you, hold like a wheel),
  X (long edge), Y (short edge) — tolerant to tilting (swing-twist math)
- **Deadzone, smoothing, invert** — all adjustable live in the app
- **Wi-Fi low-latency lock** — keeps the radio awake so packets don't arrive
  in bursts
- **Auto smooth re-center** — if the phone stops sending (>1 s), the wheel
  eases back to center instead of sticking
- **Settings persist** — IP, port and all tuning survive app restarts
- Screen orientation control (fixed landscape / fixed portrait / follow
  system) — never flips unexpectedly mid-drive

## Requirements

**Phone:** Android 7.0+ with a rotation vector sensor (practically any
smartphone).

**PC:** Linux with write access to `/dev/uinput` (most desktop distros grant
this to the logged-in user; otherwise see [Troubleshooting](#troubleshooting))
and Python 3 with `python-uinput`:

```bash
pip install python-uinput
```

## Quick start

### 1. PC — run the receiver

```bash
cd receiver
./run_receiver.sh
```

or directly: `python3 -u steering_receiver_linux.py` (needs `pip install python-uinput`)

Find your PC IP address: `ip a` (e.g. `192.168.0.100`).

### 2. Phone — install the app

Grab the APK from [`releases/`](releases/) (or build it yourself, see below),
install it, make sure the phone is on the same Wi-Fi network as the PC, then:

1. Enter the PC IP and port `47777`
2. Press **START** — wait for the green `● 100 pkt/s — PC OK` indicator
3. Hold the phone like a wheel, screen facing you, and press **CALIBRATE**
   while the wheel is straight

## App settings

| Setting | Description |
|---|---|
| Steering axis | Rotation axis: `Z` screen normal (hold like a wheel), `X` long edge, `Y` short edge |
| Full lock | Angle giving 100% deflection: 30–3000° (multi-turn safe) |
| Vibration beyond full lock | Haptic warning strength 0–100% (0 = off) when the wheel turns past the full-lock angle |
| Deadzone | Center dead zone in degrees (default 2°) |
| Smoothing | Exponential smoothing 0–95% (default 40%) |
| Invert | On by default: clockwise turn = positive axis value |
| Screen orientation | Fixed landscape / fixed portrait / follow system setting |
| Language | English / Українська |
| Keep screen on | Prevents the screen from sleeping while streaming |

All settings are saved automatically.

## Protocol (UDP, port 47777)

The phone sends a packet every 10 ms:

```
S,<steer>
```

`steer` is an integer in `-1000..1000` (clockwise/right turn = positive).

The PC replies with `A` once per second — the app turns green ("PC OK")
on reception. If no packets arrive for more than 1 second, the receiver
smoothly returns the wheel to center.

## Testing without a phone

```bash
cd receiver
./run_receiver.sh &
python3 test_sender.py 127.0.0.1 47777
```

Verify the axis with `jstest /dev/input/js0` or `evtest`.

## Building the APK from source

The repo ships the exact toolchain setup used by the author (JDK 17 +
Android SDK platform 34 + Gradle 8.7). One command downloads everything
(~500 MB, no root):

```bash
tools/build_apk.sh
```

(first run installs the toolchain automatically, or run `tools/setup_toolchain.sh` alone)

Output: `android-app/app/build/outputs/apk/debug/app-debug.apk`

Prefer your own setup? Open `android-app/` in Android Studio and build there
— the project has no external dependencies.

## Troubleshooting

- **App shows "PC silent"** — check the IP, make sure both devices are on the
  same network. If a firewall is running: `sudo ufw allow 47777/udp`.
- **PermissionError on /dev/uinput** — add a udev rule:

  ```
  # /etc/udev/rules.d/85-uinput.rules
  KERNEL=="uinput", MODE="0660", GROUP="input", TAG+="uaccess"
  ```

  then `sudo udevadm control --reload && sudo udevadm trigger`.
- **Packets arrive in bursts** — the app holds a Wi-Fi low-latency lock; if
  your ROM ignores it, disable battery optimization for the app
  (Settings → Apps → Gyro Steering Wheel → Battery → Unrestricted).
- **Games don't see the axis** — verify with `jstest /dev/input/js0`;
  the device exposes `ABS_X` 0..32767 with center at 16384 (vJoy-style).
- **Slow center drift** — normal gyro behavior over many minutes of
  multi-turn use; tap CALIBRATE at straight-ahead.

## Roadmap

- On-screen buttons (the protocol already reserves a `B,<mask>` field)
- Force-feedback-style resistance (haptics on turn)
- Windows receiver

## Repository layout

```
receiver/          Linux receiver + UDP test sender (Python, uinput)
android-app/       Android app sources (Gradle, no external dependencies)
tools/             build_apk.sh + setup_toolchain.sh — one-command build toolchain
releases/          prebuilt APK of the latest release
```

## License

[MIT](LICENSE)
