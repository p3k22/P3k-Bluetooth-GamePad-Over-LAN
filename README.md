# P3k Bluetooth GamePad Over LAN

Use an Android phone as a low-latency bridge between a Bluetooth controller and a Windows PC, or as a touchscreen
gamepad. The phone forwards input over your local network, and the Windows app presents it to games as a virtual
Xbox 360 or DualShock 4 controller.

It was built for a PC whose Bluetooth adapter delivered controller reports only every 60 ms (16.7 Hz) and lost quick
presses. Routed through a phone, the same controller reports every 11.25 ms.

| Windows receiver | Android sender |
| --- | --- |
| ![Windows receiver](docs/windows.png) | ![Android sender](docs/android.png) |

## Download

Get both apps from [Releases](../../releases):

- **Windows**: `P3k-GamePad-Over-LAN-Windows-v2.0.0.zip`, a self-contained exe for 64-bit Windows 10/11. Nothing else
  to install except the [ViGEmBus driver](https://github.com/nefarius/ViGEmBus/releases), which creates the virtual
  controller.
- **Android**: `P3k-GamePad-Over-LAN-Android-v2.0.0.apk`, for Android 9 or later. Install it by opening the file on
  the phone and allowing installs from that source.

## Quick start

1. Install ViGEmBus, unzip the Windows release and run the exe. It listens straight away.
2. On the phone, pair your controller in Android's Bluetooth settings, then open the app on the same Wi-Fi network.
3. The app finds the PC by itself. If it doesn't, type the PC's IP address (shown in the Windows app) and tap
   **Connect IP**.
4. If the phone can't connect, click **Add firewall exception** in the Windows app. It allows only this app, only on
   your local subnet.
5. Leave **Touchscreen mode** off to forward the controller, or turn it on to play with the phone's screen.

Keep the phone app in the foreground while playing.

## Latency

Measured on a Pixel 8a with a DualShock 4, from the controller's report reaching the phone to the virtual pad
updating on Windows:

| Stage | Average | Worst 5% |
| --- | --- | --- |
| Gap between controller reports (phone) | 11.25 ms | ~60–100 ms when a radio slot is missed |
| Android handing the event to the app | 3.4 ms | 5.9 ms |
| Phone → PC over Wi-Fi (half the round trip) | ~3.4 ms | ~7 ms |
| Windows updating the virtual pad | 0.09 ms | 0.15 ms |

The phone app opts out of Android's habit of holding controller events until the next screen frame, which alone
cut about 9 ms. Details and the timing log used for these numbers are in the [Android README](Android/README.md).

## Repository layout

- [Windows/](Windows/README.md): the WPF receiver (.NET 8) and its checks.
- [Android/](Android/README.md): the Kotlin sender app.
- [PROTOCOL.md](PROTOCOL.md): the discovery and input protocol both sides speak.

Use it on a trusted network only: discovery and input are not encrypted or authenticated.

## Licence

GPL-3.0, see [LICENSE](LICENSE).
