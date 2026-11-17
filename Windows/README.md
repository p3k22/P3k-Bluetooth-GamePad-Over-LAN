# P3k Gamepad over LAN — Windows receiver

Receives a controller from the companion Android app and presents a real virtual Xbox 360 or DualShock 4 gamepad to Windows games.

1. Start the Windows app. It immediately listens on TCP **8080**, on every IPv4 interface.
2. Open the [Android app](../Android/README.md) on the same LAN. It automatically finds the Windows app; **Find Windows** retries discovery.
3. Alternatively enter any reachable Windows LAN IP/hostname and TCP port on the phone, then choose **Connect IP**.
4. Choose **Bluetooth controller** (switch off) or **Touchscreen mode** on the phone. To keep forwarding while the
   phone is locked or in other apps, enable the phone app's accessibility service (see the Android README).

The large status line is green while a phone is connected and red otherwise. Errors show underneath in red, and the
controller battery reported by the phone shows in green (50% and over), orange (20–49%) or red (under 20%).
5. Choose Xbox 360 (default, broad game compatibility) or DualShock 4 output on Windows.

The [ViGEmBus driver](https://github.com/nefarius/ViGEmBus/releases) must be installed. The app does not install or change drivers.

## Discovery and firewall

UDP **8888** is used for advertisements and active discovery requests. Directed broadcasts are computed from **each interface's actual IPv4 subnet mask**, including /16, /20, /22 and /23 networks. TCP is bound to all IPv4 interfaces before discovery starts. A VMware, VPN or Bluetooth PAN address no longer determines where the receiver listens.

The phone uses the discovery packet's source address, rather than trusting an advertised first-interface IP. Broadcasts normally remain within one subnet; manual IP entry is available when discovery is blocked.

**Add firewall exception** opens a Windows administrator prompt and installs two inbound rules for this executable: TCP 8080 and UDP 8888, restricted to **LocalSubnet**. Private/Domain profiles are default. Select **Also allow on Public networks** if your home network is marked Public. The button is safe to run again and does not disable Windows Firewall. Moving the executable requires adding rules again. Rules are grouped as **P3k Gamepad over LAN**.

Use this on a trusted LAN: discovery and input are not encrypted or authenticated. One phone is accepted at a time.

## Input and diagnostics

Button changes are sent immediately over an ordered, bounded queue with TCP_NODELAY. The 50 ms heartbeat repeats the last state; it does **not** delay button events. Every complete state is submitted as one native gamepad report. Input submission runs independently of the WPF refresh timer.

A lost connection or 500 ms without valid input releases and removes the virtual gamepad. Reconnect from the phone afterwards. Queue overflow/stale input on Android disconnects instead of silently dropping button transitions.

- Live frame rate, age, button-down and Square/X counts.
- Windows parse + native gamepad submission time (mean/p95).
- The phone measures round-trip time using acknowledgements after gamepad submission. **This is not one-way input latency and excludes controller-to-Android and game/display delay.**
- **Reset counts** starts a fresh count without requiring every button to be released.
- **Save input log** exports the most recent 10,000 button transitions as a JSON array.- Xbox mapping: Cross→A, Circle→B, Square→X, Triangle→Y. PS/Home may be intercepted by Android.

Rumble, lightbar control and motion sensors are not forwarded.

## Build and checks

.NET 8 SDK, Windows, and ViGEmBus for actual virtual-gamepad output.

~~~powershell
dotnet build
dotnet run --project tests/Checks/Checks.csproj
dotnet run --project tests/Checks/Checks.csproj -- --hardware
dotnet publish 'P3k Bluetooth GamePad Over LAN.csproj' -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true `
    -p:DebugType=none -o artifacts/release-windows
~~~

Run checks with the receiver app stopped (the discovery check uses UDP 8888). Hardware checks create temporary virtual controllers and verify actual XInput state, including simultaneous buttons, axes, triggers, opposite D-pad release and neutralisation. They do not require touching the physical controller.

To observe a phone test in real XInput:

~~~powershell
dotnet run --project tests/Checks/Checks.csproj -- --observe 30
~~~

See [PROTOCOL.md](../PROTOCOL.md) for wire compatibility and timing.