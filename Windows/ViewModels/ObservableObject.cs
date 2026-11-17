using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace P3k_Bluetooth_GamePad_Over_LAN.ViewModels;

/// <summary>Base for view models that raise <see cref="INotifyPropertyChanged.PropertyChanged"/>.</summary>
public abstract class ObservableObject : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    protected void OnPropertyChanged([CallerMemberName] string propertyName = "")
    {
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
    }
}
