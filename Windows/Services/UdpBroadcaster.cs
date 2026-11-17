using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace P3k_Bluetooth_GamePad_Over_LAN.Services;

/// <summary>Lets phones find this server: answers discovery probes and announces itself on every LAN.</summary>
public sealed class UdpBroadcaster : IAsyncDisposable
{
    public const int DiscoveryPort = 8888;
    public const string Probe = "P3K_GAMEPAD_DISCOVER_V2";

    /// <summary>Message older phone builds listen for; sent after each announcement.</summary>
    private const string LegacyClientProbe = "DISCOVER_P3KBTPASSTHROUGH_CLIENT";

    private static readonly TimeSpan AdvertiseInterval = TimeSpan.FromSeconds(1);

    private readonly int _port;
    private readonly CancellationTokenSource _cancellation = new();
    private UdpClient? _listener;
    private Task[] _tasks = [];

    public UdpBroadcaster(int port = InputReceiver.DefaultPort) => _port = port;

    private byte[] Announcement => JsonSerializer.SerializeToUtf8Bytes(new
    {
        type = "p3k-gamepad-server",
        protocol = 2,
        port = _port,
        name = Environment.MachineName
    });

    public void Start()
    {
        _listener = new UdpClient(new IPEndPoint(IPAddress.Any, DiscoveryPort));
        _listener.EnableBroadcast = true;
        _tasks = [ReceiveAsync(), AdvertiseAsync()];
    }

    public async ValueTask DisposeAsync()
    {
        _cancellation.Cancel();
        _listener?.Dispose();
        await Task.WhenAll(_tasks);
        _cancellation.Dispose();
    }

    private async Task ReceiveAsync()
    {
        try
        {
            while (!_cancellation.IsCancellationRequested)
            {
                var packet = await _listener!.ReceiveAsync(_cancellation.Token);
                if (Encoding.UTF8.GetString(packet.Buffer) == Probe)
                {
                    await _listener.SendAsync(Announcement, packet.RemoteEndPoint, _cancellation.Token);
                }
            }
        }
        catch (Exception exception) when (exception is OperationCanceledException
                                              or ObjectDisposedException
                                              or SocketException)
        {
            // Expected when the listener is shut down.
        }
    }

    private async Task AdvertiseAsync()
    {
        try
        {
            while (!_cancellation.IsCancellationRequested)
            {
                await AdvertiseOnAllNetworksAsync();
                await Task.Delay(AdvertiseInterval, _cancellation.Token);
            }
        }
        catch (OperationCanceledException)
        {
            // Expected when the broadcaster is disposed.
        }
    }

    private async Task AdvertiseOnAllNetworksAsync()
    {
        foreach (var address in NetworkAddresses.GetAll())
        {
            if (address.Broadcast is null)
            {
                continue;
            }

            try
            {
                await AdvertiseFromAddressAsync(address, address.Broadcast);
            }
            catch (SocketException)
            {
                // One unreachable network must not stop the others.
            }
        }
    }

    private async Task AdvertiseFromAddressAsync(LanAddress address, IPAddress subnetBroadcast)
    {
        using var udp = new UdpClient(new IPEndPoint(address.Address, 0)) { EnableBroadcast = true };
        foreach (var target in new[] { subnetBroadcast, IPAddress.Broadcast }.Distinct())
        {
            var endpoint = new IPEndPoint(target, DiscoveryPort);
            await udp.SendAsync(Announcement, endpoint, _cancellation.Token);
            await udp.SendAsync(Encoding.UTF8.GetBytes(LegacyClientProbe), endpoint, _cancellation.Token);
        }
    }
}
