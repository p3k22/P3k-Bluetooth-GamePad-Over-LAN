using P3k_Bluetooth_GamePad_Over_LAN.ViewModels.Templates;

namespace P3k_Bluetooth_GamePad_Over_LAN.ViewModels;

public class PrimaryActionButtonsViewModel(
    ButtonViewModel triangle,
    ButtonViewModel cross,
    ButtonViewModel square,
    ButtonViewModel circle)
{
    /// <summary>Takes the Xbox-style face button values: A is Cross, B is Circle, X is Square, Y is Triangle.</summary>
    public void UpdateButtonPress(float buttonA, float buttonB, float buttonX, float buttonY)
    {
        triangle.Value = buttonY;
        cross.Value = buttonA;
        square.Value = buttonX;
        circle.Value = buttonB;
    }
}
