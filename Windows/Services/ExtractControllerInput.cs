namespace P3k_Bluetooth_GamePad_Over_LAN.Services;
public static class ExtractControllerInput
{
    public static Dictionary<string, float> GetFromJson(string json, bool isPSPad)
    {
        var values = InputFrame.Parse(json).Values;
        values["isPSPad"] = isPSPad ? 1 : 0;
        return values;
    }
}
