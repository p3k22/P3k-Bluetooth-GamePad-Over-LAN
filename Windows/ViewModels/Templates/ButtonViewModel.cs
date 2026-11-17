using System.Windows;
using System.Windows.Media;

namespace P3k_Bluetooth_GamePad_Over_LAN.ViewModels.Templates;

public class ButtonViewModel : ObservableObject
{
    private readonly Brush _originalBackgroundColor;
    private Brush _backgroundColor = new BitmapCacheBrush();
    private CornerRadius _cornerRadius = new CornerRadius(0);
    private double _fontSize = 10;
    private double _height = 50;
    private double _width = 50;
    private float _value;
    private string _buttonLabel = "";

    public ButtonViewModel(
        string label,
        double width,
        double height,
        Brush color,
        CornerRadius cornerRadius,
        double fontSize)
    {
        ButtonLabel = label;
        Width = width;
        Height = height;
        BackgroundColor = color;
        _originalBackgroundColor = color;
        CornerRadius = cornerRadius;
        FontSize = fontSize;
        Value = 0;
    }

    /// <summary>Background of the button; changes to a highlight colour while the button is pressed.</summary>
    public Brush BackgroundColor
    {
        get => _backgroundColor;
        set
        {
            _backgroundColor = value;
            OnPropertyChanged();
        }
    }

    /// <summary>Label text inside the button.</summary>
    public string ButtonLabel
    {
        get => _buttonLabel;
        set
        {
            _buttonLabel = value;
            OnPropertyChanged();
        }
    }

    /// <summary>Corner radius that controls how rounded the button is.</summary>
    public CornerRadius CornerRadius
    {
        get => _cornerRadius;
        set
        {
            _cornerRadius = value;
            OnPropertyChanged();
        }
    }

    public double FontSize
    {
        get => _fontSize;
        set
        {
            _fontSize = value;
            OnPropertyChanged();
        }
    }

    public double Height
    {
        get => _height;
        set
        {
            _height = value;
            OnPropertyChanged();
        }
    }

    /// <summary>How far the button is pressed; zero restores the original background colour.</summary>
    public float Value
    {
        get => _value;
        set
        {
            if (_value == value)
            {
                return;
            }

            _value = value;
            BackgroundColor = Value == 0 ? _originalBackgroundColor : new SolidColorBrush { Color = Colors.Aquamarine };

            OnPropertyChanged();
        }
    }

    public double Width
    {
        get => _width;
        set
        {
            _width = value;
            OnPropertyChanged();
        }
    }
}
