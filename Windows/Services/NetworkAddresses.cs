using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Numerics;

namespace P3k_Bluetooth_GamePad_Over_LAN.Services;

public sealed record LanAddress(string Interface, IPAddress Address, IPAddress Mask, IPAddress? Broadcast)
{
    public override string ToString() => $"{Interface}: {Address}/{NetworkAddresses.PrefixLength(Mask)}";
}

public static class NetworkAddresses
{
    public static int PrefixLength(IPAddress mask) =>
        mask.GetAddressBytes().Sum(octet => BitOperations.PopCount((uint)octet));

    /// <summary>
    /// The directed broadcast address of a subnet, or null when the prefix has none (/0, /31 and /32).
    /// Throws <see cref="ArgumentException"/> for non-IPv4 input or a mask whose one-bits are not contiguous.
    /// </summary>
    public static IPAddress? BroadcastAddress(IPAddress address, IPAddress mask)
    {
        if (address.AddressFamily != AddressFamily.InterNetwork || mask.AddressFamily != AddressFamily.InterNetwork)
        {
            throw new ArgumentException("IPv4 addresses and masks are required.");
        }

        var addressBytes = address.GetAddressBytes();
        var maskBytes = mask.GetAddressBytes();
        var prefix = PrefixLength(mask);
        if (ToUInt32(maskBytes) != ContiguousMask(prefix))
        {
            throw new ArgumentException("The IPv4 mask is not contiguous.");
        }

        // No directed broadcast on point-to-point/host routes.
        if (prefix is 0 or >= 31)
        {
            return null;
        }

        var broadcastBytes = addressBytes
            .Zip(maskBytes, (addressByte, maskByte) => (byte)(addressByte | (byte)~maskByte))
            .ToArray();
        return new IPAddress(broadcastBytes);
    }

    public static IReadOnlyList<LanAddress> GetAll()
    {
        var addresses = new List<LanAddress>();
        foreach (var networkInterface in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (!IsUsable(networkInterface))
            {
                continue;
            }

            addresses.AddRange(GetIpv4Addresses(networkInterface));
        }

        return addresses
            .OrderBy(address => address.Interface.Contains("VM", StringComparison.OrdinalIgnoreCase))
            .ThenBy(address => address.Interface)
            .ToArray();
    }

    private static bool IsUsable(NetworkInterface networkInterface) =>
        networkInterface.OperationalStatus == OperationalStatus.Up
        && networkInterface.NetworkInterfaceType != NetworkInterfaceType.Loopback;

    private static IEnumerable<LanAddress> GetIpv4Addresses(NetworkInterface networkInterface)
    {
        foreach (var unicast in networkInterface.GetIPProperties().UnicastAddresses)
        {
            if (unicast.Address.AddressFamily != AddressFamily.InterNetwork || IPAddress.IsLoopback(unicast.Address))
            {
                continue;
            }

            LanAddress lanAddress;
            try
            {
                var broadcast = BroadcastAddress(unicast.Address, unicast.IPv4Mask);
                lanAddress = new LanAddress(networkInterface.Name, unicast.Address, unicast.IPv4Mask, broadcast);
            }
            catch (ArgumentException)
            {
                // Skip addresses with an unusable mask.
                continue;
            }

            yield return lanAddress;
        }
    }

    private static uint ToUInt32(byte[] bigEndianBytes) =>
        ((uint)bigEndianBytes[0] << 24) | ((uint)bigEndianBytes[1] << 16) | ((uint)bigEndianBytes[2] << 8)
        | bigEndianBytes[3];

    private static uint ContiguousMask(int prefix) => prefix == 0 ? 0 : uint.MaxValue << (32 - prefix);
}
