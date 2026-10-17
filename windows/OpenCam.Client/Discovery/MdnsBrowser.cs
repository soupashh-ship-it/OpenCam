using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace OpenCam.Client.Discovery;

/// <summary>A phone found on the local network.</summary>
public sealed record DiscoveredDevice(string Name, string Address, int Port, string ServiceType)
{
    public bool IsOpenCam => ServiceType.Contains("opencam", StringComparison.OrdinalIgnoreCase);

    public string Display => $"{Name}  ({Address}:{Port})";
}

/// <summary>
/// Minimal multicast DNS browser.
///
/// Windows has no general-purpose mDNS service resolver API, and pulling a full
/// DNS library in for two service types is not worth it, so this sends the PTR
/// queries itself and parses the PTR/SRV/A records that come back.
/// </summary>
public sealed class MdnsBrowser : IDisposable
{
    private const string MdnsAddress = "224.0.0.251";
    private const int MdnsPort = 5353;

    /// <summary>Service types to browse: OpenCam first, then the reference type.</summary>
    private static readonly string[] ServiceTypes =
    {
        "_opencam._tcp.local",
        "_droidcamobs._tcp.local",
    };

    private readonly List<UdpClient> _sockets = new();
    private readonly Dictionary<string, DiscoveredDevice> _found = new(StringComparer.OrdinalIgnoreCase);
    private readonly object _gate = new();
    private CancellationTokenSource? _cts;
    private Task? _loop;

    /// <summary>Raised on a background thread whenever the device list changes.</summary>
    public event Action<IReadOnlyList<DiscoveredDevice>>? DevicesChanged;

    public IReadOnlyList<DiscoveredDevice> Devices
    {
        get
        {
            lock (_gate)
            {
                return _found.Values
                    .OrderByDescending(d => d.IsOpenCam)
                    .ThenBy(d => d.Name, StringComparer.OrdinalIgnoreCase)
                    .ToList();
            }
        }
    }

