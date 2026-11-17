using P3k_Bluetooth_GamePad_Over_LAN.ViewModels;
using System.Windows;

namespace P3k_Bluetooth_GamePad_Over_LAN.Views.Templates;

/// <summary>Interaction logic for JoyStickView.xaml</summary>
public partial class JoyStickView
{
    public JoyStickView(JoyStickViewModel viewModel)
    {
        InitializeComponent();
        ViewModel = viewModel;
        DataContext = ViewModel;
    }

    public JoyStickViewModel ViewModel { get; }

    public void Canvas_Loaded(object sender, RoutedEventArgs eventArgs)
    {
        ViewModel.DisplayCanvas(JoyStickCanvas);
    }

    public void Canvas_SizeChanged(object sender, RoutedEventArgs eventArgs)
    {
        if (JoyStickCanvas.IsLoaded)
        {
            JoyStickCanvas.Children.Clear();

            ViewModel.DisplayCanvas(JoyStickCanvas);
        }
    }
}
