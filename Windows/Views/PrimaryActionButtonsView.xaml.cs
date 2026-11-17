using P3k_Bluetooth_GamePad_Over_LAN.ViewModels;
using P3k_Bluetooth_GamePad_Over_LAN.ViewModels.Templates;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace P3k_Bluetooth_GamePad_Over_LAN.Views;

/// <summary>The four face buttons (Triangle, Cross, Square, Circle).</summary>
public partial class PrimaryActionButtonsView
{
    private const double ButtonSize = 30;
    private const double ButtonCornerRadius = 15;
    private const double ButtonFontSize = 14;

    public PrimaryActionButtonsView()
    {
        InitializeComponent();

        // PlayStation symbols fit the 30 px buttons; the full names did not.
        var triangle = CreateButton(Triangle, "△");
        var cross = CreateButton(Cross, "✕");
        var square = CreateButton(Square, "□");
        var circle = CreateButton(Circle, "○");

        ViewModel = new PrimaryActionButtonsViewModel(
            triangle.ViewModel,
            cross.ViewModel,
            square.ViewModel,
            circle.ViewModel);
    }

    public PrimaryActionButtonsViewModel ViewModel { get; }

    private static ButtonView CreateButton(ContentControl host, string label)
    {
        var button = new ButtonView(new ButtonViewModel(
            label,
            ButtonSize,
            ButtonSize,
            Brushes.Coral,
            new CornerRadius(ButtonCornerRadius),
            ButtonFontSize));
        host.Content = button;
        return button;
    }
}
