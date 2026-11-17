using System.Text.Json;

namespace P3k_Bluetooth_GamePad_Over_LAN.Services;

public record InputLogEntry(
    DateTime Utc,
    long Sequence,
    string Source,
    string Device,
    string Control,
    string Event);

/// <summary>
/// Bounded history of button down/up events. Not thread-safe: the owner must serialise access.
/// </summary>
public sealed class InputEventLog
{
    private const int MaxEntries = 10000;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    private readonly Queue<InputLogEntry> _entries = new();

    public void Record(InputFrame frame, string control, bool isDown)
    {
        _entries.Enqueue(new InputLogEntry(
            DateTime.UtcNow,
            frame.Sequence,
            frame.Source,
            frame.DeviceName,
            control,
            isDown ? "down" : "up"));

        while (_entries.Count > MaxEntries)
        {
            _entries.Dequeue();
        }
    }

    public void Clear() => _entries.Clear();

    /// <summary>The history as a camelCase JSON array, oldest first.</summary>
    public string ToJson() => JsonSerializer.Serialize(_entries, JsonOptions);
}
