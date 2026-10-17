using System.Globalization;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;

namespace OpenCam.Client.Protocol;

/// <summary>
/// Talks to one phone.
///
/// Control and query calls go through a normal <see cref="HttpClient"/>. The two
/// long-lived streams use a separate client configured without a request timeout,
/// because a video or audio stream is expected to stay open indefinitely.
/// </summary>
public sealed class OpenCamConnection : IDisposable
{
    private readonly HttpClient _control;
    private readonly HttpClient _stream;

    public string Host { get; }
    public int Port { get; }

    /// <summary>
    /// When true (the default) video is pulled from `/video`, the classic MJPEG
    /// route that both OpenCam and stock DroidCam servers support. When false the
    /// versioned `/v5/video/...` route is used with the negotiated size.
    /// </summary>
    public bool UseLegacyVideoRoute { get; set; } = true;

    /// <summary>Size requested on the versioned video route.</summary>
    public (int Width, int Height) RequestedSize { get; set; } = (1280, 720);

    public string? LastError { get; private set; }

    public string BaseUrl => $"http://{Host}:{Port}";

    public OpenCamConnection(string host, int port)
    {
        Host = host;
        Port = port;

        _control = new HttpClient(new SocketsHttpHandler
        {
            UseProxy = false,
            ConnectTimeout = TimeSpan.FromSeconds(8),
            PooledConnectionLifetime = TimeSpan.FromMinutes(2),
        })
        {
            Timeout = TimeSpan.FromSeconds(20),
        };

        _stream = new HttpClient(new SocketsHttpHandler
        {
            UseProxy = false,
            ConnectTimeout = TimeSpan.FromSeconds(8),
            PooledConnectionIdleTimeout = Timeout.InfiniteTimeSpan,
        })
        {
            Timeout = Timeout.InfiniteTimeSpan,
        };
    }

    // ------------------------------------------------------------------ queries

    public async Task<DeviceStatus?> GetStatusAsync(CancellationToken ct = default)
    {
        var text = await GetTextSafeAsync($"{BaseUrl}/v1/status", ct).ConfigureAwait(false);
        return text is null ? null : DeviceStatus.TryParse(text);
    }

    public async Task<string?> GetDeviceNameAsync(CancellationToken ct = default)
    {
        // Falls back to the name field in /v1/status for servers without the route.
        var name = await GetTextSafeAsync($"{BaseUrl}/v1/phone/name", ct).ConfigureAwait(false);
        if (!string.IsNullOrWhiteSpace(name)) return name.Trim();
        var status = await GetStatusAsync(ct).ConfigureAwait(false);
        return status?.Name;
    }

    public async Task<int> GetBatteryAsync(CancellationToken ct = default)
    {
        var text = await GetTextSafeAsync($"{BaseUrl}/battery", ct).ConfigureAwait(false);
        return int.TryParse(text?.Trim(), out var level) ? level : -1;
    }

    public async Task<IReadOnlyList<CameraEntry>> GetCameraListAsync(CancellationToken ct = default)
    {
        var text = await GetTextSafeAsync($"{BaseUrl}/v1/camera/camera_list", ct).ConfigureAwait(false);
        if (string.IsNullOrWhiteSpace(text)) return Array.Empty<CameraEntry>();

        var list = new List<CameraEntry>();
        var index = 0;
        foreach (var line in text.Split('\n', StringSplitOptions.RemoveEmptyEntries))
        {
            var trimmed = line.Trim();
            if (trimmed.Length == 0) continue;
            var colon = trimmed.IndexOf(':');
            var id = colon > 0 ? trimmed[..colon] : trimmed;
            var facing = colon > 0 ? trimmed[(colon + 1)..] : "unknown";
            list.Add(new CameraEntry(index++, id, facing));
        }
        return list;
    }

    public async Task<CameraInfo?> GetCameraInfoAsync(CancellationToken ct = default)
    {
        var text = await GetTextSafeAsync($"{BaseUrl}/v1/camera/info", ct).ConfigureAwait(false);
        return string.IsNullOrWhiteSpace(text) ? null : CameraInfo.Parse(text);
    }

