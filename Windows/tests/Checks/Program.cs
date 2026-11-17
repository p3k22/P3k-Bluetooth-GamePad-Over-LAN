using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using P3k_Bluetooth_GamePad_Over_LAN.Services;

/// <summary>
/// Self-contained check harness. Options: --skip-discovery, --hardware (real ViGEm + XInput), --observe [seconds].
/// </summary>
static class Checks
{
    private const int FramesPerCheck = 40;
    private const int QuickPressCount = FramesPerCheck / 2;
    private const int XInputSlotCount = 4;

    // XInput button bits.
    private const int XInputSquare = 0x4000;
    private const int XInputSquareL3UpLeft = 0x4045;
    private const int XInputSquareAndDirections = 0x400f;
    private const int XInputDownRight = 0x000a;

    private static int _passedCount;

    [StructLayout(LayoutKind.Sequential)]
    public struct GamepadState
    {
        public ushort Buttons;
        public byte LeftTrigger;
        public byte RightTrigger;
        public short LeftThumbX;
        public short LeftThumbY;
        public short RightThumbX;
        public short RightThumbY;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct State
    {
        public uint Packet;
        public GamepadState Pad;
    }

    [DllImport("xinput1_4.dll")]
    public static extern uint XInputGetState(uint index, out State state);

    static async Task Main(string[] args)
    {
        if (args.Length > 0 && args[0] == "--observe")
        {
            await ObserveAsync(args.Skip(1).ToArray());
            return;
        }

        CheckBroadcastAddresses();
        CheckLineFraming();
        CheckInputParsing();
        await CheckReceiverAsync();
        if (!args.Contains("--skip-discovery"))
        {
            await CheckDiscoveryAsync();
        }

        if (args.Contains("--hardware"))
        {
            await CheckHardwareAsync();
        }

        Console.WriteLine("All " + _passedCount + " checks passed.");
    }

    static void Check(bool value, string message)
    {
        if (!value)
        {
            throw new Exception(message);
        }

        _passedCount++;
        Console.WriteLine("PASS " + message);
    }

    /// <summary>Passes when the action throws <typeparamref name="TException"/>; any other outcome fails.</summary>
    static void CheckThrows<TException>(Action action, string acceptedMessage, string passMessage)
        where TException : Exception
    {
        try
        {
            action();
        }
        catch (TException)
        {
            Check(true, passMessage);
            return;
        }

        throw new Exception(acceptedMessage);
    }

    static async Task Until(Func<bool> condition, int timeoutMs = 2500)
    {
        var watch = Stopwatch.StartNew();
        while (!condition())
        {
            if (watch.ElapsedMilliseconds > timeoutMs)
            {
                throw new TimeoutException("Condition timed out");
            }

            await Task.Delay(5);
        }
    }

    static int LoggedEventCount(InputReceiver receiver)
    {
        using var log = JsonDocument.Parse(receiver.ExportEvents());
        return log.RootElement.GetArrayLength();
    }

    static string Frame(long sequence, float square = 0)
    {
        var frame = new
        {
            protocol = 2,
            sequence,
            sentNs = Stopwatch.GetTimestamp(),
            source = "touch",
            deviceName = "test",
            input = new { buttonX = square }
        };
        return JsonSerializer.Serialize(frame) + "\n";
    }

