using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.DualShock4;
using Nefarius.ViGEm.Client.Targets.Xbox360;

namespace P3k_Bluetooth_GamePad_Over_LAN.Services;

/// <summary>Owns one virtual pad (Xbox 360 or DualShock 4) and turns input values into its reports.</summary>
public sealed class ControllerEmulator : IDisposable
{
    private const float PressThreshold = 0.5f;

    private static readonly (string Key, Xbox360Button Button)[] Xbox360Buttons =
    [
        ("buttonA", Xbox360Button.A),
        ("buttonB", Xbox360Button.B),
        ("buttonX", Xbox360Button.X),
        ("buttonY", Xbox360Button.Y),
        ("l1", Xbox360Button.LeftShoulder),
        ("r1", Xbox360Button.RightShoulder),
        ("select", Xbox360Button.Back),
        ("start", Xbox360Button.Start),
        ("l3", Xbox360Button.LeftThumb),
        ("r3", Xbox360Button.RightThumb),
        ("psButton", Xbox360Button.Guide)
    ];

    private static readonly (string Key, DualShock4Button Button)[] DualShock4Buttons =
    [
        ("buttonA", DualShock4Button.Cross),
        ("buttonB", DualShock4Button.Circle),
        ("buttonX", DualShock4Button.Square),
        ("buttonY", DualShock4Button.Triangle),
        ("l1", DualShock4Button.ShoulderLeft),
        ("r1", DualShock4Button.ShoulderRight),
        ("select", DualShock4Button.Share),
        ("start", DualShock4Button.Options),
        ("l3", DualShock4Button.ThumbLeft),
        ("r3", DualShock4Button.ThumbRight)
    ];

    private readonly ViGEmClient _client = new();
    private readonly IXbox360Controller? _xbox360;
    private readonly IDualShock4Controller? _dualShock4;

    public ControllerEmulator(bool isPSPad)
    {
        if (isPSPad)
        {
            _dualShock4 = _client.CreateDualShock4Controller();
            _dualShock4.AutoSubmitReport = false;
            _dualShock4.Connect();
        }
        else
        {
            _xbox360 = _client.CreateXbox360Controller();
            _xbox360.AutoSubmitReport = false;
            _xbox360.Connect();
        }
    }

    public void MapInputs(Dictionary<string, float> values)
    {
        if (_xbox360 != null)
        {
            MapXbox360(_xbox360, values);
        }

        if (_dualShock4 != null)
        {
            MapDualShock4(_dualShock4, values);
        }
    }

    public void Disconnect() => Dispose();

    public void Dispose()
    {
        try
        {
            MapInputs(InputFrame.Neutral());
        }
        finally
        {
            _xbox360?.Disconnect();
            _dualShock4?.Disconnect();
            _client.Dispose();
        }
    }

    private static void MapXbox360(IXbox360Controller pad, Dictionary<string, float> values)
    {
        foreach (var (key, button) in Xbox360Buttons)
        {
            pad.SetButtonState(button, IsPressed(values, key));
        }

        pad.SetButtonState(Xbox360Button.Left, ValueOf(values, "dpadX") < -PressThreshold);
        pad.SetButtonState(Xbox360Button.Right, ValueOf(values, "dpadX") > PressThreshold);
        pad.SetButtonState(Xbox360Button.Up, ValueOf(values, "dpadY") < -PressThreshold);
        pad.SetButtonState(Xbox360Button.Down, ValueOf(values, "dpadY") > PressThreshold);

        pad.SetAxisValue(Xbox360Axis.LeftThumbX, ToXbox360Axis(values, "leftStickX"));
        pad.SetAxisValue(Xbox360Axis.LeftThumbY, ToXbox360Axis(values, "leftStickY", invert: true));
        pad.SetAxisValue(Xbox360Axis.RightThumbX, ToXbox360Axis(values, "rightStickX"));
        pad.SetAxisValue(Xbox360Axis.RightThumbY, ToXbox360Axis(values, "rightStickY", invert: true));

        pad.SetSliderValue(Xbox360Slider.LeftTrigger, ToTrigger(values, "l2"));
        pad.SetSliderValue(Xbox360Slider.RightTrigger, ToTrigger(values, "r2"));
        pad.SubmitReport();
    }

