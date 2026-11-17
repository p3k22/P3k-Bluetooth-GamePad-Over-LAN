namespace P3k_Bluetooth_GamePad_Over_LAN.ViewModels;

public class TriggersViewModel(SliderViewModel sliderViewModel)
{
    /// <summary>Shows both triggers on one slider: left pulls it negative, right pulls it positive.</summary>
    public void UpdateTriggerPress(float leftTrigger, float rightTrigger)
    {
        sliderViewModel.SliderValue = ((-leftTrigger) + rightTrigger) * 100;
    }
}
