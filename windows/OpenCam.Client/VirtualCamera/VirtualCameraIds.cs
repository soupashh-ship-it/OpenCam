using System.IO;
using Microsoft.Win32;

namespace OpenCam.Client.VirtualCamera;

/// <summary>
/// Identifiers shared with the native media source. The values here must stay in
/// step with <c>OpenCamVirtualCam.h</c> and <c>OpenCamVirtualCameraHost.cpp</c>.
/// </summary>
public static class VirtualCameraIds
{
    /// <summary>CLSID of the OpenCam media source; also the registered COM class.</summary>
    public const string Clsid = "{6F1B4C93-7A2E-4D71-9C58-2E4A0B7D5F31}";

    /// <summary>The media source binary, which also hosts the virtual camera.</summary>
    public const string DllName = "OpenCamVirtualCam.dll";

    /// <summary>The name applications show in their camera pickers.</summary>
    public const string FriendlyName = "OpenCam";

    /// <summary>
    /// Fallback location used by <c>register.bat</c> when the camera is registered
    /// from a development build. The Windows frame server services load this DLL
    /// and run as Local Service and Local System, so it cannot live under a user
    /// profile.
    /// </summary>
    public static string InstallDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "OpenCam");

    /// <summary>
    /// Every place the media source may be installed, in the order the client
    /// trusts them: the installer's Program Files copy first, then the development
    /// registration, then a copy beside the executable.
    /// </summary>
    public static IEnumerable<string> CandidatePaths
    {
        get
        {
            var programFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
            if (!string.IsNullOrEmpty(programFiles))
            {
                yield return Path.Combine(programFiles, "OpenCam", DllName);
            }

            yield return Path.Combine(InstallDirectory, DllName);
            yield return Path.Combine(AppContext.BaseDirectory, DllName);
        }
    }

    /// <summary>Installed location of the media source, or null when not installed.</summary>
    public static string? InstalledPath
    {
        get
        {
            foreach (var candidate in CandidatePaths)
            {
                if (File.Exists(candidate)) return candidate;
            }

            return null;
        }
    }

    /// <summary>The COM path the frame server will use, taken from the registry.</summary>
    public static string? RegisteredPath
    {
        get
        {
            if (!OperatingSystem.IsWindows()) return null;
            using var key = RegistryKey
                .OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64)
                .OpenSubKey($@"SOFTWARE\Classes\CLSID\{Clsid}\InprocServer32");
            return key?.GetValue(null) as string;
        }
    }
}
