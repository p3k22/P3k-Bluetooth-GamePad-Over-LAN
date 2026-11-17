using P3k_Bluetooth_GamePad_Over_LAN.ViewModels;

namespace P3k_Bluetooth_GamePad_Over_LAN.Views;

public partial class TriggersView
{
    public TriggersView()
    {
        InitializeComponent();

        var triggerSlider = new SliderView(new SliderViewModel("Triggers (L2 / R2)"));
        ViewModel = new TriggersViewModel(triggerSlider.ViewModel);
        Trigger.Content = triggerSlider;
    }

    public TriggersViewModel ViewModel { get; }
}
