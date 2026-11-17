using P3k_Bluetooth_GamePad_Over_LAN.ViewModels.Templates;

namespace P3k_Bluetooth_GamePad_Over_LAN.ViewModels;

public class SecondaryActionButtonsViewModel(
    ButtonViewModel select,
    ButtonViewModel start,
    ButtonViewModel leftShoulder,
    ButtonViewModel rightShoulder,
    ButtonViewModel leftStickClick,
    ButtonViewModel rightStickClick)
{
    public void UpdateButtonPress(
        float selectValue,
        float startValue,
        float leftShoulderValue,
        float rightShoulderValue,
        float leftStickClickValue,
        float rightStickClickValue)
    {
        select.Value = selectValue;
        start.Value = startValue;
        leftShoulder.Value = leftShoulderValue;
        rightShoulder.Value = rightShoulderValue;
        leftStickClick.Value = leftStickClickValue;
        rightStickClick.Value = rightStickClickValue;
    }
}