    static void CheckBroadcastAddresses()
    {
        var subnets = new[]
        {
            (Address: "192.168.20.15", Mask: "255.255.252.0", Expected: "192.168.23.255"),
            (Address: "10.33.18.21", Mask: "255.255.0.0", Expected: "10.33.255.255"),
            (Address: "10.44.19.12", Mask: "255.255.240.0", Expected: "10.44.31.255"),
            (Address: "172.20.5.31", Mask: "255.255.254.0", Expected: "172.20.5.255"),
            (Address: "10.0.0.5", Mask: "255.255.255.252", Expected: "10.0.0.7")
        };
        foreach (var (address, mask, expected) in subnets)
        {
            var broadcast = NetworkAddresses.BroadcastAddress(IPAddress.Parse(address), IPAddress.Parse(mask));
            Check(broadcast!.ToString() == expected, address + " mask " + mask);
        }

        var hostAddress = IPAddress.Parse("10.0.0.2");
        Check(
            NetworkAddresses.BroadcastAddress(hostAddress, IPAddress.Parse("255.255.255.254")) == null,
            "/31 has no broadcast");
        Check(
            NetworkAddresses.BroadcastAddress(hostAddress, IPAddress.Parse("255.255.255.255")) == null,
            "/32 has no broadcast");
        CheckThrows<ArgumentException>(
            () => NetworkAddresses.BroadcastAddress(hostAddress, IPAddress.Parse("255.0.255.0")),
            "Accepted invalid mask",
            "Reject noncontiguous masks");
    }

    static void CheckLineFraming()
    {
        var bytes = Encoding.UTF8.GetBytes("{\"input\":{\"buttonX\":1},\"deviceName\":\"🎮\"}\n{}\r\n");
        var framer = new LineFramer();
        var decoded = new List<string>();
        foreach (byte singleByte in bytes)
        {
            decoded.AddRange(framer.Add(new[] { singleByte }));
        }

        Check(
            decoded.Count == 2 && InputFrame.Parse(decoded[0]).DeviceName == "🎮",
            "Fragmented UTF-8 and coalesced frames");
    }

    static void CheckInputParsing()
    {
        var legacy = InputFrame.Parse(
            "{\"input\":{\"leftStickButton\":1,\"leftStickY\":-1,\"buttonX\":-2,\"r2\":2}}").Values;
        Check(
            legacy["l3"] == 1 && legacy["leftStickY"] == -1 && legacy["buttonX"] == 0 && legacy["r2"] == 1,
            "Legacy aliases, Y orientation, button/trigger clamping");
        Check(InputFrame.Parse("{\"controllerBattery\":73,\"input\":{}}").ControllerBattery == 73
              && InputFrame.Parse("{\"controllerBattery\":140,\"input\":{}}").ControllerBattery is null
              && InputFrame.Parse("{\"input\":{}}").ControllerBattery is null,
            "Controller battery read when valid, unknown when missing or out of range");
        CheckThrows<System.IO.InvalidDataException>(
            () => new LineFramer().Add(new byte[LineFramer.MaxLineBytes + 1]),
            "Accepted oversized frame",
            "Frame size is bounded");
        CheckThrows<System.IO.InvalidDataException>(
            () => InputFrame.Parse("{\"input\":{\"buttonA\":\"invalid\"}}"),
            "Accepted string value",
            "Reject malformed input values");
    }

    /// <summary>
    /// Runs one receiver through a whole phone session. The first client stays connected on purpose
    /// until the end, so the later steps depend on its order.
    /// </summary>
    static async Task CheckReceiverAsync()
    {
        var received = new List<float>();
        int releasedCount = 0;
        var receiver = new InputReceiver(
            frame =>
            {
                lock (received)
                {
                    received.Add(frame.Values["buttonX"]);
                }
            },
            () => Interlocked.Increment(ref releasedCount),
            _ => { });
        receiver.Start(0);

        using var client = new TcpClient() { NoDelay = true };
        await client.ConnectAsync(IPAddress.Loopback, receiver.Port);
        await SendQuickPressesInTwoFragmentsAsync(client.GetStream());
        await Until(() => CountReceived(received) == FramesPerCheck);
        CheckQuickPressesPreserved(receiver, received);

        using var second = new TcpClient();
        await second.ConnectAsync(IPAddress.Loopback, receiver.Port);
        var readResult = await second.GetStream().ReadAsync(new byte[1]).AsTask().WaitAsync(TimeSpan.FromSeconds(1));
        Check(readResult == 0, "Reject competing phone");

        await Until(() => Volatile.Read(ref releasedCount) == 1);
        Check(
            receiver.Snapshot().SquareDowns == QuickPressCount && receiver.Snapshot().Client == "",
            "Watchdog releases stalled client and counts downs");

        using var third = new TcpClient();
        await third.ConnectAsync(IPAddress.Loopback, receiver.Port);
        await third.GetStream().WriteAsync(Encoding.UTF8.GetBytes(Frame(0, 1)));
        await Until(() => CountReceived(received) == FramesPerCheck + 1);
        third.Dispose();
        await Until(() => Volatile.Read(ref releasedCount) == 2);
        await receiver.StopAsync();
        Check(true, "Reconnect accepts new sequence and releases on disconnect");

        receiver.ResetCounters();
        Check(
            receiver.Snapshot().SquareDowns == 0 && LoggedEventCount(receiver) == 0,
            "Reset counts and event log independently of held input");
    }