    public void Start()
    {
        if (_loop is not null) return;

        _cts = new CancellationTokenSource();

        foreach (var address in LocalIPv4Addresses())
        {
            try
            {
                var socket = new UdpClient();
                socket.Client.SetSocketOption(
                    SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                socket.Client.Bind(new IPEndPoint(address, MdnsPort));
                socket.JoinMulticastGroup(IPAddress.Parse(MdnsAddress));

                // Allow the response to arrive on a second socket bound to an
                // ephemeral port as well, which some stacks prefer.
                socket.Client.ReceiveTimeout = 500;
                _sockets.Add(socket);
            }
            catch
            {
                // A busy 5353 endpoint or a virtual adapter we cannot join is fine;
                // discovery simply continues on the remaining interfaces.
            }
        }

        if (_sockets.Count == 0)
        {
            // Fall back to an unbound socket: we will miss the multicast group but
            // can still receive unicast responses.
            try
            {
                var socket = new UdpClient();
                _sockets.Add(socket);
            }
            catch
            {
                return;
            }
        }

        _loop = Task.Run(() => RunAsync(_cts.Token));
    }

    private async Task RunAsync(CancellationToken ct)
    {
        var targets = _sockets.Select(s => new IPEndPoint(IPAddress.Parse(MdnsAddress), MdnsPort));
        var sinceLastQuery = TimeSpan.MaxValue;

        while (!ct.IsCancellationRequested)
        {
            if (sinceLastQuery > TimeSpan.FromSeconds(4))
            {
                foreach (var type in ServiceTypes)
                {
                    var query = BuildPtrQuery(type);
                    foreach (var socket in _sockets)
                    {
                        foreach (var target in targets)
                        {
                            try
                            {
                                await socket.SendAsync(query, query.Length, target)
                                    .ConfigureAwait(false);
                            }
                            catch
                            {
                                // interface went away
                            }
                        }
                    }
                }
                sinceLastQuery = TimeSpan.Zero;
            }

            var received = false;
            foreach (var socket in _sockets.ToList())
            {
                try
                {
                    while (socket.Available > 0)
                    {
                        var result = await socket.ReceiveAsync(ct).ConfigureAwait(false);
                        received = true;
                        ProcessPacket(result.Buffer);
                    }
                }
                catch (OperationCanceledException)
                {
                    return;
                }
                catch
                {
                    // ignore malformed datagrams
                }
            }

            if (received || sinceLastQuery == TimeSpan.FromSeconds(4))
            {
                DevicesChanged?.Invoke(Devices);
            }

            await Task.Delay(250, ct).ConfigureAwait(false);
            sinceLastQuery += TimeSpan.FromMilliseconds(250);
        }
    }

    // ------------------------------------------------------------- DNS encoding

    private static byte[] BuildPtrQuery(string serviceType)
    {
        using var stream = new MemoryStream();
        void WriteShort(int value)
        {
            stream.WriteByte((byte)(value >> 8));
            stream.WriteByte((byte)value);
        }

        WriteShort(0);      // transaction id
        WriteShort(0);      // flags: standard query
        WriteShort(1);      // questions
        WriteShort(0);      // answers
        WriteShort(0);      // authority
        WriteShort(0);      // additional

        foreach (var label in serviceType.Split('.'))
        {
            if (label.Length == 0) continue;
            var bytes = Encoding.ASCII.GetBytes(label);
            stream.WriteByte((byte)bytes.Length);
            stream.Write(bytes);
        }
        stream.WriteByte(0);   // root label

        WriteShort(12);        // PTR
        WriteShort(1);         // IN class (no cache-flush bit on queries)
        return stream.ToArray();
    }

    // ------------------------------------------------------------- DNS decoding

    private void ProcessPacket(byte[] packet)
    {
        if (packet.Length < 12) return;

        int ReadShort(int offset) => (packet[offset] << 8) | packet[offset + 1];

        var questionCount = ReadShort(4);
        var answerCount = ReadShort(6);
        var authorityCount = ReadShort(8);
        var additionalCount = ReadShort(10);

        var offset = 12;
        try
        {
            for (var i = 0; i < questionCount; i++)
            {
                offset = SkipName(packet, offset);
                offset += 4;
            }
        }
        catch
        {
            return;
        }

        var records = new List<DnsRecord>();
        var total = answerCount + authorityCount + additionalCount;
        for (var i = 0; i < total; i++)
        {
            try
            {
                var parsed = ReadRecord(packet, offset);
                if (parsed is null) break;
                offset = parsed.Value.NextOffset;
                records.Add(parsed.Value.Record);
            }
            catch
            {
                break;
            }
        }

        Incorporate(records);
    }

    private readonly record struct DnsRecord(string Name, int Type, string Text, string Target, int Port);

    private static (DnsRecord Record, int NextOffset)? ReadRecord(byte[] packet, int offset)
    {
        var name = ReadName(packet, ref offset);
        if (offset + 10 > packet.Length) return null;

        var type = (packet[offset] << 8) | packet[offset + 1];
        var length = (packet[offset + 8] << 8) | packet[offset + 9];
        var rdataStart = offset + 10;
        if (rdataStart + length > packet.Length) return null;

        var text = "";
        var target = "";
        var port = 0;

        switch (type)
        {
            case 12: // PTR
                var ptrOffset = rdataStart;
                text = ReadName(packet, ref ptrOffset);
                break;

            case 33: // SRV
                if (length >= 6)
                {
                    port = (packet[rdataStart + 4] << 8) | packet[rdataStart + 5];
                    var srvOffset = rdataStart + 6;
                    target = ReadName(packet, ref srvOffset);
                }
                break;

            case 1: // A
                if (length == 4)
                {
                    text = new IPAddress(packet.Skip(rdataStart).Take(4).ToArray()).ToString();
                }
                break;
        }

        return (new DnsRecord(name, type, text, target, port), rdataStart + length);
    }

    private static int SkipName(byte[] packet, int offset)
    {
        var guard = 0;
        while (offset < packet.Length && guard++ < 128)
        {
            var length = packet[offset];
            if (length == 0) return offset + 1;
            if ((length & 0xC0) == 0xC0) return offset + 2;
            offset += 1 + length;
        }
        return offset;
    }

    /// <summary>Reads a possibly compressed DNS name.</summary>
    private static string ReadName(byte[] packet, ref int offset)
    {
        var labels = new List<string>();
        var guard = 0;
        var jumped = false;
        var cursor = offset;

        while (cursor < packet.Length && guard++ < 128)
        {
            var length = packet[cursor];

            if (length == 0)
            {
                cursor++;
                if (!jumped) offset = cursor;
                break;
            }

            if ((length & 0xC0) == 0xC0)
            {
                if (cursor + 1 >= packet.Length) break;
                var pointer = ((length & 0x3F) << 8) | packet[cursor + 1];
                if (!jumped) offset = cursor + 2;
                jumped = true;
                cursor = pointer;
                continue;
            }

            if (cursor + 1 + length > packet.Length) break;
            labels.Add(Encoding.ASCII.GetString(packet, cursor + 1, length));
            cursor += 1 + length;
            if (!jumped) offset = cursor;
        }

        return string.Join('.', labels);
    }

    /// <summary>
    /// Stitches PTR, SRV and A records together into devices. The naming is:
    /// PTR gives the instance, SRV gives host and port, A gives the address.
    /// </summary>
    private void Incorporate(List<DnsRecord> records)
    {
        var instances = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase); // instance -> service type
        var services = new Dictionary<string, (string Host, int Port)>(StringComparer.OrdinalIgnoreCase);
        var addresses = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var txtNames = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        foreach (var record in records)
        {
            switch (record.Type)
            {
                case 12:
                    if (record.Name.StartsWith('_')) instances[record.Text] = record.Name;
                    break;
                case 33:
                    services[record.Name] = (record.Target, record.Port);
                    break;
                case 1:
                    addresses[record.Name] = record.Text;
                    break;
            }
        }

        foreach (var (instance, serviceType) in instances)
        {
            if (!services.TryGetValue(instance, out var service)) continue;
            if (!addresses.TryGetValue(service.Host, out var address))
            {
                // Some responders give a bare hostname that we cannot resolve
                // without another round trip, so skip it for now.
                continue;
            }

            var friendly = instance.Split('.').FirstOrDefault() ?? instance;
            if (txtNames.TryGetValue(instance, out var txt) && txt.Length > 0) friendly = txt;

            lock (_gate)
            {
                _found[instance] = new DiscoveredDevice(
                    friendly,
                    address,
                    service.Port == 0 ? 4747 : service.Port,
                    serviceType);
            }
        }
    }

    private static IEnumerable<IPAddress> LocalIPv4Addresses()
    {
        var result = new List<IPAddress>();
        foreach (var nic in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
            if (nic.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback)
                continue;

            foreach (var info in nic.GetIPProperties().UnicastAddresses)
            {
                if (info.Address.AddressFamily == AddressFamily.InterNetwork)
                {
                    result.Add(info.Address);
                }
            }
        }
        return result;
    }

    public void Stop()
    {
        _cts?.Cancel();
        foreach (var socket in _sockets)
        {
            try
            {
                socket.DropMulticastGroup(IPAddress.Parse(MdnsAddress));
            }
            catch
            {
            }
            socket.Dispose();
        }
        _sockets.Clear();
        _loop = null;
        _cts = null;
    }

    public void Dispose() => Stop();
}
