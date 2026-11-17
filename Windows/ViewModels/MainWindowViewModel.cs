using P3k_Bluetooth_GamePad_Over_LAN.Services;

namespace P3k_Bluetooth_GamePad_Over_LAN.ViewModels;

/// <summary>Wires the receiver, the virtual pad and the discovery broadcaster to the on-screen controls.</summary>
public sealed class MainWindowViewModel
{
    private readonly UIViewModels _views;
    private readonly object _gate = new();
    private ControllerEmulator? _emulator;
    private Dictionary<string, float> _latestValues = InputFrame.Neutral();
    private UdpBroadcaster? _discovery;
    private bool _emulatePlayStationPad;
    private string _status = "Starting…";
    private string _source = "";
    private string? _error;
    private int? _controllerBattery;
    private LatencyReport? _latency;

    public MainWindowViewModel(UIViewModels views)
    {
        _views = views;
        Receiver = new InputReceiver(Apply, Release, status => _status = status, ReportError);
    }

    public InputReceiver Receiver { get; }

    public string Status => _status;

    public string Source => _source;

    /// <summary>The most recent problem, cleared as soon as input flows again.</summary>
    public string? Error => _error;

    /// <summary>Controller battery percentage reported by the phone, or null when unknown.</summary>
    public int? ControllerBattery => _controllerBattery;

    /// <summary>The phone's latest latency measurements, or null when none have arrived.</summary>
    public LatencyReport? Latency => _latency;

    public void ReportError(string message)
    {
        _error = message;
    }

    public bool IsRunning { get; private set; }

    /// <summary>
    /// Chooses the virtual pad type; a change drops the current pad so the next frame makes the new one.
    /// </summary>
    public void Configure(bool emulatePlayStationPad)
    {
        lock (_gate)
        {
            if (_emulatePlayStationPad != emulatePlayStationPad)
            {
                _emulator?.Dispose();
                _emulator = null;
            }

            _emulatePlayStationPad = emulatePlayStationPad;
        }
    }

    public void Start()
    {
        Receiver.Start();
        IsRunning = true;
        _status = InputReceiver.WaitingStatus;
        try
        {
            _discovery = new UdpBroadcaster();
            _discovery.Start();
        }
        catch (Exception exception)
        {
            _error = "Automatic discovery unavailable (manual IP still works): " + exception.Message;
        }
    }

    public async Task StopAsync()
    {
        if (!IsRunning)
        {
            return;
        }

        IsRunning = false;
        await Receiver.StopAsync();
        if (_discovery != null)
        {
            await _discovery.DisposeAsync();
            _discovery = null;
        }

        _status = "Stopped";
        Release();
    }

    /// <summary>Copies the latest input values onto the on-screen controls.</summary>
    public void RefreshUI()
    {
        Dictionary<string, float> latest;
        lock (_gate)
        {
            latest = _latestValues;
        }

        float ValueOf(string key) => latest.GetValueOrDefault(key);

        _views.LeftStick.UpdateAxis(ValueOf("leftStickX"), ValueOf("leftStickY"));
        _views.RightStick.UpdateAxis(ValueOf("rightStickX"), ValueOf("rightStickY"));
        _views.DPad.UpdateAxis(ValueOf("dpadX"), ValueOf("dpadY"));
        _views.PrimaryActionButtons.UpdateButtonPress(
            ValueOf("buttonA"),
            ValueOf("buttonB"),
            ValueOf("buttonX"),
            ValueOf("buttonY"));
        _views.SecondaryActionButtons.UpdateButtonPress(
            ValueOf("select"),
            ValueOf("start"),
            ValueOf("l1"),
            ValueOf("r1"),
            ValueOf("l3"),
            ValueOf("r3"));
        _views.Triggers.UpdateTriggerPress(ValueOf("l2"), ValueOf("r2"));
    }

    private void Apply(InputFrame frame)
    {
        lock (_gate)
        {
            _emulator ??= new ControllerEmulator(_emulatePlayStationPad);
            _emulator.MapInputs(frame.Values);
            _latestValues = frame.Values;
            _source = $"{frame.Source} · {frame.DeviceName}";
            _controllerBattery = frame.ControllerBattery;
            _latency = frame.Latency ?? _latency;
            _error = null;
        }
    }

    private void Release()
    {
        lock (_gate)
        {
            _emulator?.Dispose();
            _emulator = null;
            _latestValues = InputFrame.Neutral();
            _source = "";
            _controllerBattery = null;
            _latency = null;
        }
    }
}
