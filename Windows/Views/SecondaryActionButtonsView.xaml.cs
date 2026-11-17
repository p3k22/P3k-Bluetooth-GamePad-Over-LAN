using P3k_Bluetooth_GamePad_Over_LAN.ViewModels;
using P3k_Bluetooth_GamePad_Over_LAN.ViewModels.Templates;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace P3k_Bluetooth_GamePad_Over_LAN.Views;

/// <summary>The Select, Start, shoulder and stick-click buttons.</summary>
public partial class SecondaryActionButtonsView
{
    private const double ButtonWidth = 50;
    private const double ButtonHeight = 25;
    private const double ButtonCornerRadius = 10;
    private const double ButtonFontSize = 10;

    public SecondaryActionButtonsView()
    {
        InitializeComponent();

        var select = CreateButton(Select, "Select");
        var start = CreateButton(Start, "Start");
        var leftShoulder = CreateButton(LeftShoulder, "L1");
        var rightShoulder = CreateButton(RightShoulder, "R1");
        var leftStickClick = CreateButton(LeftStickClick, "L3");
        var rightStickClick = CreateButton(RightStickClick, "R3");

        ViewModel = new SecondaryActionButtonsViewModel(
            select.ViewModel,
            start.ViewModel,
            leftShoulder.ViewModel,
            rightShoulder.ViewModel,
            leftStickClick.ViewModel,
            rightStickClick.ViewModel);
    }

    public SecondaryActionButtonsViewModel ViewModel { get; }

    private static ButtonView CreateButton(ContentControl host, string label)
    {
        var button = new ButtonView(new ButtonViewModel(
            label,
            ButtonWidth,
            ButtonHeight,
            Brushes.Coral,
            new CornerRadius(ButtonCornerRadius),
            ButtonFontSize));
        host.Content = button;
        return button;
    }
}
