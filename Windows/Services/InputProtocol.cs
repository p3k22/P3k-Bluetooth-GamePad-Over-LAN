using System.IO;
using System.Text.Json;

namespace P3k_Bluetooth_GamePad_Over_LAN.Services;

/// <summary>One decoded input message: control values plus the sender's sequence, timestamp and identity.</summary>
public sealed record InputFrame(
    Dictionary<string, float> Values,
    long Sequence,
    long SentNs,
    string Source,
    string DeviceName,
    int? ControllerBattery = null)
{
    private const int MaxJsonDepth = 8;
    private const int MaxTextLength = 100;
    private const long MissingSequence = -1;
    private const string DefaultSource = "bluetooth";
    private const string DefaultDeviceName = "Controller";

    public static readonly string[] Fields =
    [
        "leftStickX", "leftStickY", "rightStickX", "rightStickY", "dpadX", "dpadY", "l2", "r2",
        "buttonA", "buttonB", "buttonX", "buttonY", "l1", "r1", "select", "start", "l3", "r3", "psButton", "touchpad"
    ];

    public static Dictionary<string, float> Neutral() => Fields.ToDictionary(fieldName => fieldName, _ => 0f);

    public static InputFrame Parse(string json)
    {
        using var document = JsonDocument.Parse(json, new JsonDocumentOptions { MaxDepth = MaxJsonDepth });
        var root = document.RootElement;
        if (root.ValueKind != JsonValueKind.Object)
        {
            throw new InvalidDataException("Expected an input object.");
        }

        var input = root.TryGetProperty("input", out var wrapped) ? wrapped : root;
        if (input.ValueKind != JsonValueKind.Object)
        {
            throw new InvalidDataException("Expected input state.");
        }

        var values = ReadValues(input);
        values["isPSPad"] = root.TryGetProperty("isPSPad", out var isPsPad) && isPsPad.ValueKind == JsonValueKind.True
            ? 1
            : 0;

        return new InputFrame(
            values,
            ReadSequence(root),
            ReadSentNs(root),
            ReadString(root, "source", DefaultSource),
            ReadString(root, "deviceName", DefaultDeviceName),
            ReadControllerBattery(root));
    }

    /// <summary>Controller battery percentage, when the phone knows it; anything outside 0–100 counts as unknown.</summary>
    private static int? ReadControllerBattery(JsonElement root) =>
        root.TryGetProperty("controllerBattery", out var property)
        && property.TryGetInt32(out var percent)
        && percent is >= 0 and <= 100
            ? percent
            : null;

    private static Dictionary<string, float> ReadValues(JsonElement input)
    {
        var values = Neutral();
        foreach (var key in Fields)
        {
            var wireKey = ToWireKey(key);
            if (!input.TryGetProperty(wireKey, out var property) && !input.TryGetProperty(key, out property))
            {
                continue;
            }

            if (property.ValueKind != JsonValueKind.Number
                || !property.TryGetSingle(out var value)
                || !float.IsFinite(value))
            {
                throw new InvalidDataException($"Invalid {wireKey}.");
            }

            values[key] = Math.Clamp(value, IsSignedField(key) ? -1f : 0f, 1f);
        }

        return values;
    }

    /// <summary>The phone's older builds call the stick clicks leftStickButton / rightStickButton.</summary>
    private static string ToWireKey(string key) => key switch
    {
        "l3" => "leftStickButton",
        "r3" => "rightStickButton",
        _ => key
    };

    /// <summary>Sticks and the D-pad run from -1 to 1; buttons and triggers run from 0 to 1.</summary>
    private static bool IsSignedField(string key) =>
        key.StartsWith("leftStick") || key.StartsWith("rightStick") || key.StartsWith("dpad");

    private static long ReadSequence(JsonElement root) =>
        root.TryGetProperty("sequence", out var property) && property.TryGetInt64(out var sequence)
            ? sequence
            : MissingSequence;

    private static long ReadSentNs(JsonElement root) =>
        root.TryGetProperty("sentNs", out var property) && property.TryGetInt64(out var sentNs)
            ? sentNs
            : 0;

    private static string ReadString(JsonElement root, string name, string fallback)
    {
        if (!root.TryGetProperty(name, out var property) || property.ValueKind != JsonValueKind.String)
        {
            return fallback;
        }

        var text = property.GetString() ?? fallback;
        return text[..Math.Min(text.Length, MaxTextLength)];
    }
}