    /// <summary>Alternating press/release frames, split mid-frame to prove the framing copes.</summary>
    static async Task SendQuickPressesInTwoFragmentsAsync(NetworkStream stream)
    {
        const int firstFragmentLength = 7;
        var frames = Enumerable.Range(0, FramesPerCheck).Select(index => Frame(index, index % 2 == 0 ? 1 : 0));
        var data = Encoding.UTF8.GetBytes(string.Concat(frames));
        await stream.WriteAsync(data.AsMemory(0, firstFragmentLength));
        await stream.WriteAsync(data.AsMemory(firstFragmentLength));
    }

    static int CountReceived(List<float> received)
    {
        lock (received)
        {
            return received.Count;
        }
    }

    static void CheckQuickPressesPreserved(InputReceiver receiver, List<float> received)
    {
        var expected = Enumerable.Range(0, FramesPerCheck).Select(index => index % 2 == 0 ? 1f : 0f);
        lock (received)
        {
            Check(received.SequenceEqual(expected), "TCP preserves 20 quick press/release edges");
        }

        Check(LoggedEventCount(receiver) == FramesPerCheck, "Input log records all 20 down/up pairs");
    }

    static async Task CheckDiscoveryAsync()
    {
        await using var discovery = new UdpBroadcaster();
        discovery.Start();
        using var probe = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        await probe.SendAsync(
            Encoding.UTF8.GetBytes(UdpBroadcaster.Probe),
            new IPEndPoint(IPAddress.Loopback, UdpBroadcaster.DiscoveryPort));
        var reply = await probe.ReceiveAsync().WaitAsync(TimeSpan.FromSeconds(2));
        using var json = JsonDocument.Parse(reply.Buffer);
        Check(
            json.RootElement.GetProperty("port").GetInt32() == InputReceiver.DefaultPort,
            "Active UDP discovery works independently of TCP handshake");
    }

    static bool TryReadSlot(int slot, out State state) => XInputGetState((uint)slot, out state) == 0;

    static async Task CheckHardwareAsync()
    {
        await CheckXbox360HardwareAsync();
        CheckDualShock4Hardware();
    }

