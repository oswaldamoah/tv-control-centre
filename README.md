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


## Lightweight build and TV health diagnostics

This revision focuses on reducing the app's own memory and UI-thread load:

- Ambient wallpapers are decoded on a background worker instead of the Android main/UI thread.
- Wallpapers are downsampled before display and decoded as `RGB_565` to reduce bitmap memory use.
- The previous wallpaper bitmap is released when replaced, and wallpaper memory is cleared when returning to the dashboard.
- `FLAG_KEEP_SCREEN_ON` is used only in Ambient Mode, not across the entire dashboard session.
- The Windows Companion connection reconnects with capped backoff while the TV app is in the foreground, and disconnects when the app leaves the foreground.
- A lightweight five-second main-thread heartbeat is sent to the Windows Companion. If the socket remains connected but heartbeats stop, the companion marks the TV app as potentially stalled. This is a diagnostic signal, not proof that the entire TV operating system is frozen.

### Important recovery limitations

The current build does **not** silently restart the TV's Wi-Fi or Screen Share system service, automatically switch HDMI after a freeze, or reboot the television. Those actions need a separate recovery controller and device-specific testing.

A third-party Android app generally cannot reboot the TV itself: Android documents `PowerManager.reboot()` as requiring the privileged `REBOOT` permission, which is not intended for ordinary third-party apps. A TV-side app may also stop executing when the TV's UI/system is frozen. An independent Windows Companion/ADB controller is therefore the more reliable place to host a watchdog, provided the TV is reachable over the network and ADB debugging has been explicitly enabled and authorized.

Android TV also has background-execution restrictions. This app deliberately does not add a permanent foreground service just to poll the TV. The heartbeat runs only while the app is in the foreground. The Windows Companion can therefore identify a stalled foreground app, but cannot infer a whole-TV freeze from a missing heartbeat alone.

### Suggested recovery design for the next milestone

1. Detect a missing app heartbeat for a sustained period and confirm the TV is not simply in the background or disconnected from Wi-Fi.
2. Use the Windows Companion to check whether the TV is still reachable and whether ADB responds.
3. If ADB responds, attempt a controlled app/service recovery twice, recording each result.
4. If the TV is responsive but the current source/session is unusable, request HDMI 3 as the fallback.
5. Only if the TV remains unresponsive and ADB still accepts commands, offer or perform a configured `adb reboot` after a clear timeout and safety guard.
6. If neither network nor ADB responds, software running on the TV or PC cannot guarantee a restart. Manual power cycling may be required.

The recovery sequence must be tested on the actual Hisense firmware before enabling unattended automatic reboot. HDMI switching and Screen Share relaunch commands are vendor-specific and may not be exposed by the TV.


## Stability-first revision

This revision prioritizes reducing work performed on the TV and avoiding unsafe bitmap lifecycle operations. It does not claim to have identified the TV firmware's root cause without device-side logs.

### Changes in this revision

- Reduced wallpaper decode target from 1920×1080 to 1280×720 and retained RGB_565 decoding to reduce bitmap memory pressure.
- Removed manual recycling of bitmaps after they have been assigned to an `ImageView`. Android may still be drawing the previous drawable; recycling it immediately can cause rendering failures. The app now drops its reference and lets Android reclaim the bitmap.
- Simplified the global shortcut listener to one delayed task per key press instead of rescheduling a recursive runnable. It still fires once after a continuous 3-second hold and always returns `false` so the original key event is not consumed.
- Disabled Android app backup to avoid unnecessary backup work for this utility app.

### Important isolation test for freezes

Because the freezes reportedly began immediately after installing the app and enabling remote shortcuts, first test the app with its Accessibility Service **disabled**. Use TV Settings → Accessibility → TV Control Centre remote shortcuts → Off. Run the TV normally for a comparable period. If freezes stop, keep the shortcut service disabled while investigating firmware key-event behaviour. If freezes continue with the service disabled and the app uninstalled, the cause is likely outside this app (TV firmware, Wi-Fi/router, HDMI device, or another system app).

Do not leave the dashboard or Ambient Mode running just to monitor the TV. Exit the app when it is not needed. Ambient Mode is an optional foreground screen, not a system screensaver or watchdog.

### What this app cannot safely guarantee

- A missing heartbeat does not prove the whole TV has frozen. The app heartbeat runs only while the activity is foregrounded.
- When Android itself is frozen, an app on that TV cannot reliably repair Wi-Fi, switch HDMI, or reboot the TV.
- HDMI 3 switching and vendor Screen Share relaunch depend on Hisense firmware support.
- Do not automatically power-cycle the TV based only on a missed heartbeat. A false positive could interrupt normal use or a firmware update. An external power controller should use independent network/ADB checks, a long confirmation window, and safeguards against cycling during updates.

### Validation status

Source-level checks can verify Kotlin structure, XML validity, and Windows Python syntax, but only a successful Gradle build and a sustained test on the physical Hisense A4 can establish that this revision improves stability. Record Android logcat around any repeat freeze before concluding the cause is fixed.
