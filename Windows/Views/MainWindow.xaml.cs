using System.ComponentModel;
using System.Diagnostics;
using System.Windows;
using System.Windows.Threading;
using P3k_Bluetooth_GamePad_Over_LAN.Services;
using P3k_Bluetooth_GamePad_Over_LAN.ViewModels;

namespace P3k_Bluetooth_GamePad_Over_LAN.Views;

public partial class MainWindow : Window
{
    private const int RefreshIntervalMs = 33;
    private const int AddressRefreshPeriodMs = 2000;
    private const int AddressRefreshWindowMs = 40;
    private const string FirewallScriptName = "Add-FirewallRules.ps1";

    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromMilliseconds(RefreshIntervalMs) };

    // Null until the constructor has finished: InitializeComponent raises the settings events
    // before the view model exists, and Settings_Changed must ignore those.
    private MainWindowViewModel? _viewModel;
    private bool _closing;
    private bool _closed;

    public MainWindow(UIViews views, MainWindowViewModel viewModel)
    {
        InitializeComponent();
        _viewModel = viewModel;

        JoystickElements.Content = views.AnalogueSticks;
        DPadElements.Content = views.DPad;
        PrimaryButtonsElements.Content = views.PrimaryButtons;
        SecondaryButtonsElements.Content = views.SecondaryButtons;
        TriggerElements.Content = views.Triggers;

        _timer.Tick += (_, _) => Refresh();
        Loaded += (_, _) =>
        {
            Start();
            _timer.Start();
        };
        Closing += OnClosing;
    }

    private void Start()
    {
        try
        {
            _viewModel!.Start();
            StartButton.Content = "Stop receiving";
        }
        catch (Exception exception)
        {
            ControllerState.Text = "Cannot listen: " + exception.Message;
        }
    }

    private void Refresh()
    {
        var viewModel = _viewModel!;
        viewModel.RefreshUI();
        ControllerState.Text = viewModel.Status;
        SourceInfo.Text = viewModel.Source;
        Statistics.Text = FormatStatistics(viewModel.Receiver.Snapshot());
        if (Environment.TickCount64 % AddressRefreshPeriodMs < AddressRefreshWindowMs || Addresses.Text.Length == 0)
        {
            Addresses.Text = string.Join("\n", NetworkAddresses.GetAll().Select(address => address.ToString()));
        }
    }

    private static string FormatStatistics(ReceiverStats stats)
    {
        return $"Received {stats.Packets:N0} frames · {stats.Rate:F1}/s · last {stats.AgeMs:F0} ms ago\n" +
               $"Button downs {stats.ButtonDowns:N0} · Square/X downs {stats.SquareDowns:N0}\n" +
               $"Windows parse + gamepad submit: mean {stats.MeanApplyUs:F0} µs · p95 {stats.P95ApplyUs:F0} µs";
    }

    private void Settings_Changed(object sender, RoutedEventArgs eventArgs)
    {
        _viewModel?.Configure(OutputMode.SelectedIndex == 1);
    }

    private async void Toggle_Click(object sender, RoutedEventArgs eventArgs)
    {
        StartButton.IsEnabled = false;
        try
        {
            if (_viewModel!.IsRunning)
            {
                await _viewModel.StopAsync();
                StartButton.Content = "Start receiving";
            }
            else
            {
                Start();
            }
        }
        finally
        {
            StartButton.IsEnabled = true;
        }
    }

    private async void Firewall_Click(object sender, RoutedEventArgs eventArgs)
    {
        FirewallButton.IsEnabled = false;
        try
        {
            using var process = Process.Start(CreateFirewallStartInfo())!;
            await process.WaitForExitAsync();
            FirewallStatus.Text = process.ExitCode == 0
                ? "Firewall rules added for this app: TCP 8080 and UDP 8888, local subnet only."
                : "Firewall rules were not added. Check the administrator prompt and try again.";
        }
        catch (Win32Exception exception)
        {
            FirewallStatus.Text = "Firewall permission was not granted: " + exception.Message;
        }
        catch (Exception exception)
        {
            FirewallStatus.Text = exception.Message;
        }
        finally
        {
            FirewallButton.IsEnabled = true;
        }
    }

    /// <summary>Runs the firewall script elevated and hidden, for this app's own executable.</summary>
    private ProcessStartInfo CreateFirewallStartInfo()
    {
        string script = System.IO.Path.Combine(AppContext.BaseDirectory, FirewallScriptName);
        var startInfo = new ProcessStartInfo("powershell.exe")
        {
            UseShellExecute = true,
            Verb = "runas",
            WindowStyle = ProcessWindowStyle.Hidden
        };

        string[] arguments =
        [
            "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script, "-ProgramPath", Environment.ProcessPath!
        ];
        foreach (string argument in arguments)
        {
            startInfo.ArgumentList.Add(argument);
        }

        if (PublicNetwork.IsChecked == true)
        {
            startInfo.ArgumentList.Add("-IncludePublic");
        }

        return startInfo;
    }

    private void Reset_Click(object sender, RoutedEventArgs eventArgs)
    {
        _viewModel!.Receiver.ResetCounters();
    }

    private void SaveLog_Click(object sender, RoutedEventArgs eventArgs)
    {
        var dialog = new Microsoft.Win32.SaveFileDialog
        {
            Filter = "JSON input log|*.json",
            FileName = $"gamepad-input-{DateTime.Now:yyyyMMdd-HHmmss}.json"
        };
        if (dialog.ShowDialog(this) == true)
        {
            System.IO.File.WriteAllText(dialog.FileName, _viewModel!.Receiver.ExportEvents());
        }
    }

    private async void OnClosing(object? sender, CancelEventArgs eventArgs)
    {
        if (_closed)
        {
            return;
        }

        eventArgs.Cancel = true;
        if (_closing)
        {
            return;
        }

        _closing = true;
        _timer.Stop();
        try
        {
            await _viewModel!.StopAsync();
        }
        finally
        {
            _closed = true;
            Close();
        }
    }
}