    static async Task CheckXbox360HardwareAsync()
    {
        var existingSlots = Enumerable.Range(0, XInputSlotCount).Where(slot => TryReadSlot(slot, out _)).ToHashSet();
        using var emulator = new ControllerEmulator(false);

        int newSlot = -1;
        await Until(() =>
        {
            newSlot = Enumerable.Range(0, XInputSlotCount)
                .FirstOrDefault(slot => !existingSlots.Contains(slot) && TryReadSlot(slot, out _), -1);
            return newSlot >= 0;
        });

        var values = InputFrame.Neutral();
        values["buttonX"] = 1;
        values["l3"] = 1;
        values["dpadX"] = -1;
        values["dpadY"] = -1;
        values["leftStickY"] = -1;
        values["rightStickX"] = .5f;
        values["l2"] = .5f;
        values["r2"] = 1;
        emulator.MapInputs(values);
        await Until(() => TryReadSlot(newSlot, out var polled) && (polled.Pad.Buttons & XInputSquare) != 0);
        TryReadSlot(newSlot, out var state);
        Check(
            (state.Pad.Buttons & XInputSquareL3UpLeft) == XInputSquareL3UpLeft
                && state.Pad.LeftThumbY == 32767
                && state.Pad.RightThumbX == 16384
                && state.Pad.LeftTrigger == 128
                && state.Pad.RightTrigger == 255,
            "Real ViGEm → XInput: Square, L3, diagonal, axes, both triggers");

        values["dpadX"] = 1;
        values["dpadY"] = 1;
        values["buttonX"] = 0;
        emulator.MapInputs(values);
        await Until(() => TryReadSlot(newSlot, out var polled)
                          && (polled.Pad.Buttons & XInputSquareAndDirections) == XInputDownRight);
        Check(true, "Real XInput releases opposite D-pad and face button");

        emulator.MapInputs(InputFrame.Neutral());
        await Until(() => TryReadSlot(newSlot, out var polled)
                          && polled.Pad.Buttons == 0
                          && polled.Pad.RightTrigger == 0);
        Check(true, "Real XInput neutral report releases every control");
    }

    static void CheckDualShock4Hardware()
    {
        using var dualShock4 = new ControllerEmulator(true);
        var values = InputFrame.Neutral();
        values["buttonX"] = 1;
        values["dpadX"] = -1;
        values["dpadY"] = 1;
        dualShock4.MapInputs(values);
        dualShock4.MapInputs(InputFrame.Neutral());
        Check(true, "Real ViGEm DS4 target connects, submits and releases");
    }

    /// <summary>Per-slot memory for <see cref="ObserveAsync"/>: what the slot reported last time.</summary>
    sealed class SlotObservation
    {
        public ushort Buttons;
        public byte LeftTrigger;
        public byte RightTrigger;
        public int SquareDowns;

        public void Reset()
        {
            Buttons = 0;
            LeftTrigger = 0;
            RightTrigger = 0;
        }
    }

    static async Task ObserveAsync(string[] args)
    {
        double seconds = args.Length > 0
            ? double.Parse(args[0], System.Globalization.CultureInfo.InvariantCulture)
            : 20;
        Console.WriteLine("Observing real XInput for " + seconds + " seconds at " + DateTime.UtcNow.ToString("O"));
        var elapsed = Stopwatch.StartNew();
        var slots = Enumerable.Range(0, XInputSlotCount).Select(_ => new SlotObservation()).ToArray();
        while (elapsed.Elapsed.TotalSeconds < seconds)
        {
            for (int slot = 0; slot < XInputSlotCount; slot++)
            {
                ObserveSlot(slot, slots[slot], elapsed);
            }

            await Task.Delay(1);
        }

        var squareDowns = slots.Select(observation => observation.SquareDowns);
        Console.WriteLine("Square downs per slot: " + string.Join(",", squareDowns));
    }

    static void ObserveSlot(int slot, SlotObservation observation, Stopwatch elapsed)
    {
        if (!TryReadSlot(slot, out var state))
        {
            observation.Reset();
            return;
        }

        ushort down = (ushort)(state.Pad.Buttons & ~observation.Buttons);
        ushort up = (ushort)(observation.Buttons & ~state.Pad.Buttons);
        bool changed = down != 0
                       || up != 0
                       || observation.LeftTrigger != state.Pad.LeftTrigger
                       || observation.RightTrigger != state.Pad.RightTrigger;
        if (changed)
        {
            if ((down & XInputSquare) != 0)
            {
                observation.SquareDowns++;
            }

            Console.WriteLine(
                $"{elapsed.Elapsed.TotalMilliseconds:F2} ms slot {slot} DOWN 0x{down:X4} UP 0x{up:X4} " +
                $"Square={observation.SquareDowns} RT={state.Pad.RightTrigger}");
        }

        observation.Buttons = state.Pad.Buttons;
        observation.LeftTrigger = state.Pad.LeftTrigger;
        observation.RightTrigger = state.Pad.RightTrigger;
    }
}