    private static void MapDualShock4(IDualShock4Controller pad, Dictionary<string, float> values)
    {
        foreach (var (key, button) in DualShock4Buttons)
        {
            pad.SetButtonState(button, IsPressed(values, key));
        }

        pad.SetButtonState(DualShock4Button.TriggerLeft, ValueOf(values, "l2") > PressThreshold);
        pad.SetButtonState(DualShock4Button.TriggerRight, ValueOf(values, "r2") > PressThreshold);
        pad.SetButtonState(DualShock4SpecialButton.Ps, IsPressed(values, "psButton"));
        pad.SetButtonState(DualShock4SpecialButton.Touchpad, IsPressed(values, "touchpad"));
        pad.SetDPadDirection(ToDualShock4DPad(values));

        pad.SetAxisValue(DualShock4Axis.LeftThumbX, ToDualShock4Axis(values, "leftStickX"));
        pad.SetAxisValue(DualShock4Axis.LeftThumbY, ToDualShock4Axis(values, "leftStickY"));
        pad.SetAxisValue(DualShock4Axis.RightThumbX, ToDualShock4Axis(values, "rightStickX"));
        pad.SetAxisValue(DualShock4Axis.RightThumbY, ToDualShock4Axis(values, "rightStickY"));

        pad.SetSliderValue(DualShock4Slider.LeftTrigger, ToTrigger(values, "l2"));
        pad.SetSliderValue(DualShock4Slider.RightTrigger, ToTrigger(values, "r2"));
        pad.SubmitReport();
    }

    private static DualShock4DPadDirection ToDualShock4DPad(Dictionary<string, float> values)
    {
        return (DirectionOf(values, "dpadX"), DirectionOf(values, "dpadY")) switch
        {
            (0, -1) => DualShock4DPadDirection.North,
            (1, -1) => DualShock4DPadDirection.Northeast,
            (1, 0) => DualShock4DPadDirection.East,
            (1, 1) => DualShock4DPadDirection.Southeast,
            (0, 1) => DualShock4DPadDirection.South,
            (-1, 1) => DualShock4DPadDirection.Southwest,
            (-1, 0) => DualShock4DPadDirection.West,
            (-1, -1) => DualShock4DPadDirection.Northwest,
            _ => DualShock4DPadDirection.None
        };
    }

    private static float ValueOf(Dictionary<string, float> values, string key) => values.GetValueOrDefault(key);

    private static bool IsPressed(Dictionary<string, float> values, string key) =>
        ValueOf(values, key) > PressThreshold;

    /// <summary>-1, 0 or 1, with 0 for anything inside the press threshold.</summary>
    private static int DirectionOf(Dictionary<string, float> values, string key)
    {
        float value = ValueOf(values, key);
        return Math.Sign(Math.Abs(value) > PressThreshold ? value : 0);
    }

    /// <summary>A 0..1 trigger as a byte 0..255.</summary>
    private static byte ToTrigger(Dictionary<string, float> values, string key) =>
        (byte)Math.Round(Math.Clamp(ValueOf(values, key), 0f, 1f) * 255);

    /// <summary>A -1..1 stick as a short; Xbox Y axes point up when positive, so the phone's Y is inverted.</summary>
    private static short ToXbox360Axis(Dictionary<string, float> values, string key, bool invert = false) =>
        (short)Math.Round(Math.Clamp(ValueOf(values, key) * (invert ? -1 : 1), -1f, 1f) * 32767);

    /// <summary>A -1..1 stick as a byte 0..255 (128 is centre).</summary>
    private static byte ToDualShock4Axis(Dictionary<string, float> values, string key) =>
        (byte)Math.Round((Math.Clamp(ValueOf(values, key), -1f, 1f) + 1) * 127.5);
}
