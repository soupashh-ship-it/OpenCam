using System.Text.Json;
using System.Text.Json.Serialization;

namespace OpenCam.Client.Protocol;

/// <summary>One camera on the phone, as listed by `/v1/camera/camera_list`.</summary>
public sealed record CameraEntry(int Index, string Id, string Facing)
{
    public string Label => $"{Facing} ({Id})";
}

/// <summary>Parsed `/v1/camera/info` state.</summary>
public sealed class CameraInfo
{
    public string Camera { get; init; } = "";
    public int Active { get; init; }
    public int Width { get; init; }
    public int Height { get; init; }
    public int Fps { get; init; }
    public float Zoom { get; init; }
    public float Ev { get; init; }
    public int Iso { get; init; }
    public long ShutterNs { get; init; }
    public int WbMode { get; init; }
    public float WbLevel { get; init; }
    public int AfMode { get; init; }
    public float ManualFocus { get; init; }
    public bool Torch { get; init; }
    public bool Mic { get; init; }

    public static CameraInfo Parse(string text)
    {
        var map = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var token in text.Split(' ', StringSplitOptions.RemoveEmptyEntries))
        {
            var eq = token.IndexOf('=');
            if (eq > 0) map[token[..eq]] = token[(eq + 1)..];
        }

        string S(string k) => map.TryGetValue(k, out var v) ? v : "";
        int I(string k) => int.TryParse(S(k), out var v) ? v : 0;
        long L(string k) => long.TryParse(S(k), out var v) ? v : 0;
        float F(string k) =>
            float.TryParse(S(k), System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var v) ? v : 0f;
        bool B(string k) => S(k) is "true" or "1";

        // `/v1/camera/info` reports the capture size as `size=<W>x<H>`.
        var width = 0;
        var height = 0;
        if (map.TryGetValue("size", out var size))
        {
            var x = size.IndexOf('x');
            if (x > 0)
            {
                int.TryParse(size[..x], out width);
                int.TryParse(size[(x + 1)..], out height);
            }
        }

        return new CameraInfo
        {
            Camera = S("camera"),
            Active = I("active"),
            Width = width,
            Height = height,
            Fps = I("fps"),
            Zoom = F("zoom"),
            Ev = F("ev"),
            Iso = I("iso"),
            ShutterNs = L("ss_ns"),
            WbMode = I("wb"),
            WbLevel = F("wb_level"),
            AfMode = I("af"),
            ManualFocus = F("mf"),
            Torch = B("torch"),
            Mic = B("mic"),
        };
    }
}

/// <summary>Parsed `/v1/status` JSON.</summary>
public sealed class DeviceStatus
{
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("port")] public int Port { get; set; }
    [JsonPropertyName("streaming")] public bool Streaming { get; set; }
    [JsonPropertyName("video_clients")] public int VideoClients { get; set; }
    [JsonPropertyName("audio_clients")] public int AudioClients { get; set; }
    [JsonPropertyName("fps")] public int Fps { get; set; }
    [JsonPropertyName("width")] public int Width { get; set; }
    [JsonPropertyName("height")] public int Height { get; set; }
    [JsonPropertyName("battery")] public int Battery { get; set; }
    [JsonPropertyName("tally")] public int Tally { get; set; }

    public static DeviceStatus? TryParse(string json)
    {
        try
        {
            return JsonSerializer.Deserialize<DeviceStatus>(json);
        }
        catch
        {
            return null;
        }
    }
}

/// <summary>White balance presets, in the order the control endpoint accepts.</summary>
public static class WbModes
{
    public static readonly string[] Labels =
    {
        "Auto", "Incandescent", "Fluorescent", "Warm Fluorescent",
        "Daylight", "Cloudy", "Twilight", "Shade"
    };
}

/// <summary>Autofocus modes, in the order the control endpoint accepts.</summary>
public static class AfModes
{
    public static readonly string[] Labels =
    {
        "Off", "Auto", "Macro", "Continuous Video", "Continuous Picture", "EDOF"
    };
}

/// <summary>Resolution presets the phone understands.</summary>
public static class Resolutions
{
    public static readonly (string Name, int Width, int Height)[] Presets =
    {
        ("480p", 640, 480),
        ("720s", 960, 720),
        ("768s", 1024, 768),
        ("720p", 1280, 720),
        ("1080p", 1920, 1080),
        ("1440p", 2560, 1440),
        ("2160p", 3840, 2160),
    };
}