    /// <summary>
    /// Cheap liveness check used by discovery and the LAN scan. Any HTTP response
    /// from `/v1/status` or `/v1/phone/name` counts as a compatible server.
    /// </summary>
    public static async Task<DeviceStatus?> ProbeAsync(string host, int port, TimeSpan timeout)
    {
        using var cts = new CancellationTokenSource(timeout);
        using var connection = new OpenCamConnection(host, port);
        var status = await connection.GetStatusAsync(cts.Token).ConfigureAwait(false);
        if (status is not null) return status;

        var name = await connection.GetDeviceNameAsync(cts.Token).ConfigureAwait(false);
        return name is null ? null : new DeviceStatus { Name = name, Port = port };
    }

    // ----------------------------------------------------------------- controls

    public Task<bool> SetZoomAsync(float value, CancellationToken ct = default) =>
        PutAsync($"/v3/camera/zoom/{Invariant(value)}", ct);

    public Task<bool> SetEvAsync(float value, CancellationToken ct = default) =>
        PutAsync($"/v3/camera/ev/{Invariant(value)}", ct);

    public Task<bool> SetIsoAsync(int value, CancellationToken ct = default) =>
        PutAsync($"/v3/camera/iso/{value}", ct);

    /// <summary>Shutter speed in milliseconds; 0 restores automatic exposure.</summary>
    public Task<bool> SetShutterMsAsync(int milliseconds, CancellationToken ct = default) =>
        PutAsync($"/v3/camera/ss/{milliseconds}", ct);

    /// <summary>Manual focus 0..1; 0 restores autofocus.</summary>
    public Task<bool> SetManualFocusAsync(float value, CancellationToken ct = default) =>
        PutAsync($"/v3/camera/mf/{Invariant(value)}", ct);

    public Task<bool> SetWhiteBalanceLevelAsync(float value, CancellationToken ct = default) =>
        PutAsync($"/v2/camera/wb_level?wb_level={Invariant(value)}", ct);

    public Task<bool> SetWhiteBalanceModeAsync(int mode, CancellationToken ct = default) =>
        PutAsync($"/v1/camera/wb_mode?wb_mode={mode}", ct);

    public Task<bool> SetAutofocusModeAsync(int mode, CancellationToken ct = default) =>
        PutAsync($"/v1/camera/autofocus_mode?autofocus_mode={mode}", ct);

    public Task<bool> TriggerAutofocusAsync(CancellationToken ct = default) =>
        PutAsync("/v1/camera/autofocus", ct);

    public Task<bool> ToggleMicrophoneAsync(CancellationToken ct = default) =>
        PutAsync("/v1/camera/mic_toggle", ct);

    public Task<bool> ToggleTorchAsync(CancellationToken ct = default) =>
        PutAsync("/v1/camera/torch_toggle", ct);

    public Task<bool> ToggleAeLockAsync(CancellationToken ct = default) =>
        PutAsync("/v1/camera/ae_toggle", ct);

    public Task<bool> ToggleWhiteBalanceLockAsync(CancellationToken ct = default) =>
        PutAsync("/v1/camera/wbl_toggle", ct);

    public Task<bool> SelectCameraAsync(int index, CancellationToken ct = default) =>
        PutAsync($"/v1/camera/active?active={index}", ct);

    /// <summary>Tally state: 0 idle, 1 program, 2 preview.</summary>
    public Task<bool> SetTallyAsync(int state, CancellationToken ct = default) =>
        PutAsync($"/v1/tally?tally={state}", ct);

    public Task<bool> RestartStreamAsync(CancellationToken ct = default) =>
        PutAsync("/v1/restart", ct);

    public Task<bool> StopStreamAsync(CancellationToken ct = default) =>
        PutAsync("/v1/stop", ct);

    private static string Invariant(float value) =>
        value.ToString("0.###", CultureInfo.InvariantCulture);

