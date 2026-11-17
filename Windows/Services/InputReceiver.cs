using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace P3k_Bluetooth_GamePad_Over_LAN.Services;

/// <summary>
/// Accepts one phone at a time over TCP, applies each newline-delimited input frame, acknowledges it,
/// and releases all input if the phone disconnects or stalls.
/// </summary>
public sealed class InputReceiver
{
    public const int DefaultPort = 8080;
    public const string WaitingStatus = "Ready — waiting for phone";

    private const int ReadBufferSize = 4096;
    private const int WatchdogIntervalMs = 50;
    private const int StallTimeoutMs = 500;
    private const long NoSequence = -1;

    private static readonly string[] ButtonFields = InputFrame.Fields.Where(IsButtonField).ToArray();

    private readonly Action<InputFrame> _applyFrame;
    private readonly Action _releaseInput;
    private readonly Action<string> _reportStatus;
    private readonly Action<string>? _reportError;

    // Guards every field below; frames are applied and statistics read while holding it.
    private readonly object _gate = new();
    private readonly ApplyTimingWindow _timing = new();
    private readonly InputEventLog _eventLog = new();
    private CancellationTokenSource? _cancellation;
    private TcpListener? _listener;
    private TcpClient? _active;
    private Task _acceptTask = Task.CompletedTask;
    private Task _watchdogTask = Task.CompletedTask;
    private Task _clientTask = Task.CompletedTask;
    private Dictionary<string, float> _previousValues = InputFrame.Neutral();
    private long _lastFrameTimestamp;
    private long _packetCount;
    private long _buttonDownCount;
    private long _squareDownCount;
    private string _clientAddress = "";

    public InputReceiver(Action<InputFrame> apply, Action release, Action<string> status, Action<string>? error = null)
    {
        _applyFrame = apply;
        _releaseInput = release;
        _reportStatus = status;
        _reportError = error;
    }

    public int Port => ((IPEndPoint)_listener!.LocalEndpoint).Port;

    public void Start(int port = DefaultPort)
    {
        _cancellation = new CancellationTokenSource();
        _listener = new TcpListener(IPAddress.Any, port);
        _listener.Start();
        _acceptTask = AcceptAsync(_cancellation.Token);
        _watchdogTask = WatchdogAsync(_cancellation.Token);
    }

    public async Task StopAsync()
    {
        _cancellation?.Cancel();
        _listener?.Stop();
        lock (_gate)
        {
            _active?.Dispose();
        }

        await Task.WhenAll(_acceptTask, _watchdogTask);
        await _clientTask;
        _cancellation?.Dispose();
        _cancellation = null;
    }

    /// <summary>Button down/up history as a JSON array, oldest first.</summary>
    public string ExportEvents()
    {
        lock (_gate)
        {
            return _eventLog.ToJson();
        }
    }

    public void ResetCounters()
    {
        lock (_gate)
        {
            _buttonDownCount = 0;
            _squareDownCount = 0;
            _eventLog.Clear();
        }
    }

    public ReceiverStats Snapshot()
    {
        lock (_gate)
        {
            var timing = _timing.Summarise(Stopwatch.GetTimestamp());
            double millisecondsSinceLastFrame = _lastFrameTimestamp == 0
                ? 0
                : Stopwatch.GetElapsedTime(_lastFrameTimestamp).TotalMilliseconds;
            return new ReceiverStats(
                _clientAddress,
                _packetCount,
                timing.FramesPerSecond,
                millisecondsSinceLastFrame,
                timing.MeanMicroseconds,
                timing.P95Microseconds,
                _buttonDownCount,
                _squareDownCount);
        }
    }

    private static bool IsButtonField(string field) =>
        field.StartsWith("button") || field is "l1" or "r1" or "select" or "start" or "l3" or "r3" or "psButton"
            or "touchpad";

    private static bool IsConnectionClosed(Exception exception) =>
        exception is IOException or SocketException or OperationCanceledException or ObjectDisposedException;

