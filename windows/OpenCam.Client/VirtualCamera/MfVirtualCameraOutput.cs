using System.IO;
using System.Runtime.InteropServices;

namespace OpenCam.Client.VirtualCamera;

/// <summary>
/// The Media Foundation output stage.
///
/// Windows 11 can host a virtual camera from a media source: the frame server
/// loads a registered COM media source and exposes it to every application that
/// enumerates cameras. OpenCam registers such a source (see
/// <c>windows\OpenCam.VirtualCam</c>), and this class creates and drives one
/// instance of the camera device for the session.
///
/// Frames reach the media source through the named shared memory section in
/// <see cref="SharedFrameBuffer"/>, so the client never hands a buffer to the
/// frame server directly and the camera keeps working while the UI is busy.
/// </summary>
public sealed class MfVirtualCameraOutput : IVirtualCameraOutput
{
    /// <summary>Windows 11 is the first build with the virtual camera API.</summary>
    private const int MinimumBuild = 22000;

    private readonly SharedFrameBuffer _frames = new();
    private readonly object _gate = new();

    private nint _library;
    private nint _camera;
    private bool _started;
    private int _fps = 30;

    public string Name => "OpenCam virtual camera";

    public VirtualCameraStatus Probe()
    {
        if (!OperatingSystem.IsWindows())
        {
            return new VirtualCameraStatus(
                VirtualCameraAvailability.UnsupportedOs,
                "The virtual camera is Windows only.");
        }

        var build = Environment.OSVersion.Version.Build;
        if (build < MinimumBuild)
        {
            return new VirtualCameraStatus(
                VirtualCameraAvailability.UnsupportedOs,
                $"Windows 11 (build {MinimumBuild}) or newer is required; this is build {build}.");
        }

        if (FindMediaSource() is not { } path)
        {
            // Name every location that was searched: the installer puts the media
            // source under Program Files and register.bat puts it under
            // ProgramData, so pointing at one of them would be misleading.
            var searched = string.Join(", ", VirtualCameraIds.CandidatePaths);
            return new VirtualCameraStatus(
                VirtualCameraAvailability.DeviceNotRegistered,
                $"The OpenCam camera is not installed. Run the OpenCam installer, "
                + $"OpenCamSetup.msi. Looked for "
                + $"{VirtualCameraIds.DllName} in: {searched}");
        }

        var registered = VirtualCameraIds.RegisteredPath;
        if (registered is null)
        {
            return new VirtualCameraStatus(
                VirtualCameraAvailability.DeviceNotRegistered,
                "The OpenCam camera is copied but not registered. Run the OpenCam "
                + "installer, OpenCamSetup.msi, or register.bat as administrator "
                + "from a source checkout.",
                path);
        }

        if (!File.Exists(registered))
        {
            return new VirtualCameraStatus(
                VirtualCameraAvailability.DeviceNotRegistered,
                $"The registered camera driver is missing from {registered}. "
                + "Re-run the OpenCam installer, or register.bat as administrator "
                + "from a source checkout.",
                path);
        }

        return new VirtualCameraStatus(
            VirtualCameraAvailability.Available,
            "Ready. Start the virtual camera, then choose \"OpenCam\" in any app.",
            path);
    }

    public Task<bool> StartAsync(int width, int height, int fps, CancellationToken ct)
    {
        var status = Probe();
        if (!status.CanPublish)
        {
            throw new NotSupportedException(status.Detail);
        }

        lock (_gate)
        {
            if (_started) return Task.FromResult(true);

            _library = LoadLibrary(status.RuntimePath!);
            _frames.Open();
            _fps = fps > 0 ? fps : 30;

            var start = GetExport<StartVirtualCameraDelegate>(_library, "OpenCamVirtualCameraStart");
            var handle = nint.Zero;
            var hr = start(VirtualCameraIds.FriendlyName, out handle);
            if (hr < 0 || handle == nint.Zero)
            {
                _frames.Dispose();
                throw new InvalidOperationException(
                    $"Windows could not create the OpenCam virtual camera (0x{hr:X8}). "
                    + "Re-run the OpenCam installer, then restart OpenCam.");
            }

            _camera = handle;
            _started = true;
        }

        return Task.FromResult(true);
    }

    /// <summary>
    /// Publishes one decoded frame. <paramref name="pixels"/> is BGRA32 with
    /// <paramref name="stride"/> bytes per row, which is the layout the preview
    /// renderer already produces, so no second decode is needed.
    /// </summary>
    public void PushFrame(byte[] pixels, int width, int height, int stride)
    {
        if (!_started) return;
        _frames.Publish(pixels, width, height, stride, _fps);
    }

    public Task StopAsync()
    {
        lock (_gate)
        {
            if (!_started) return Task.CompletedTask;

            var release = GetExport<ReleaseVirtualCameraDelegate>(
                _library, "OpenCamVirtualCameraRelease");
            release(_camera);

            _camera = nint.Zero;
            _started = false;
            _frames.Dispose();

            if (_library != nint.Zero)
            {
                NativeLibrary.Free(_library);
                _library = nint.Zero;
            }
        }

        return Task.CompletedTask;
    }

    public async ValueTask DisposeAsync() => await StopAsync().ConfigureAwait(false);

    // ------------------------------------------------------------------ loading

    /// <summary>
    /// Finds the media source: the installed copy first, then a copy beside the
    /// application so a development build works without registering anything.
    /// </summary>
    private static string? FindMediaSource()
    {
        if (VirtualCameraIds.InstalledPath is { } installed) return installed;

        var local = Path.Combine(AppContext.BaseDirectory, VirtualCameraIds.DllName);
        if (File.Exists(local)) return local;

        var build = Path.Combine(AppContext.BaseDirectory, "build", VirtualCameraIds.DllName);
        return File.Exists(build) ? build : null;
    }

    private static nint LoadLibrary(string path)
    {
        if (NativeLibrary.TryLoad(path, out var handle)) return handle;
        throw new InvalidOperationException(
            $"Could not load {path}. Re-run the OpenCam installer.");
    }

    private static T GetExport<T>(nint library, string name) where T : Delegate
    {
        if (!NativeLibrary.TryGetExport(library, name, out var address))
        {
            throw new InvalidOperationException(
                $"{VirtualCameraIds.DllName} does not export {name}. "
                + "Rebuild the camera with windows/OpenCam.VirtualCam/build.bat.");
        }

        return Marshal.GetDelegateForFunctionPointer<T>(address);
    }

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate int StartVirtualCameraDelegate(
        [MarshalAs(UnmanagedType.LPWStr)] string friendlyName, out nint handle);

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate void ReleaseVirtualCameraDelegate(nint handle);
}
