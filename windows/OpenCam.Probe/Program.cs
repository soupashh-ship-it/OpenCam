using System.IO;
using OpenCam.Client.Protocol;
using OpenCam.Probe;

// Verifies the Windows client's protocol layer against a live server.
// Usage: opencam-probe [host:port]
//        opencam-probe --publish-vcam [seconds] [width] [height]

// This mode needs no phone: it writes frames into the shared buffer the virtual
// camera reads, so the native read test can be run on its own.
if (args.Length > 0 && args[0] == "--publish-vcam")
{
    return VirtualCameraBridge.Publish(args);
}

var target = args.Length > 0 ? args[0] : "127.0.0.1:4747";
var host = target;
var port = 4747;

var colon = target.LastIndexOf(':');
if (colon > 0)
{
    host = target[..colon];
    if (!int.TryParse(target[(colon + 1)..], out port)) port = 4747;
}

Console.WriteLine($"OpenCam Windows protocol probe against {host}:{port}");
Console.WriteLine();

var passed = 0;
var failed = 0;

void Check(string name, bool ok, string detail = "")
{
    if (ok)
    {
        passed++;
        Console.WriteLine($"  PASS  {name}" + (detail.Length > 0 ? $"  [{detail}]" : ""));
    }
    else
    {
        failed++;
        Console.WriteLine($"  FAIL  {name}" + (detail.Length > 0 ? $"  [{detail}]" : ""));
    }
}

using var connection = new OpenCamConnection(host, port);

// ---------------------------------------------------------------- connectivity
Console.WriteLine("[connectivity]");

var reachable = false;
try
{
    using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(8));
    var status = await connection.GetStatusAsync(cts.Token);
    reachable = true;

    Check("GET /v1/status parses", status is not null);
    if (status is not null)
    {
        Check("status reports a name", status.Name.Length > 0, status.Name);
        Check("status reports streaming", status.Streaming,
            $"{status.Width}x{status.Height} @ {status.Fps}fps");
        Check("status reports a port", status.Port > 0, status.Port.ToString());
        Check("status reports battery", status.Battery is >= 0 and <= 100,
            status.Battery.ToString());
    }
}
catch (Exception e)
{
    Console.WriteLine($"  FAIL  cannot reach the server: {e.Message}");
    Console.WriteLine();
    Console.WriteLine("Start OpenCam on the phone, then forward the port:");
    Console.WriteLine("  adb forward tcp:4747 tcp:4747");
    return 2;
}

if (!reachable) return 2;

var name = await connection.GetDeviceNameAsync();
Check("GET /v1/phone/name", !string.IsNullOrWhiteSpace(name), name ?? "null");

var battery = await connection.GetBatteryAsync();
Check("GET /battery returns a percentage", battery is >= 0 and <= 100, battery.ToString());

var cameras = await connection.GetCameraListAsync();
Check("GET /v1/camera/camera_list parses entries", cameras.Count > 0,
    string.Join(", ", cameras.Select(c => c.Label)));

var info = await connection.GetCameraInfoAsync();
Check("GET /v1/camera/info parses", info is not null);
if (info is not null)
{
    Check("camera info carries dimensions", info.Width > 0 && info.Height > 0,
        $"{info.Width}x{info.Height}");
    Check("camera info carries a frame rate", info.Fps > 0, info.Fps.ToString());
}

// --------------------------------------------------------------------- video
Console.WriteLine();
Console.WriteLine("[video]");

var frames = new List<VideoFrame>();
var frameLock = new object();
var sawBadFrame = false;
string badDetail = "";

using (var cts = new CancellationTokenSource(TimeSpan.FromSeconds(6)))
{
    try
    {
        await connection.PumpVideoAsync(frame =>
        {
            var (w, h) = JpegInspector.Dimensions(frame.Jpeg);
            if (!JpegInspector.IsJpeg(frame.Jpeg) || w <= 0 || h <= 0)
            {
                sawBadFrame = true;
                badDetail = $"first bytes {Convert.ToHexString(frame.Jpeg.AsSpan(0, Math.Min(4, frame.Jpeg.Length)))}";
            }
            lock (frameLock)
            {
                if (frames.Count < 200) frames.Add(frame);
            }
        }, cts.Token);
    }
    catch (OperationCanceledException)
    {
    }
    catch (Exception e)
    {
        Check("video stream stays connected", false, e.Message);
    }
}

Check("MJPEG stream produced frames", frames.Count >= 5, $"{frames.Count} frames");
Check("every frame is a complete JPEG", !sawBadFrame, badDetail);