    private async Task AcceptAsync(CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                var client = await _listener!.AcceptTcpClientAsync(token);
                client.NoDelay = true;
                lock (_gate)
                {
                    AdmitClient(client, token);
                }
            }
        }
        catch (Exception exception) when (exception is OperationCanceledException
                                              or SocketException
                                              or ObjectDisposedException)
        {
            // Expected when the listener is stopped.
        }
    }

    /// <summary>Makes the client the active phone, or drops it when another phone is already connected.</summary>
    private void AdmitClient(TcpClient client, CancellationToken token)
    {
        if (_active != null)
        {
            client.Dispose();
            return;
        }

        _active = client;
        _clientAddress = client.Client.RemoteEndPoint?.ToString() ?? "";
        _lastFrameTimestamp = Stopwatch.GetTimestamp();
        _previousValues = InputFrame.Neutral();
        _reportStatus("Connected: " + _clientAddress);
        _clientTask = HandleAsync(client, token);
    }

    private async Task HandleAsync(TcpClient client, CancellationToken token)
    {
        // Let AcceptAsync leave the lock before this connection starts reading.
        await Task.Yield();
        string? error = null;
        try
        {
            await ReadFramesAsync(client, token);
        }
        catch (Exception exception) when (IsConnectionClosed(exception))
        {
            // A normal disconnect, stall or shutdown is not reported as an error.
        }
        catch (Exception exception)
        {
            error = exception.Message;
        }
        finally
        {
            var releaseError = ReleaseClient(client);
            error ??= releaseError;
            _reportStatus(WaitingStatus);
            if (error is not null)
            {
                _reportError?.Invoke("Phone disconnected: " + error);
            }
        }
    }

    private async Task ReadFramesAsync(TcpClient client, CancellationToken token)
    {
        var stream = client.GetStream();
        var buffer = new byte[ReadBufferSize];
        var framer = new LineFramer();
        long previousSequence = NoSequence;
        while (!token.IsCancellationRequested)
        {
            int size = await stream.ReadAsync(buffer, token);
            if (size == 0)
            {
                return;
            }

            foreach (var line in framer.Add(buffer.AsSpan(0, size)))
            {
                long startTimestamp = Stopwatch.GetTimestamp();
                var frame = InputFrame.Parse(line);
                EnsureSequenceAdvances(frame, previousSequence);
                previousSequence = frame.Sequence;

                if (!TryApplyFrame(client, frame, startTimestamp, out double applyMicroseconds))
                {
                    return;
                }

                if (frame.SentNs > 0)
                {
                    await SendAckAsync(stream, frame, applyMicroseconds, token);
                }
            }
        }
    }

    private static void EnsureSequenceAdvances(InputFrame frame, long previousSequence)
    {
        if (frame.Sequence >= 0 && frame.Sequence <= previousSequence)
        {
            throw new InvalidDataException("Out-of-order input sequence.");
        }
    }

    /// <summary>
    /// Applies the frame unless the client has been replaced or dropped meanwhile (returns false).
    /// </summary>
    private bool TryApplyFrame(TcpClient client, InputFrame frame, long startTimestamp, out double applyMicroseconds)
    {
        lock (_gate)
        {
            if (!ReferenceEquals(_active, client))
            {
                applyMicroseconds = 0;
                return false;
            }

            _applyFrame(frame);
            long now = Stopwatch.GetTimestamp();
            applyMicroseconds = (now - startTimestamp) * 1_000_000d / Stopwatch.Frequency;
            _lastFrameTimestamp = now;
            _packetCount++;
            _timing.Add(now, applyMicroseconds);
            RecordButtonEdges(frame);
            _previousValues = frame.Values;
            return true;
        }
    }

    /// <summary>
    /// Logs buttons that changed since the previous frame and counts new presses. Caller holds the lock.
    /// </summary>
    private void RecordButtonEdges(InputFrame frame)
    {
        foreach (var key in ButtonFields)
        {
            bool isDown = frame.Values[key] > .5;
            bool wasDown = _previousValues[key] > .5;
            if (isDown != wasDown)
            {
                _eventLog.Record(frame, key, isDown);
            }

            if (isDown && _previousValues[key] <= .5)
            {
                _buttonDownCount++;
                if (key == "buttonX")
                {
                    _squareDownCount++;
                }
            }
        }
    }

    private static async Task SendAckAsync(
        NetworkStream stream,
        InputFrame frame,
        double applyMicroseconds,
        CancellationToken token)
    {
        var ack = new
        {
            type = "ack",
            sequence = frame.Sequence,
            sentNs = frame.SentNs,
            serverApplyUs = applyMicroseconds
        };
        await stream.WriteAsync(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(ack) + "\n"), token);
    }

    /// <summary>
    /// Releases input if this client is still the active one, and disposes it.
    /// Returns the message of any error the release threw.
    /// </summary>
    private string? ReleaseClient(TcpClient client)
    {
        string? releaseError = null;
        lock (_gate)
        {
            if (ReferenceEquals(_active, client))
            {
                try
                {
                    _releaseInput();
                }
                catch (Exception exception)
                {
                    releaseError = exception.Message;
                }

                _active = null;
                _clientAddress = "";
            }

            client.Dispose();
        }

        return releaseError;
    }

    private async Task WatchdogAsync(CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(WatchdogIntervalMs, token);
                DropStalledClient();
            }
        }
        catch (OperationCanceledException)
        {
            // Expected when the receiver stops.
        }
    }

    private void DropStalledClient()
    {
        lock (_gate)
        {
            if (_active != null && Stopwatch.GetElapsedTime(_lastFrameTimestamp).TotalMilliseconds > StallTimeoutMs)
            {
                _active.Dispose();
            }
        }
    }
}
