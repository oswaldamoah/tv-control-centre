# TV Control Centre + Windows Companion

Redesigned working source for the Hisense HiSmart TV A4 target (Android TV 11 / API 30).

## Remote shortcuts — 3-second hold

The Android AccessibilityService listens for global number-key events and starts a shortcut only after the same key has remained down continuously for **at least 3,000 ms**. A shorter press is not consumed by this service (`onKeyEvent` returns `false`) and should continue to the foreground TV application.

| Remote key | Action |
|---|---|
| 1 | HDMI 1 |
| 2 | HDMI 2 |
| 3 | HDMI 3 |
| 4 | HDMI 4 |
| 5 | Hisense Screen Share (if launchable on this firmware) |
| 6 | Open Wi-Fi settings |
| 7 | Ambient Mode |
| 8 | TV Control Centre dashboard |
| 9 | Next slideshow image |
| 0 | HDMI 3 backup |

The supplied ADB diagnostic log identifies `/dev/input/event2` as `MStar Smart TV IR Receiver` and shows `KEY_0` through `KEY_9`. This confirms Linux input-driver visibility, **not** that Android delivers those keys to third-party AccessibilityService filters. Enable the service in TV Settings → Accessibility, then test the physical remote. Firmware may block global key filtering. See the validation checklist below.

## Android features in this source

- TV dashboard and Ambient Mode slideshow using user-selected image URIs.
- Remote OK/Enter/Media Next advances the slideshow; Back/Menu returns to the dashboard.
- 3-second global number-key shortcut listener.
- HDMI 1–4 selection attempts through Android TV input passthrough APIs, with firmware-specific limitations.
- Screen Share launch attempt and Wi-Fi settings recovery shortcut.
- Local WebSocket connection to the Windows companion on port 8765.

## Windows companion

A Tkinter desktop control panel hosts a local WebSocket server and exposes dashboard, Ambient Mode, Screen Share, HDMI 1–4, Wi-Fi recovery and slideshow commands. It displays the PC's detected LAN IPv4 address. Both devices must be on the same network; allow Python through Windows Firewall on Private networks.

## Build Android app

1. Open this folder in Android Studio with JDK 17.
2. Sync Gradle and build `app` → `assembleDebug`.
3. Install `app/build/outputs/apk/debug/app-debug.apk` using `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
4. Open TV Control Centre and enable **TV Control Centre remote shortcuts** under Settings → Accessibility.
5. Set the Windows PC IPv4 address in Dashboard → Windows Companion Settings.

GitHub Actions is configured to build a debug APK on pushes to `main` and on manual workflow dispatch.

## Windows setup

```powershell
cd windows-companion
py -m pip install -r requirements.txt
py companion.py
```

## Required validation before calling features complete

This source archive has **not** been built or tested on the physical Hisense TV or a Windows PC in this environment. Do not treat code presence as proof of device compatibility.

1. Build the Android debug APK in Android Studio or GitHub Actions.
2. Install on the Hisense A4 and enable the accessibility service.
3. For each digit, test a tap shorter than 3 seconds (no shortcut action) and a continuous hold of at least 3 seconds (exactly one action).
4. Repeat while the dashboard, Ambient Mode, and another TV app are foregrounded; verify short presses still reach the foreground app.
5. Verify HDMI 1–4 individually. Hisense firmware may not expose every input to third-party apps; retain the physical INPUT button as fallback.
6. Verify Screen Share launch and Wi-Fi settings on the actual firmware.
7. Run the Windows companion, connect from the TV, and test each command.
8. Confirm reconnect behaviour after temporarily stopping/restarting the PC companion. The current Android socket reports disconnection but does not yet implement a persistent background service or automatic reconnect loop.

## Explicitly not included / limitations

- Live TV-to-PC mirroring is not implemented. It requires TV-side MediaProjection consent, a foreground capture service and video transport/decoder; protected apps may block capture.
- A persistent Android background WebSocket service, automatic reconnect, PC-to-TV image transfer and full HDMI firmware compatibility remain future work.
- Screen Share depends on the vendor package exposing a launchable activity.
