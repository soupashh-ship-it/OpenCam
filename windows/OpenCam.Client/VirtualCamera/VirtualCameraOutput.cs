using System.IO;
using Microsoft.Win32;

namespace OpenCam.Client.VirtualCamera;

public enum VirtualCameraAvailability
{
    /// <summary>The output stage is present and can publish frames.</summary>
    Available,

    /// <summary>The backing runtime (OBS) is not installed on this machine.</summary>
    RuntimeMissing,

    /// <summary>The camera driver exists but the system does not know about it yet.</summary>
    DeviceNotRegistered,

    /// <summary>The OpenCam capture source that feeds the runtime is not built yet.</summary>
    SourcePluginMissing,

    /// <summary>The OS cannot host this backend.</summary>
    UnsupportedOs,

    /// <summary>Probing failed for another reason.</summary>
    Failed,
}

public sealed record VirtualCameraStatus(
    VirtualCameraAvailability Availability,
    string Detail,
    string? RuntimePath = null)
{
    public bool CanPublish => Availability == VirtualCameraAvailability.Available;
}

/// <summary>
/// Chooses the output stage for this machine.
/// </summary>
public static class VirtualCameraBackend
{
    /// <summary>Windows 11 is the first build that can host a virtual camera itself.</summary>
    private const int MinimumBuild = 22000;

    /// <summary>
    /// The Media Foundation stage wherever the OS supports it, because it needs no
    /// third-party runtime; the OBS-backed stage remains for older builds.
    /// </summary>
    public static IVirtualCameraOutput Create()
    {
        if (OperatingSystem.IsWindows() && Environment.OSVersion.Version.Build >= MinimumBuild)
        {
            return new MfVirtualCameraOutput();
        }

        return new ObsVirtualCameraOutput();
    }
}

/// <summary>
/// A way to make the phone appear as a camera to other applications.
/// </summary>
public interface IVirtualCameraOutput : IAsyncDisposable
{
    string Name { get; }

    VirtualCameraStatus Probe();

    Task<bool> StartAsync(int width, int height, int fps, CancellationToken ct);

    /// <summary>
    /// Publishes one frame as BGRA32, <paramref name="stride"/> bytes per row.
    /// A backend that is not running ignores the call.
    /// </summary>
    void PushFrame(byte[] pixels, int width, int height, int stride);

    Task StopAsync();
}

/// <summary>
/// The OBS-backed output stage, the same shape the reference client uses: a
/// bundled OBS runtime plus a capture source, publishing through OBS's virtual
/// camera device.
///
/// <see cref="MfVirtualCameraOutput"/> is preferred on Windows 11, which can host
/// a virtual camera directly; this backend is what a Windows 10 machine would need.
///
/// What this class can do today is detect and report readiness. It deliberately
/// does not pretend to publish frames: feeding OBS requires the OpenCam capture
/// source plugin (a native OBS source module) to be built and installed, and
/// without it OBS has nothing to send to the virtual camera device. Detect that
/// case and say so rather than silently doing nothing.
/// </summary>
public sealed class ObsVirtualCameraOutput : IVirtualCameraOutput
{
    private const string ObsRegistryKey = @"SOFTWARE\OBS Studio";

    /// <summary>CLSID of the DirectShow filter OBS registers as "OBS Virtual Camera".</summary>
    private const string ObsVirtualCamClsid = "{A3FCE0F5-3493-419F-958A-ABA1250EC20B}";

    public string Name => "OBS Virtual Camera";

    private string? _runtimePath;

    public VirtualCameraStatus Probe()
    {
        if (!OperatingSystem.IsWindows())
        {
            return new VirtualCameraStatus(
                VirtualCameraAvailability.UnsupportedOs,
                "The OBS-backed output stage is Windows only.");
        }

        _runtimePath = FindObsExecutable();
        if (_runtimePath is null)
        {
            return new VirtualCameraStatus(
                VirtualCameraAvailability.RuntimeMissing,
                "OBS was not found. Install OBS Studio, or place a portable OBS build in "
                + "an 'obs' folder next to OpenCam.");
        }

        var deviceRegistered = IsVirtualCameraRegistered();
        if (!deviceRegistered)
        {
            return new VirtualCameraStatus(
                VirtualCameraAvailability.DeviceNotRegistered,
                "OBS is installed but its virtual camera device is not registered. "
                + "Open OBS once and use 'Start Virtual Camera' to register it.",
                _runtimePath);
        }

        // The runtime and the device are both present. The remaining piece is the
        // OpenCam capture source that hands phone frames to OBS.
        if (!IsCaptureSourceInstalled(_runtimePath))
        {
            return new VirtualCameraStatus(
                VirtualCameraAvailability.SourcePluginMissing,
                "OBS and its virtual camera are ready, but the OpenCam capture source "
                + "plugin is not installed in this OBS build yet.",
                _runtimePath);
        }

        return new VirtualCameraStatus(
            VirtualCameraAvailability.Available,
            "Ready. Phone frames will be published to the OBS Virtual Camera device.",
            _runtimePath);
    }

    public Task<bool> StartAsync(int width, int height, int fps, CancellationToken ct)
    {
        var status = Probe();
        if (!status.CanPublish)
        {
            throw new NotSupportedException(status.Detail);
        }

        // Publishing is the capture source plugin's job; nothing to start here.
        return Task.FromResult(true);
    }

    public void PushFrame(byte[] pixels, int width, int height, int stride)
    {
        // Frames reach OBS through the capture source plugin, which connects to the
        // phone itself. Nothing to forward from the desktop process.
    }


    public Task StopAsync() => Task.CompletedTask;

    public ValueTask DisposeAsync() => ValueTask.CompletedTask;

    // ------------------------------------------------------------------ probing

    private static string? FindObsExecutable()
    {
        // 1. An OBS build shipped next to OpenCam, as the packaged installer does.
        var local = Path.Combine(AppContext.BaseDirectory, "obs", "bin", "64bit", "obs64.exe");
        if (File.Exists(local)) return local;

        // 2. A normal OBS Studio installation.
        foreach (var root in new[]
                 {
                     Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                     Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),
                 })
        {
            if (string.IsNullOrEmpty(root)) continue;
            var candidate = Path.Combine(root, "obs-studio", "bin", "64bit", "obs64.exe");
            if (File.Exists(candidate)) return candidate;
        }

        // 3. Registry record of the install location.
        foreach (var hive in new[] { RegistryHive.LocalMachine, RegistryHive.CurrentUser })
        {
            try
            {
                using var baseKey = RegistryKey.OpenBaseKey(hive, RegistryView.Registry64);
                using var key = baseKey.OpenSubKey(ObsRegistryKey);
                if (key?.GetValue("InstallPath") is string path && path.Length > 0)
                {
                    var candidate = Path.Combine(path, "bin", "64bit", "obs64.exe");
                    if (File.Exists(candidate)) return candidate;
                }
            }
            catch
            {
                // registry access denied is not fatal
            }
        }

        return null;
    }

    private static bool IsVirtualCameraRegistered()
    {
        try
        {
            using var key = Registry.ClassesRoot.OpenSubKey($"CLSID\\{ObsVirtualCamClsid}");
            return key is not null;
        }
        catch
        {
            return false;
        }
    }

    private static bool IsCaptureSourceInstalled(string obsExePath)
    {
        try
        {
            var pluginDir = Path.GetFullPath(
                Path.Combine(Path.GetDirectoryName(obsExePath)!, "..", "..", "obs-plugins", "64bit"));
            return File.Exists(Path.Combine(pluginDir, "opencam-obs.dll"));
        }
        catch
        {
            return false;
        }
    }
}