    private async Task<bool> PutAsync(string path, CancellationToken ct)
    {
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Put, BaseUrl + path);
            using var response = await _control.SendAsync(request, ct).ConfigureAwait(false);
            LastError = null;
            return response.IsSuccessStatusCode;
        }
        catch (Exception e)
        {
            LastError = e.Message;
            return false;
        }
    }

    private async Task<string?> GetTextSafeAsync(string url, CancellationToken ct)
    {
        try
        {
            var text = await _control.GetStringAsync(url, ct).ConfigureAwait(false);
            LastError = null;
            return text;
        }
        catch (Exception e)
        {
            LastError = e.Message;
            return null;
        }
    }

    // ------------------------------------------------------------------ streams

    /// <summary>
    /// Pulls frames until cancelled or the stream ends. Runs on the calling thread
    /// (the caller should hand this to a background task).
    /// </summary>
    public async Task PumpVideoAsync(Action<VideoFrame> onFrame, CancellationToken ct)
    {
        var url = UseLegacyVideoRoute
            ? $"{BaseUrl}/video"
            : $"{BaseUrl}/v5/video/back/{RequestedSize.Width}x{RequestedSize.Height}/port/{Port}/os/windows/obs/0/client/opencam/nonce/{Environment.TickCount}/";

        using var response = await _stream
            .GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct)
            .ConfigureAwait(false);
        response.EnsureSuccessStatusCode();

        await using var raw = await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        var reader = new FramedStreamReader(raw);
        try
        {
            while (!ct.IsCancellationRequested)
            {
                var jpeg = await MjpegDemuxer.ReadFrameAsync(reader, ct).ConfigureAwait(false);
                if (jpeg is null) break;

                var (width, height) = JpegInspector.Dimensions(jpeg);
                onFrame(new VideoFrame(jpeg, width, height, DateTimeOffset.Now));
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception e)
        {
            LastError = e.Message;
            throw;
        }
        finally
        {
            reader.Release();
        }
    }

    /// <summary>
    /// Pulls PCM audio from `/v1/audio`. The WAV header is parsed first so the
    /// caller can configure playback before any samples arrive.
    /// </summary>
    public async Task PumpAudioAsync(
        Action<WavFormat> onFormat,
        Action<byte[], int> onData,
        CancellationToken ct)
    {
        using var response = await _stream
            .GetAsync($"{BaseUrl}/v1/audio", HttpCompletionOption.ResponseHeadersRead, ct)
            .ConfigureAwait(false);
        response.EnsureSuccessStatusCode();

        await using var raw = await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        var reader = new FramedStreamReader(raw);
        try
        {
            var format = await WavFormat.ReadAsync(reader, ct).ConfigureAwait(false);
            if (format is null)
            {
                LastError = "audio stream did not start with a WAV header";
                return;
            }

            onFormat(format);

            var buffer = new byte[format.SampleRate * format.BlockAlign / 10];
            while (!ct.IsCancellationRequested)
            {
                var read = await reader.ReadSomeAsync(buffer, 0, buffer.Length, ct)
                    .ConfigureAwait(false);
                if (read == 0) break;
                onData(buffer, read);
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception e)
        {
            LastError = e.Message;
            throw;
        }
        finally
        {
            reader.Release();
        }
    }

    public void Dispose()
    {
        _control.Dispose();
        _stream.Dispose();
    }
}

/// <summary>Reads the pixel dimensions straight out of a JPEG's SOF marker.</summary>
public static class JpegInspector
{
    public static (int Width, int Height) Dimensions(ReadOnlySpan<byte> jpeg)
    {
        if (jpeg.Length < 4 || jpeg[0] != 0xFF || jpeg[1] != 0xD8) return (0, 0);

        var i = 2;
        while (i + 9 < jpeg.Length)
        {
            if (jpeg[i] != 0xFF)
            {
                i++;
                continue;
            }

            var marker = jpeg[i + 1];

            if (marker is 0xC0 or 0xC1 or 0xC2)
            {
                var height = (jpeg[i + 5] << 8) | jpeg[i + 6];
                var width = (jpeg[i + 7] << 8) | jpeg[i + 8];
                return (width, height);
            }

            if (marker is 0xD8 or 0xD9 || (marker >= 0xD0 && marker <= 0xD7))
            {
                i += 2;
                continue;
            }

            if (i + 3 >= jpeg.Length) break;
            var length = (jpeg[i + 2] << 8) | jpeg[i + 3];
            if (length < 2) break;
            i += 2 + length;
        }

        return (0, 0);
    }

    public static bool IsJpeg(ReadOnlySpan<byte> data) =>
        data.Length >= 2 && data[0] == 0xFF && data[1] == 0xD8;
}