if (frames.Count > 0)
{
    var first = frames[0];
    var (width, height) = JpegInspector.Dimensions(first.Jpeg);
    Check("frames carry decodable dimensions", width > 0 && height > 0, $"{width}x{height}");

    var sizes = frames.Select(f => f.Jpeg.Length).Where(s => s > 0).ToList();
    Check("frames are non-trivial in size", sizes.Count > 0 && sizes.Min() > 2000,
        $"min={sizes.Min()}B max={sizes.Max()}B");

    var span = frames[^1].ReceivedAt - frames[0].ReceivedAt;
    var fps = span.TotalSeconds > 0 ? (frames.Count - 1) / span.TotalSeconds : 0;
    Check("frames arrive at a usable rate", fps >= 3,
        $"{fps:0.0} fps over {span.TotalSeconds:0.0}s");

    // A JPEG that decodes in .NET proves the bytes are not just well-formed headers.
    var decoded = TryDecodeJpeg(first.Jpeg, out var decodeError);
    Check("a frame decodes as an image", decoded, decodeError);
}

// --------------------------------------------------------------------- audio
Console.WriteLine();
Console.WriteLine("[audio]");

WavFormat? format = null;
var audioBytes = 0;

using (var cts = new CancellationTokenSource(TimeSpan.FromSeconds(4)))
{
    try
    {
        await connection.PumpAudioAsync(
            f => format = f,
            (data, count) => Interlocked.Add(ref audioBytes, count),
            cts.Token);
    }
    catch (OperationCanceledException)
    {
    }
    catch (Exception e)
    {
        Check("audio stream stays connected", false, e.Message);
    }
}

Check("WAV header parsed", format is not null,
    format is null ? "none" : $"{format.SampleRate}Hz ch={format.Channels} bits={format.BitsPerSample}");
Check("audio is 48 kHz mono 16-bit",
    format is { SampleRate: 48000, Channels: 1, BitsPerSample: 16 },
    format is null ? "n/a" : $"{format.SampleRate}/{format.Channels}/{format.BitsPerSample}");
Check("audio samples arrived", audioBytes > 8000, $"{audioBytes} bytes");

// ------------------------------------------------------------------ controls
Console.WriteLine();
Console.WriteLine("[controls]");

async Task<string> ReadInfoAsync()
{
    var current = await connection.GetCameraInfoAsync();
    return current is null
        ? ""
        : $"zoom={current.Zoom} ev={current.Ev} iso={current.Iso} " +
          $"ss={current.ShutterNs / 1_000_000} wb={current.WbMode} mf={current.ManualFocus}";
}

Check("PUT /v3/camera/zoom/0.5", await connection.SetZoomAsync(0.5f));
var afterZoom = await connection.GetCameraInfoAsync();
Check("zoom read back", afterZoom is not null && Math.Abs(afterZoom.Zoom - 0.5f) < 0.01f,
    afterZoom?.Zoom.ToString() ?? "?");

Check("PUT /v3/camera/ev/1.5", await connection.SetEvAsync(1.5f));
var afterEv = await connection.GetCameraInfoAsync();
Check("EV read back", afterEv is not null && Math.Abs(afterEv.Ev - 1.5f) < 0.01f,
    afterEv?.Ev.ToString() ?? "?");

Check("PUT /v3/camera/iso/800", await connection.SetIsoAsync(800));
var afterIso = await connection.GetCameraInfoAsync();
Check("ISO read back", afterIso?.Iso == 800, afterIso?.Iso.ToString() ?? "?");

Check("PUT /v3/camera/ss/30", await connection.SetShutterMsAsync(30));
var afterSs = await connection.GetCameraInfoAsync();
Check("shutter read back", afterSs?.ShutterNs == 30_000_000L,
    (afterSs?.ShutterNs ?? 0).ToString());

Check("PUT /v2/camera/wb_level", await connection.SetWhiteBalanceLevelAsync(0.25f));
var afterWb = await connection.GetCameraInfoAsync();
Check("white balance level read back",
    afterWb is not null && Math.Abs(afterWb.WbLevel - 0.25f) < 0.01f,
    afterWb?.WbLevel.ToString() ?? "?");

Check("PUT /v1/camera/wb_mode", await connection.SetWhiteBalanceModeAsync(4));
var afterWbMode = await connection.GetCameraInfoAsync();
Check("white balance mode read back", afterWbMode?.WbMode == 4,
    afterWbMode?.WbMode.ToString() ?? "?");

Check("PUT /v1/camera/autofocus_mode", await connection.SetAutofocusModeAsync(1));
var afterAf = await connection.GetCameraInfoAsync();
Check("autofocus mode read back", afterAf?.AfMode == 1, afterAf?.AfMode.ToString() ?? "?");

Check("PUT /v1/camera/autofocus", await connection.TriggerAutofocusAsync());

