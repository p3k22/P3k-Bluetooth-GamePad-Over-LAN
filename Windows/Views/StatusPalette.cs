using System.Windows.Media;

namespace P3k_Bluetooth_GamePad_Over_LAN.Views;

/// <summary>Colours for status, error and battery text; the Android app uses the same values.</summary>
public static class StatusPalette
{
    private const int HealthyBatteryPercent = 50;
    private const int LowBatteryPercent = 20;

    public static readonly Brush Good = Frozen("#4ADE80");
    public static readonly Brush Warning = Frozen("#FB923C");
    public static readonly Brush Bad = Frozen("#F87171");
    public static readonly Brush Unknown = Frozen("#AEBBD0");

    /// <summary>Green from 50%, orange from 20%, red below; grey when the level is unknown.</summary>
    public static Brush ForBattery(int? percent) => percent switch
    {
        null => Unknown,
        >= HealthyBatteryPercent => Good,
        >= LowBatteryPercent => Warning,
        _ => Bad
    };

    private static Brush Frozen(string colour)
    {
        var brush = new SolidColorBrush((Color)ColorConverter.ConvertFromString(colour));
        brush.Freeze();
        return brush;
    }
}
