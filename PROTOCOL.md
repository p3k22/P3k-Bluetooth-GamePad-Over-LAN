# LAN gamepad protocol v2

IPv4 UDP 8888 discovery; IPv4 TCP 8080 input. UTF-8 newline-delimited JSON, maximum 16 KiB per input line.

## Discovery
Phone broadcasts the ASCII probe P3K_GAMEPAD_DISCOVER_V2. Windows replies to the sender address/port:
~~~json
{"type":"p3k-gamepad-server","protocol":2,"port":8080,"name":"PC-NAME"}
~~~
Windows also broadcasts this announcement every second from each active IPv4 interface using its actual mask-derived directed broadcast (plus limited broadcast). The datagram's source IP is the connection address. Legacy DISCOVER_P3KBTPASSTHROUGH_CLIENT announcements are retained for older phones. TCP is listening immediately, independently of discovery.

## Input
~~~json
{"type":"input","protocol":2,"sequence":0,"sentNs":123456789,"source":"bluetooth","deviceName":"Wireless Controller","controllerBattery":73,"input":{"buttonX":1,"leftStickY":-1,"r2":1}}
~~~
Each frame is a complete state; missing fields are neutral. sequence increases per connection. sentNs is the phone's elapsedRealtimeNanos clock, recorded immediately before serialisation/write. source is bluetooth or touch.

controllerBattery is optional: the controller's battery level as an integer 0–100, sent when Android reports it (Android 12+, and only for controllers that report a level). It is omitted when unknown and in touch mode. Windows treats a missing or out-of-range value as unknown; older receivers ignore it.

latency is optional: the phone's own measurements, so Windows can display them.
~~~json
"latency":{"roundTripMeanMs":7.03,"roundTripP95Ms":12.1,"phoneInputMeanMs":3.92}
~~~
roundTripMeanMs/roundTripP95Ms cover the last 200 acknowledged frames and are omitted until the first ack.
phoneInputMeanMs is Android's average delay from the kernel timestamp of a controller event to the app receiving it,
over the last 200 controller events; omitted in touch mode or before the first event. Windows estimates
controller → PC as phoneInputMeanMs + roundTripMeanMs / 2, and ignores values that are missing, negative or over 10 s.

Axes leftStickX/Y, rightStickX/Y and dpadX/Y are -1..1; X is right-positive, Y down-positive. Triggers l2/r2 are 0..1. Buttons buttonA/B/X/Y, l1/r1, select/start, leftStickButton/rightStickButton, psButton/touchpad are 0 or 1. Windows also accepts aliases l3/r3 and legacy payloads without metadata. NaN, non-numeric fields and oversized lines are rejected. Values are clamped.

Windows acknowledges each v2 input after submitting its native gamepad report:
~~~json
{"type":"ack","sequence":0,"sentNs":123456789,"serverApplyUs":42.5}
~~~
Android calculates RTT from the echoed sentNs on its own monotonic clock. Clocks do not need synchronisation. Windows apply time includes parsing and native report submission, not game polling. RTT is not a one-way latency measurement.

Button changes are queued as full snapshots immediately; a heartbeat repeats the last sent state every 50 ms when idle. Both sockets use TCP_NODELAY. Android bounds its queue to 512 snapshots and disconnects on overflow or stale queued input (>250 ms). Windows accepts one client, bounds line length, and releases/removes the virtual pad on EOF/invalid input or after 500 ms without valid frames.

No authentication, encryption, rumble or sensor channel. Intended for a trusted local network.