var micBefore = (await connection.GetCameraInfoAsync())?.Mic;
Check("PUT /v1/camera/mic_toggle", await connection.ToggleMicrophoneAsync());
var micAfter = (await connection.GetCameraInfoAsync())?.Mic;
Check("microphone state changed", micBefore != micAfter, $"{micBefore} -> {micAfter}");
await connection.ToggleMicrophoneAsync();

var torchBefore = (await connection.GetCameraInfoAsync())?.Torch;
Check("PUT /v1/camera/torch_toggle", await connection.ToggleTorchAsync());
var torchAfter = (await connection.GetCameraInfoAsync())?.Torch;
Check("torch state changed", torchBefore != torchAfter, $"{torchBefore} -> {torchAfter}");
await connection.ToggleTorchAsync();

Check("PUT /v1/tally", await connection.SetTallyAsync(1));
await Task.Delay(150);
var statusAfterTally = await connection.GetStatusAsync();
Check("tally read back as program", statusAfterTally?.Tally == 1,
    statusAfterTally?.Tally.ToString() ?? "?");
await connection.SetTallyAsync(0);

Check("PUT /v1/camera/active", await connection.SelectCameraAsync(0));

Check("PUT /v1/camera/ae_toggle", await connection.ToggleAeLockAsync());
Check("PUT /v1/camera/wbl_toggle", await connection.ToggleWhiteBalanceLockAsync());

// ------------------------------------------------------- versioned video route
Console.WriteLine();
Console.WriteLine("[versioned video route]");

connection.RequestedSize = (640, 480);
connection.UseLegacyVideoRoute = false;

var resized = new List<VideoFrame>();
using (var cts = new CancellationTokenSource(TimeSpan.FromSeconds(6)))
{
    try
    {
        var acquired = 0;
        await connection.PumpVideoAsync(frame =>
        {
            lock (resized)
            {
                if (resized.Count < 40) resized.Add(frame);
            }
            if (Interlocked.Increment(ref acquired) >= 40) cts.Cancel();
        }, cts.Token);
    }
    catch (OperationCanceledException)
    {
    }
    catch (Exception e)
    {
        Check("versioned route streams", false, e.Message);
    }
}

Check("versioned route produced frames", resized.Count > 0, $"{resized.Count} frames");
if (resized.Count > 0)
{
    var (w, h) = JpegInspector.Dimensions(resized[^1].Jpeg);
    Check("versioned route applied the requested size", w == 640 && h == 480, $"{w}x{h}");
}

// --------------------------------------------------------------------- reset
connection.UseLegacyVideoRoute = true;
await connection.SetZoomAsync(0);
await connection.SetEvAsync(0);
await connection.SetIsoAsync(0);
await connection.SetShutterMsAsync(0);
await connection.SetManualFocusAsync(0);
await connection.SetWhiteBalanceLevelAsync(0.5f);
await connection.SetWhiteBalanceModeAsync(0);

Console.WriteLine();
Console.WriteLine($"{passed} passed, {failed} failed");
if (failed > 0) Console.WriteLine($"Last state: {await ReadInfoAsync()}");

return failed == 0 ? 0 : 1;

/// <summary>
/// Decodes a frame with the exact decoder the WPF client uses, so a pass here
/// means the UI will be able to draw the frame.
/// </summary>
static bool TryDecodeJpeg(byte[] jpeg, out string error)
{
    try
    {
        using var stream = new MemoryStream(jpeg, writable: false);
        var decoder = new System.Windows.Media.Imaging.JpegBitmapDecoder(
            stream,
            System.Windows.Media.Imaging.BitmapCreateOptions.None,
            System.Windows.Media.Imaging.BitmapCacheOption.OnLoad);

        if (decoder.Frames.Count == 0)
        {
            error = "decoder produced no frames";
            return false;
        }

        var frame = decoder.Frames[0];
        var converted = new System.Windows.Media.Imaging.FormatConvertedBitmap(
            frame, System.Windows.Media.PixelFormats.Bgra32, null, 0);

        var width = converted.PixelWidth;
        var height = converted.PixelHeight;
        var stride = width * 4;
        var pixels = new byte[stride * height];
        converted.CopyPixels(pixels, stride, 0);

        // A frame that is entirely one value is a blank image, not a camera feed.
        var distinct = new HashSet<byte>();
        for (var i = 0; i < pixels.Length && distinct.Count < 8; i += 997) distinct.Add(pixels[i]);

        if (distinct.Count < 2)
        {
            error = $"decoded {width}x{height} but the image is uniform";
            return false;
        }

        error = $"{width}x{height}, {distinct.Count}+ distinct samples";
        return true;
    }
    catch (Exception e)
    {
        error = e.Message;
        return false;
    }
}
