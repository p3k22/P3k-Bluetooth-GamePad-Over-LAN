using P3k_Bluetooth_GamePad_Over_LAN.ViewModels;

namespace P3k_Bluetooth_GamePad_Over_LAN.Views;

/// <summary>Interaction logic for SliderView.xaml</summary>
public partial class SliderView
{
    public SliderView(SliderViewModel viewModel)
    {
        InitializeComponent();
        ViewModel = viewModel;
        DataContext = ViewModel;
    }

    public SliderViewModel ViewModel { get; }
}
