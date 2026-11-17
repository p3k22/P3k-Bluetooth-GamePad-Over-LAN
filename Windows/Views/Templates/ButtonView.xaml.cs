using P3k_Bluetooth_GamePad_Over_LAN.ViewModels.Templates;

namespace P3k_Bluetooth_GamePad_Over_LAN.Views;

/// <summary>Interaction logic for ButtonView.xaml</summary>
public partial class ButtonView
{
    public ButtonView(ButtonViewModel viewModel)
    {
        InitializeComponent();
        ViewModel = viewModel;
        DataContext = ViewModel;
    }

    public ButtonViewModel ViewModel { get; set; }
}
