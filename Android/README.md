# P3k Gamepad over LAN — Android sender

Use an Android phone as either a **Bluetooth controller bridge** or a **multitouch gamepad** for the [Windows receiver](../Windows/README.md).

1. Run the Windows app and open this app on the same LAN.
2. Automatic discovery connects to the Windows app. **Find Windows** retries.
3. If discovery is blocked, enter the Windows LAN **IP/hostname** and TCP **port** (default 8080), then tap **Connect IP**.
4. Leave **Touchscreen mode** off to forward the first connected Android gamepad. Pair your PS4 controller in Android's Bluetooth settings first.
5. Turn **Touchscreen mode** on to use the phone itself: two simultaneous sticks, diagonal D-pad, face buttons, shoulders/triggers, stick clicks, Share/Options, PS and touchpad click.

The app reads Android game-controller input from already connected devices; it does not scan for or pair Bluetooth devices, and needs no Bluetooth/location runtime permissions. Input-device changes are detected. Keep one physical gamepad connected.

## Connection and latency

Discovery sends active UDP probes and listens for Windows announcements on port **8888**. It uses actual interface broadcast addresses, including unusual subnet masks. Manual input stays enabled. Windows listens on TCP **8080** without needing a discovery handshake.

If connection fails, use **Add firewall exception** in Windows. If the home network is marked Public, also select the Public profile option there. Local-subnet rules do not open the app to the Internet.

Button transitions are captured synchronously during Android input dispatch and queued immediately. MotionEvent objects are read before Android recycles them. TCP_NODELAY avoids Nagle delays. A 50 ms heartbeat keeps the link alive without sampling/delaying button presses. Full states remain ordered, including very quick press/release pairs. An overflowing or >250 ms stale queue disconnects rather than silently discarding input.

The displayed **LAN round trip** uses the phone's monotonic clock and an acknowledgement after Windows submits the virtual gamepad report. Mean and p95 use the last 200 acknowledgements. It includes both network directions and Windows processing; it **does not measure Bluetooth radio, physical switch, game or display latency**. Send-queue delay shows the most recent queued event separately.

For the parts the round trip cannot see, the app writes per-event timing to logcat under the tag `P3kTiming`. Read it with `adb logcat -s P3kTiming`. Each controller event logs its kernel arrival timestamp and when the app received it, so the gaps between timestamps give the controller's Bluetooth report rate, and the difference gives Android's delivery delay. Each sent frame logs its queue delay, and each acknowledgement logs its round trip and the Windows apply time. On a Pixel 8a with a DualShock 4 the controller reported every 11.25 ms and the round trip averaged 7–8 ms.

The app requests unbuffered dispatch for controller input (Android 11+). By default Android holds joystick events until the next display frame; measured on the Pixel 8a, opting out cut Android's stick delivery delay from 12.3 ms to 3.7 ms on average (p95 15.9 ms to 6.3 ms) and button delivery from 10.5 ms to 6.2 ms.

While connected, the app requests [Android low-latency Wi-Fi mode](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_LOW_LATENCY). This can use more battery. The lock is released on disconnect.

## Background and lock screen

The connection lives in a foreground service (`ForwardingService`, type `connectedDevice`) with a notification that
shows the PC and has a **Disconnect** action, so leaving the app or locking the phone does not disconnect. Only
**Disconnect** or a connection error ends it. In touch mode, leaving the app releases the on-screen pad.

Android only delivers controller input to the focused window, so input from other apps and the lock screen comes
through `ControllerAccessibilityService`, which the user turns on in **Settings → Accessibility → P3k Gamepad
controller capture**. Since Android 14 an accessibility service can receive joystick motion (`setMotionEventSources`)
as well as keys (`FLAG_REQUEST_FILTER_KEY_EVENTS`). It takes controller events only while a Bluetooth controller is
being forwarded; otherwise it passes keys through and stops requesting motion. Measured on the Pixel 8a, delivery
through the service averaged 4.1 ms for sticks and buttons. Its per-event timing is logged under the tag `P3kA11y`.

Input only arrives while the screen is on. While forwarding a controller, the service holds a screen-dim wake lock so
the lock screen stays on, dimmed, with the phone still locked. Pressing power turns the screen off and pauses input
until it comes back on.

Windows releases the virtual pad on disconnect or after a 500 ms stall. One phone controls one virtual pad.

### Reconnecting

A session lasts from a successful connect until the user taps **Disconnect** (in the app or the notification). If the
link drops during a session (Wi-Fi blip, Windows app restarted), the app retries every 3 seconds and shows
"Reconnecting…" in red, keeping the screen and Wi-Fi awake meanwhile; it gives up after 10 minutes. If Android stops
the app (an update, or reclaiming memory), Android restarts the accessibility service, and the app resumes the session
from there without being opened. A first connection that never succeeded (for example a wrong IP) is not retried.

## Status display

The status line is large and green while connected, red otherwise; errors appear underneath in red. The controller
battery (Android 12+, for controllers that report it) shows green from 50%, orange from 20% and red below, and is sent
to Windows in each frame as `controllerBattery`.

## Diagnostics

- Phone button and Square/X down counts; **Reset counts** starts a fresh check, even with R2 held.
- Sent/acknowledged frame counts, measured RTT, queue delay and Windows submission time.
- **Share log** shares up to 2,000 recent connection/button events and current metrics.
- Live controls highlight received physical input or touchscreen input.

Face-button mapping is Cross→A, Circle→B, Square→X, Triangle→Y. Android may reserve PS/Home. Rumble, lightbar commands and motion sensors are not forwarded. Physical touchpad movement is not forwarded; the touchscreen mode has an explicit touchpad-click button.

Use only on a trusted LAN: the input/discovery protocol has no authentication or encryption.

## Build

Android SDK 34, JDK 17 or 21, Gradle wrapper:

~~~powershell
# local.properties: sdk.dir=C:/Users/YOU/AppData/Local/Android/Sdk
.\gradlew.bat assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
~~~

Release builds are signed only when the environment variable `P3K_SIGNING_SETTINGS` points to a JSON file outside
the repo holding `storeFile`, `storePassword`, `keyAlias` and `keyPassword`; otherwise `assembleRelease` produces an
unsigned APK.

The package remains com.p3kIndustries.P3kBT2PC; version 2.2, protocol v2.

See [PROTOCOL.md](../PROTOCOL.md).