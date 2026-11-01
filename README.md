# TV Control Centre — starter project

Target: Hisense HiSmart TV A4, Android 11 / API 30, 32-bit ARM userspace.

## Included
- Android TV dashboard with dark-green styling.
- Ambient Mode with multiple locally selected images.
- Remote OK/Enter/Media Next advances the slideshow.
- Attempt to launch `com.mediatek.androidbox` (Hisense Screen Share).
- Attempt to select HDMI 3 using the diagnostic passthrough input ID ending `/HW6`.
- Recovery menu with receiver relaunch and HDMI fallback.
- Windows companion that sends local WebSocket commands to the TV.

## Not yet implemented / limitations
- **Live TV mirror is not implemented in this starter.** It requires Android MediaProjection consent, a foreground capture service and video transport/decoder. Protected streaming apps may block capture.
- Third-party HDMI switching may be blocked by Hisense firmware. Keep the remote INPUT button as the fallback.
- Screen Share launch depends on whether its package exposes a launchable activity.
- PC-to-TV image transfer and timed slideshow scheduling are next milestones.
- The TV app currently defaults to companion IP `192.168.1.100`. Replace it in `MainActivity.kt` with the Windows PC's IPv4 address before building.
- Long-press number shortcuts are deliberately excluded until actual IR key events are verified.

## Build Android app
1. Open this folder in Android Studio; use JDK 17.
2. Sync Gradle and build the `app` debug APK.
3. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
4. Launch TV Control Centre from the Hisense app list.

## Run Windows companion
1. Install Python 3.10+.
2. In `windows-companion`, run `py -m pip install -r requirements.txt`.
3. Run `py companion.py`.
4. Set the displayed PC IPv4 address in `MainActivity.kt`, rebuild and reinstall.
5. Allow Python through Windows Firewall on Private networks.

Both devices must be reachable on the same local network. Router client isolation can prevent this.
