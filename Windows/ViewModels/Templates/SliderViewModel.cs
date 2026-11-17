namespace P3k_Bluetooth_GamePad_Over_LAN.ViewModels;

public class SliderViewModel : ObservableObject
{
    private const double DefaultMinimum = -100;
    private const double DefaultMaximum = 100;

    private double _maximum;
    private double _minimum;
    private double _sliderValue;
    private string _label = "";

    public SliderViewModel(string label)
    {
        Minimum = DefaultMinimum;
        Maximum = DefaultMaximum;
        SliderValue = 0;
        Label = label;
    }

    public string Label
    {
        get => _label;
        set
        {
            _label = value;
            OnPropertyChanged();
        }
    }

    /// <summary>Largest value the slider can show (positive).</summary>
    public double Maximum
    {
        get => _maximum;
        set
        {
            _maximum = value;
            OnPropertyChanged();
        }
    }

    /// <summary>Smallest value the slider can show (negative).</summary>
    public double Minimum
    {
        get => _minimum;
        set
        {
            _minimum = value;
            OnPropertyChanged();
        }
    }

    /// <summary>Slider value bound to the UI.</summary>
    public double SliderValue
    {
        get => _sliderValue;
        set
        {
            _sliderValue = value;
            OnPropertyChanged();
        }
    }

    /// <summary>Shows a -1..1 input as -100..100 on the slider.</summary>
    public void UpdateSlider(float value)
    {
        var percent = value * 100;
        percent = Math.Clamp(percent, -100, 100);
        SliderValue = percent;
    }
}
