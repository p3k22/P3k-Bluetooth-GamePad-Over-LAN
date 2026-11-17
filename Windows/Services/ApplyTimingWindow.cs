using System.Diagnostics;

namespace P3k_Bluetooth_GamePad_Over_LAN.Services;

public readonly record struct ApplyTimingSummary(
    double FramesPerSecond,
    double MeanMicroseconds,
    double P95Microseconds);

/// <summary>
/// Sliding two-second window of how long each frame took to apply. Not thread-safe: the owner must serialise access.
/// </summary>
public sealed class ApplyTimingWindow
{
    private const double WindowSeconds = 2;
    private const double P95Fraction = 0.95;

    private readonly Queue<(long At, double Microseconds)> _samples = new();

    public void Add(long timestamp, double microseconds)
    {
        _samples.Enqueue((timestamp, microseconds));
        DropExpired(timestamp);
    }

    public ApplyTimingSummary Summarise(long now)
    {
        DropExpired(now);
        var sorted = _samples.Select(sample => sample.Microseconds).Order().ToArray();
        double spanSeconds = _samples.Count > 1 ? SecondsBetween(_samples.Peek().At, now) : 0;
        return new ApplyTimingSummary(
            spanSeconds > 0 ? (_samples.Count - 1) / spanSeconds : 0,
            sorted.Length == 0 ? 0 : sorted.Average(),
            sorted.Length == 0 ? 0 : sorted[(int)Math.Ceiling(sorted.Length * P95Fraction) - 1]);
    }

    private void DropExpired(long now)
    {
        while (_samples.TryPeek(out var oldest) && SecondsBetween(oldest.At, now) > WindowSeconds)
        {
            _samples.Dequeue();
        }
    }

    private static double SecondsBetween(long earlier, long later) => (later - earlier) / (double)Stopwatch.Frequency;
}
