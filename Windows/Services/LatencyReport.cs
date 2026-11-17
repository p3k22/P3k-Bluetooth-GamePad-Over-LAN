using System.Text.Json;

namespace P3k_Bluetooth_GamePad_Over_LAN.Services;

/// <summary>
/// The phone's latency measurements, carried in each input frame. Any value may be missing: the round trip until the
/// first acknowledgement, the phone input delay in touch mode or before the first controller event.
/// </summary>
public sealed record LatencyReport(double? RoundTripMeanMs, double? RoundTripP95Ms, double? PhoneInputMeanMs)
{
    private const double MaxPlausibleMs = 10_000;

    /// <summary>
    /// Controller-to-PC estimate: the phone's input delay plus half the network round trip. The controller's own
    /// radio time can't be measured by the phone and is not included.
    /// </summary>
    public double? ControllerToPcMs =>
        PhoneInputMeanMs is { } inputMs && RoundTripMeanMs is { } roundTripMs ? inputMs + roundTripMs / 2 : null;

    public static LatencyReport? Read(JsonElement root)
    {
        if (!root.TryGetProperty("latency", out var latency) || latency.ValueKind != JsonValueKind.Object)
        {
            return null;
        }

        var report = new LatencyReport(
            ReadMilliseconds(latency, "roundTripMeanMs"),
            ReadMilliseconds(latency, "roundTripP95Ms"),
            ReadMilliseconds(latency, "phoneInputMeanMs"));
        return report.RoundTripMeanMs is null && report.PhoneInputMeanMs is null ? null : report;
    }

    private static double? ReadMilliseconds(JsonElement latency, string name) =>
        latency.TryGetProperty(name, out var property)
        && property.TryGetDouble(out var milliseconds)
        && double.IsFinite(milliseconds)
        && milliseconds is >= 0 and <= MaxPlausibleMs
            ? milliseconds
            : null;
}
