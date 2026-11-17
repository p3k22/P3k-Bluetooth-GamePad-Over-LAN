namespace P3k_Bluetooth_GamePad_Over_LAN.Services;

/// <summary>A point-in-time view of what the receiver has done, shown in the window's statistics.</summary>
public record ReceiverStats(
    string Client,
    long Packets,
    double Rate,
    double AgeMs,
    double MeanApplyUs,
    double P95ApplyUs,
    long ButtonDowns,
    long SquareDowns);
