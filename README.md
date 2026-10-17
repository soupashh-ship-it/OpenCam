# OpenCam

Turn an Android phone into a webcam for a Windows PC — a phone app, a desktop
client, and a virtual camera that shows up in every app that can use a camera:
OBS, Discord, Zoom, Teams, the Windows Camera app, browsers.

**Free, with nothing held back:** no ads, no premium tier, no account, no
licence checks. Every resolution and every control is available to everyone.

## Download

Grab the latest build from [**Releases**](https://github.com/soupashh-ship-it/OpenCam/releases/latest):

| Platform | Asset | Requires |
|----------|-------|----------|
| Android | `OpenCam-<version>-android.apk` | Android 6.0+ (API 23); sideload install |
| Windows | `OpenCam-<version>-windows-x64.msi` | Windows 10/11 x64; the virtual camera needs Windows 11 (build 22000+) |

## Quick start

1. Install the APK on the phone (allow installing from unknown sources), open
   the app and press **Start**.
2. Run the MSI on the PC — it asks for elevation once, registers the virtual
   camera, adds a firewall rule for the local subnet and a Start-menu shortcut.
3. Open OpenCam on the PC — the phone shows up in the device list (or type
   `host:port` by hand) — press **Connect**.
4. Turn on **Virtual camera**, then pick **OpenCam** in any camera app.

Both devices must be on the same local network.

## Features

**Android app**

- Camera2 capture with hardware JPEG — resolution presets 480p–2160p,
  selectable frame rate, front/back/external camera
- Full manual control: zoom, exposure, ISO, shutter, white balance, focus,
  AE/WB lock, torch, microphone toggle
- Streaming: MJPEG multipart, WAV PCM, ADTS AAC — plus the packed H.264/JPEG
  framing DroidCam-family clients read
- mDNS discovery, foreground service with a Stop action, dark UI

**Windows client**

- WPF app: automatic LAN discovery, manual `host:port` connect, live preview,
  connection statistics, virtual-camera toggle

**Virtual camera** (Windows 11, build 22000+)

- A Media Foundation camera registered machine-wide — select **OpenCam** in any
  app that uses a camera. No kernel driver, no third-party runtime.

**Installer** (MSI)

- Registers the virtual camera, adds a firewall exception for the local subnet,
  creates a Start-menu shortcut and offers to launch the app when the wizard
  finishes. One elevation prompt; uninstall removes everything.

## Compatibility

The wire protocol is compatible with the DroidCam client/server pair: the
Windows client can connect to a stock DroidCam phone, and the Android app serves
the routes a stock DroidCam desktop client asks for — verified end to end
against the real client. Details in [docs/ENGINEERING.md](docs/ENGINEERING.md).

## Build from source

Android — JDK 17+ (21 recommended), Android SDK 36:

```bash
cd android
./gradlew :app:assembleRelease    # -> app/build/outputs/apk/release/opencam.apk
```

The release build is signed out of the box: `android/keystore.properties` is
used when present, the debug keystore otherwise, so the APK is always
installable.

Windows — .NET 10 SDK; the camera needs the VS 2022 C++ build tools and a
Windows SDK; the installer needs WiX 5 (restores from NuGet):

```bat
cd windows\OpenCam.Client && dotnet build -c Release     :: the WPF client
cd windows\OpenCam.VirtualCam && build.bat               :: the camera media source
cd windows\OpenCam.Setup && build.bat                    :: publish + pack the MSI
```

Test suites, toolchain versions and the full build tree:
[docs/ENGINEERING.md](docs/ENGINEERING.md).

## Repository layout

| Component | Path |
|-----------|------|
| Android app — capture, streaming server, discovery, UI | [`android/`](android/) |
| Windows client — WPF app | [`windows/OpenCam.Client`](windows/OpenCam.Client/) |
| Virtual camera — Media Foundation media source (C++) | [`windows/OpenCam.VirtualCam`](windows/OpenCam.VirtualCam/) |
| Installer — WiX 5 package | [`windows/OpenCam.Setup`](windows/OpenCam.Setup/) |
| Verification probe — headless client harness | [`windows/OpenCam.Probe`](windows/OpenCam.Probe/) |
| Test suite — protocol conformance and framing checks | [`tests/`](tests/) |

## Licence

MIT — see [LICENSE](LICENSE).
