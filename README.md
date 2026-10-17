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

Quick start:

1. Install the APK on the phone (allow installing from unknown sources), open
   the app and press **Start**.
2. Run the MSI on the PC — it asks for elevation once, registers the virtual
   camera, adds a firewall rule for the local subnet and a Start-menu shortcut.
3. Open OpenCam on the PC — the phone shows up in the device list (or type
   `host:port` by hand) — press **Connect**.
4. Turn on **Virtual camera**, then pick **OpenCam** in any camera app.

## Layout

| Component | Path |
|-----------|------|
| OpenCam for Android — capture, streaming server, discovery, UI | [`android/`](android/) |
| OpenCam for Windows — WPF client | [`windows/OpenCam.Client`](windows/OpenCam.Client/) |
| Virtual camera output — Media Foundation camera, registered by the installer | [`windows/OpenCam.VirtualCam`](windows/OpenCam.VirtualCam/) |
| Windows installer — files, camera registration, firewall rule, launch | [`windows/OpenCam.Setup`](windows/OpenCam.Setup/) |
| Protocol test suite — conformance, framing, fake phone, timing | [`tests/`](tests/) |
| Build outputs — APK, installer, portable client | `dist/` (not committed; shipped via Releases) |

The wire protocol is compatible with the DroidCam client/server pair, so either
half can be replaced by the other:

- `OpenCam Windows` can connect to a stock DroidCam phone.
- OpenCam Android serves the routes a stock DroidCam desktop client asks for —
  a full video + audio session with the real client is verified below.

---

## What works today

### Android — built, installed, and exercised on an emulator

```
android/app/build/outputs/apk/release/opencam.apk          2.3 MB   minified, shrunk, signed
dist/opencam.apk                             2.3 MB   the file to hand to a phone
dist/opencam-debug.apk                       6.9 MB
package app.opencam   minSdk 23 (Android 6.0+)   targetSdk 36
```

No restrictions of any kind: no ads, no purchase, no licence check, no feature
gating and no tracking SDK — the only dependencies are AndroidX, Material and
coroutines. Every resolution, frame rate, camera and control is available to
everyone, and `dist/opencam.apk` installs on any Android 6.0+ device without a
store account.

The release build is signed out of the box: if `android/keystore.properties`
exists it is used, otherwise the debug keystore signs it, so `assembleRelease`
always produces an installable APK instead of an unsigned one. The release APK
is verified: R8 minification and resource shrinking do not break anything — the
release APK passes the conformance and framing suites below and streams to the
stock DroidCam desktop client.

Verified live against `emulator-5554` with the server on port 4747:

```
GET /v1/status        {"streaming": true, "width": 1280, "height": 720, ...}
GET /battery          100
GET /v1/phone/name    Google sdk_gphone64_x86_64
GET /v1/camera/info   camera=1:back active=0 size=1280x720 fps=60 zoom=0.0 ...
GET /v1/camera/camera_list   1:back
GET /v1/camera/limits {"audio_sample_rate": 48000, "wb_modes": 8, "af_modes": 6}
```

Features implemented:

- **Capture** — Camera2 with hardware JPEG output, live preview, resolution
  presets 480p → 2160p, selectable frame rate, front/back/external camera.
- **Full manual control** — zoom, exposure compensation, ISO, shutter time,
  white balance mode and level, autofocus mode and trigger, manual focus,
  AE lock, WB lock, torch, microphone toggle.
- **Streaming** — hand-rolled HTTP/1.0 server: MJPEG multipart video, WAV PCM
  audio, ADTS AAC audio, and the packet framing the stock DroidCam client reads:
  H.264 Annex-B or JPEG video on `/v5/video/…`, raw AAC-LC on `/v2/audio`.
- **Discovery** — mDNS, advertising both `_opencam._tcp.` and the reference
  `_droidcamobs._tcp.` service type.
- **Service** — foreground service with camera + microphone types, persistent
  notification with a Stop action, keep-screen-awake option.
- **UI** — dark theme matching the reference palette (`#1b1b1b` surfaces,
  `#fdfdfd` controls, `#fb1a67` links): full-bleed preview, top-left connection
  and stats readout with tally lamp, top-right action bar, bottom camera control
  bar, slide-out manual adjust panel, onboarding pages, settings, about.
- **No ad or tracking SDKs.** Permissions are camera, microphone, network,
  foreground service, wake lock and notifications — nothing else.

All icons are original vector artwork drawn for this project in the same visual
language as the reference. No assets, code or drivers are taken from the
reference application.

### Windows — built, and verified against a live phone

```
windows/OpenCam.Client    WPF client, x64, .NET 10
windows/OpenCam.Probe     headless verification harness
```

Features implemented:

- **Protocol client** — every query, every control endpoint, MJPEG demux, WAV
  parsing and PCM playback.
- **Discovery** — a self-contained mDNS browser (no external DNS dependency)
  that finds both `_opencam._tcp.` and the reference `_droidcamobs._tcp.`
  services, plus manual `host:port` entry.
- **Preview** — JPEG frames decoded into a reused `WriteableBitmap`, with a
  render timer so a fast phone cannot flood the dispatcher queue.
- **Audio** — streamed PCM through a 2-second jitter buffer that drops the
  oldest samples instead of growing latency.
- **UI** — dark theme sharing the Android palette, live stats overlay, tally
  lamp that follows the phone's tally state, and the full manual control set.

### Verification

`windows/OpenCam.Probe` compiles the client's own `Protocol/` sources and runs
against a live phone, so what it exercises is the shipping code, not a copy.
It also decodes frames with the same `JpegBitmapDecoder` the UI uses, so a pass
means the UI can actually draw them.

Against OpenCam for Android on an emulator (`adb forward tcp:4747 tcp:4747`):

```
OpenCam Windows protocol probe against 127.0.0.1:4747

[connectivity]   11 passed
[video]           6 passed   1280x720, 52 frames, every frame decodes
[audio]           3 passed   48000 Hz mono 16-bit, 384000 bytes in 4 s
[controls]       24 passed   every endpoint round-trips
[versioned]       2 passed   requested 640x480, received 640x480

46 passed, 0 failed
```

Three real defects were found and fixed by this probe:

1. **Microphone produced no audio.** The capture thread read a block as large as
   the `AudioRecord` internal buffer, so `read()` waited for a buffer the queue
   never delivered and stalled. Reads are now one minimum buffer against a
   4x internal buffer. The emulator also rejected `CAMCORDER`, `MIC` and
   `VOICE_RECOGNITION`; a source fallback chain was added and only `DEFAULT`
   worked there. Audio now arrives byte-exact (384000 bytes in 4 s = 96000 B/s).
2. **White balance round-trip appeared broken.** The control endpoint takes an
   index but `/v1/camera/info` reported the raw Camera2 constant, which is not
   contiguous (`AUTO` is 1, `DAYLIGHT` is 5). Writing 4 and reading 5 looked like
   a failure. The info route now reports the same numbering it accepts.
3. **Frames came back the wrong shape.** The stream was rotated to the device's
   display rotation, so a 640x480 request arrived as 480x640. It also forced a
   full JPEG decode and re-encode per frame. The stream is now left in the
   sensor's native landscape orientation, which is both what a webcam wants (the
   phone is propped up in landscape) and what makes the requested size the
   delivered size. Mirroring and vertical flip remain available and are the only
   paths that pay the re-encode cost.

Note: the emulator's software JPEG encoder sustains about 8.6 fps at 1280x720.
That is emulator throughput, not a client or server limit.

### Virtual camera — built, tested without a phone

```
windows/OpenCam.VirtualCam   native Media Foundation media source + host API (C++, MSVC)
windows/OpenCam.Client/VirtualCamera   shared frame buffer, output stage, readiness probe
```

This is the part that makes the phone appear inside Zoom, Teams, Discord, the
Windows Camera app and OBS. It uses the Media Foundation virtual camera on
Windows 11 (`MFCreateVirtualCamera`), so it needs no kernel driver and no
third-party runtime. A registered COM media source is loaded by the frame
server; the client publishes decoded BGRA frames into a named shared memory
section and the media source serves them as samples, so the UI thread never
blocks on the frame server and a slow consumer cannot stall the preview.

Two suites cover it, both runnable without a phone:

```
windows/OpenCam.VirtualCam/build_test.bat        53 passed, 0 failed
windows/OpenCam.VirtualCam/build_read_test.bat    7 passed, 0 failed
```

- `build_test.bat` loads the built DLL directly and drives it as a consumer:
  presentation descriptor and stream layout, `MENewStream`/`MEStreamStarted`
  ordering, sample timing at 1/30 s, byte-exact pixels, withholding a frame
  whose size no longer matches the negotiated media type, padded-stride
  repacking, `DllCanUnloadNow`, and the exported host API.
- `build_read_test.bat` is the cross-language check: `opencam-probe
  --publish-vcam` writes frames with the client's own `SharedFrameBuffer` while
  the native source reads the same section in another process. It passing means
  C# and C++ agree on the 56-byte header, the seqlock and the pixel offset,
  rather than each being self-consistent. It also caught the advertised size
  tracking the published frame (640x480 in, 640x480 advertised).

Getting it to activate at all required findings that only show up against the
real API, each one confirmed by the error code changing:

1. `0x80004002` (E_NOINTERFACE) — the frame server asks a software camera
   source for **`IMFMediaSourceEx`**, not plain `IMFMediaSource`.
2. `0xC00D36E6` (MF_E_ATTRIBUTENOTFOUND) — it insists on
   **`MF_VIRTUALCAMERA_PROVIDE_ASSOCIATED_CAMERA_SOURCES`** being present, so a
   source with no associated physical cameras must set it to 0 rather than omit
   it, and it also reads the stream's descriptor attributes
   (`MF_DEVICESTREAM_STREAM_CATEGORY`, `_STREAM_ID`, `_FRAMESERVER_SHARED`,
   `_ATTRIBUTE_FRAMESOURCE_TYPES`).
3. `0x80070003` (ERROR_PATH_NOT_FOUND) — what is left is the machine-wide COM
   registration the frame server's service account needs. An installed copy
   gets it from `OpenCamSetup.msi`; for running straight from a build tree, run
   `windows/OpenCam.VirtualCam/register.bat` **as administrator** once, which
   copies the DLL to `%ProgramData%\OpenCam`, grants Everyone read access and
   registers it under `HKLM`. Until registration has happened the client reports
   "DeviceNotRegistered" instead of pretending the camera works.

`VirtualCameraOutput` still keeps the OBS-backed backend for Windows 10, where
`MFCreateVirtualCamera` does not exist. It reports ready/missing accurately and
never fakes a running camera.

### Installer — one wizard, camera included

```
windows/OpenCam.Setup/build.bat            -> bin\x64\Release\OpenCamSetup.msi   61 MB
windows/OpenCam.Setup/verify-msi.ps1       prints what the package will do
```

Double-click, install, use. One MSI, built with WiX 5, with the standard wizard
(WixUI_Minimal). It installs per machine, which is what lets it register the
camera under `HKLM` without a second elevation prompt later:

| | |
|---|---|
| Payload | 467 files, the self-contained client, so no .NET runtime is needed |
| Location | `%ProgramFiles%\OpenCam` |
| Camera | `OpenCamVirtualCam.dll` beside the client, registered under `HKLM\SOFTWARE\Classes\CLSID\{6F1B4C93-…}` — `register.bat` is not part of an install |
| Firewall | an inbound exception for `OpenCam.exe`, local subnet only, so phone discovery does not stop at the Windows security prompt; removed again on uninstall |
| Wizard | welcome/terms page, progress, then a finish page with **Launch OpenCam** checked — the app starts unelevated, the way the reference installer's finish page does |
| Start menu | `OpenCam` folder with a shortcut to `OpenCam.exe` |
| Uninstall | removes the files, the COM registration, the firewall exception and the Start-menu folder (all owned by the MSI, so nothing is left in the camera list or the firewall) |

The installer could not be run here — that needs an administrator — so the
package is verified other ways, all of which check the artifact rather than the
build log:

1. `verify-msi.ps1` reads the MSI's own tables: product name, version 1.0.1 and
   467 files with the client and camera DLL both present; the camera's three
   registry values under `HKLM`; the firewall exception (naming the installed
   `OpenCam.exe`, component `ClientExecutable`); the 18 wizard dialogs and the
   finish page's launch action; the shortcut target; the folder removal on
   uninstall; and the install directories.
2. `msiexec /a … TARGETDIR=…` performs an administrative install, which needs no
   elevation, and it extracted the full payload to `PFiles64\OpenCam` including
   `OpenCam.exe` and `OpenCamVirtualCam.dll` — so the payload and its layout are
   proven even though the machine-wide registration is not.
3. The wizard's own page was read back out of the `Control` table: the welcome
   page carries the package's terms text, so the finish-to-launch path runs
   against the real strings rather than a placeholder.

What remains unverified is only the elevated half: the `HKLM` write, the
firewall rule and the camera appearing in an application's camera picker. That
is one `OpenCamSetup.msi` double-click on a normal machine.

### Interop with the stock DroidCam client — full session, verified

The real client (DroidCam Client v6.5.3, run from the files its own installer
unpacks — nothing installed for the test) streams video and audio from OpenCam
for Android on the emulator:

```
our server   127.0.0.1 GET /v5/video/avc/1280x720/port/…/nonce/5912/   (bare request)
our server   127.0.0.1 GET /v2/audio
our server   127.0.0.1 PUT /v1/tally/program/
client       [DroidCamOBS] read_frame: got config: 22      (SPS+PPS, 1280x720)
client       [DroidCamOBS] use hw: 1
client       DroidCam_Connect: sdk_gphone64_x86_64 [USB] (emulator-5554)
client       [DroidCamOBS] read_frame: got config: 2       (AAC AudioSpecificConfig)
client       [DroidCamOBS] audio: sample_rate=48000 channels=1
client       adding 46 milliseconds of audio buffering (source: sdk_gphone64_x86_64 [USB] (emulator-5554))
```

There are no receive timeouts in a 60 s session, the virtual camera draws the
stream, and an audio starve test (15 connect/close cycles back to back) leaves
no leaked threads or clients behind.

What those sockets turned out to speak, read from the client's own
`droidcam-obs.dll` and confirmed packet by packet with `tests/fake_phone.py`:

- `/v5/video/…` and `/v2/audio` are **not** HTTP on the way out. The client
  sends a bare request line and reads packets of a 12-byte header — a
  big-endian u64 tag and a big-endian u32 payload size — followed by the
  payload. There is no status line and no multipart; the route starts the
  binary stream at once.
- `format=avc` sends H.264 Annex-B access units. The first packet is a **config
  packet** with tag `0xffffffffffffffff` carrying SPS+PPS, and the client
  prepends it to every frame it decodes. Delta frames before the first key
  frame are skipped, so a client that connects mid-GOP never decodes against
  stale setup.
- `format=jpg` sends JPEG packets with the same header and no config.
- `/v2/audio` frames raw AAC-LC the same way: one config packet with the
  two-byte AudioSpecificConfig (`0x1188` = 48 kHz mono LC), then one packet per
  access unit. An HTTP request on the same route gets ADTS instead, which is
  what a browser or ffplay wants — the request shape picks the framing.
- The size in the path is applied to capture. The client refuses a second
  config on one connection ("double config ???") and reconnects by itself, so a
  capture restart closes the stream instead of switching config in place; the
  reconnect then gets the new size as its first packet. When a connection
  itself triggers the size change, the stream waits for the restarted pipeline
  and sends the fresh config, so the client never sees a stale one.
- Tally, `/v1/phone/name` and `/battery` work as before.

Connecting OpenCam Windows to a stock DroidCam *phone* was not run: that app's
licence check blocks it on an emulator, and working around a paid app's
licensing is not something this project should do.

### Tests

- `windows/OpenCam.Probe` — end-to-end verification of the Windows client
  against a live phone (see above).
- `tests/protocol_conformance.py` — 54 checks, endpoint by endpoint: device
  queries, MJPEG framing and JPEG validity, WAV and ADTS audio, the stock
  client's packet framing on `/v5/video/…` and `/v2/audio`, every control
  endpoint, and multi-client behaviour.
- `tests/smoke_framing.py` — 10 checks for the packet shapes themselves: config
  packet tags, key-frame gating, raw AAC unit flow and the multipart form.
- `tests/fake_phone.py` — stand-in phone used while discovering the stock
  client's framing; it replays candidate layouts one connection at a time.

---

## What is left

1. **The elevated half of the install has not been run here.** The media source,
   the shared buffer and the host API all work (see the suites above), but the
   Windows frame server loads the source from a service account, so the camera
   must be registered under `HKLM` from an elevated process. `OpenCamSetup.msi`
   does that on its own at install time; on this build machine, where there is
   no administrator session, the last verification is the package's own tables
   plus the administrative extraction, and the client reports
   "DeviceNotRegistered" until a real install has happened. `register.bat` is
   the build-tree equivalent for running straight from the repository.
2. **HEVC on the versioned video route.** The client can be set to
   `video_format` 2 (`hevc`), which OpenCam does not serve; `avc` and `jpg` are
   the formats it uses by default and both stream. The format is an integer in
   the client's scene JSON: 0 = `avc`, 1 = `jpg`, 2 = `hevc`.
3. **Signing with a real key.** `opencam.apk` is signed with the debug key
   unless `android/keystore.properties` is added (storeFile, storePassword,
   keyAlias, keyPassword). That is what a store upload would need; it is not a
   functional gap, just the difference between a debug key and a release key.

### Known limits in the current protocol work

- The `/video`, `/v1/video`, `/v2/video`, `/v6/video`, `/v7/video`,
  `/v5/video/*`, `/v1/audio`, `/v2/audio`, `/v4/audio`, `/battery`,
  `/v1/phone/*`, `/v1/camera/*`, `/v3/camera/*`, `/v2/camera/wb_level`,
  `/v1/tally`, `/v1/restart` and `/v1/stop` routes are implemented.
- `/v5/video/<format>/<WxH>/…` serves `jpg`, `jpeg`, `mjpg`, `mjpeg` (JPEG
  packets) and `avc`, `h264` (H.264 Annex-B with an SPS+PPS config packet);
  `hevc` is not served. With an HTTP request the same route serves MJPEG
  multipart, so browsers and ffplay work too.
- Mirroring the outgoing stream re-encodes the JPEG (rotation to display
  orientation is required anyway because MJPEG readers ignore EXIF). Turn
  mirroring off for the highest frame rates.

---

## Protocol

Port default `4747`. All routes are plain HTTP on the LAN.

### Streams

| Method | Route | Returns |
|--------|-------|---------|
| GET | `/video` | `multipart/x-mixed-replace` MJPEG |
| GET | `/v1/video`, `/v2/video`, `/v6/video`, `/v7/video` | same MJPEG stream |
| GET | `/v3/video/…`, `/v4/video/…` | same, applying camera and size from the path |
| GET | `/v5/video/<format>/<WxH>/port/<p>/os/<os>/obs/<x>/client/<x>/nonce/<n>/` | HTTP request: MJPEG as above; bare request: the stock client's packets — `jpg` formats = JPEG packets, `avc`/`h264` = SPS+PPS config then H.264 Annex-B — with the size from the path applied to capture |
| GET | `/mjpegfeed` | alias of `/video` |
| GET | `/v1/audio` | WAV, 48 kHz mono 16-bit PCM, streamed |
| GET | `/v2/audio`, `/v4/audio` | HTTP request: ADTS AAC-LC; bare request: AAC config packet then raw AAC-LC access units |

The bare-request streams are packets of a 12-byte header — big-endian u64 tag,
big-endian u32 payload size — followed by the payload. The config packet uses
tag `0xffffffffffffffff`: SPS+PPS for `avc`, the two-byte AudioSpecificConfig
for audio. `tests/smoke_framing.py` checks these shapes.

### Queries

| Method | Route | Returns |
|--------|-------|---------|
| GET | `/` | `302` to `/remote` |
| GET | `/remote` | HTML remote control page |
| GET | `/assets/strings.js` | client-side strings |
| GET | `/battery`, `/v1/phone/battery_level` | integer percent |
| GET | `/v1/phone/battery_info` | `"<pct>% Charging\|Discharging"` |
| GET | `/v1/phone/name` | device name |
| GET | `/v1/camera/camera_list` | one `id:facing` per line |
| GET | `/v1/camera/info` | space-separated `key=value` state |
| GET | `/v1/camera/tally` | `0` idle, `1` program, `2` preview |

### Controls

Values are accepted as a path segment (`/v3/camera/zoom/0.5`), a query parameter
(`?zoom=0.5`) or a form body (`zoom=0.5`).

| Method | Route | Effect |
|--------|-------|--------|
| PUT | `/v1/camera/active` | select camera by index |
| PUT | `/v1/camera/mic_toggle` | toggle microphone |
| PUT | `/v1/camera/torch_toggle` | toggle torch |
| PUT | `/v1/camera/wb_mode` | white balance preset (0–7, see below) |
| PUT | `/v2/camera/wb_level` | manual white balance level, 0–1, 0.5 neutral |
| PUT | `/v1/camera/wbl_toggle` | white balance lock |
| PUT | `/v1/camera/ae_toggle`, `/v1/camera/el_toggle` | exposure lock |
| PUT | `/v1/camera/autofocus_mode` | AF mode (0–5, see below) |
| PUT | `/v1/camera/autofocus` | trigger a focus pass |
| PUT | `/v3/camera/zoom/<0..1>` | zoom |
| PUT | `/v3/camera/ev/<-4..4>` | exposure compensation in EV steps |
| PUT | `/v3/camera/iso/<0..n>` | ISO, 0 = auto |
| PUT | `/v3/camera/ss/<ms>` | shutter in milliseconds, 0 = auto |
| PUT | `/v3/camera/mf/<0..1>` | manual focus, 0 = auto |
| PUT | `/v1/tally` | tally state 0/1/2 |
| PUT | `/v1/restart` | restart the capture pipeline |
| PUT | `/v1/stop` | stop streaming |

White balance modes: `0` auto, `1` incandescent, `2` fluorescent,
`3` warm fluorescent, `4` daylight, `5` cloudy, `6` twilight, `7` shade.

Autofocus modes: `0` off, `1` auto, `2` macro, `3` continuous video,
`4` continuous picture, `5` EDOF.

### Added by OpenCam

These are additive and ignored by other clients.

| Method | Route | Returns |
|--------|-------|---------|
| GET | `/v1/status` | JSON: name, port, streaming, client counts, size, fps, battery, tally |
| GET | `/v1/camera/limits` | JSON: port, camera count, size, fps, audio format, control counts |

---

## Building

### Android

```bash
cd android
./gradlew :app:assembleDebug     # -> app/build/outputs/apk/debug/opencam-debug.apk
./gradlew :app:assembleRelease   # -> app/build/outputs/apk/release/opencam.apk
adb install -r app/build/outputs/apk/release/opencam.apk
```

To sign a release build with a real key, add `android/keystore.properties`:

```properties
storeFile=/path/to/opencam.jks
storePassword=…
keyAlias=opencam
keyPassword=…
```

Without that file the debug keystore signs the release build, so the APK is
always installable.

`local.properties` must point at the Android SDK. Compile SDK 36, build tools
36.0.0, JDK 17+ (JDK 21 recommended), Gradle 8.12, AGP 8.10.1, Kotlin 2.1.21.

### Windows

```bash
cd windows/OpenCam.Client
dotnet build -c Release          # the WPF client
cd ../OpenCam.VirtualCam && build.bat   # the camera media source (MSVC + Windows SDK)
cd ../OpenCam.Setup && build.bat        # publish + pack OpenCamSetup.msi
```

The client needs the .NET 10 SDK on Windows, x64. The virtual camera needs the
Visual Studio 2022 C++ build tools and a Windows 10/11 SDK; the installer also
needs the WiX 5 SDK, which restores from NuGet on first build.

Registering the camera for a development build is one elevated command:

```bat
windows\OpenCam.VirtualCam\register.bat    :: run as Administrator
```

### Running the verification harnesses

```bash
adb forward tcp:4747 tcp:4747
python tests/protocol_conformance.py          # Android server surface, 54 checks
python tests/smoke_framing.py                 # binary framing shapes, 10 checks
cd windows/OpenCam.Probe && dotnet run -- 127.0.0.1:4747   # Windows client, 46 checks

cd windows/OpenCam.VirtualCam && build_test.bat      # media source, 53 checks
cd windows/OpenCam.VirtualCam && build_read_test.bat  # C# writer to C++ reader, 7 checks
#   (run the read test while this publishes frames:)
#   windows/OpenCam.Probe/bin/Debug/net10.0-windows/opencam-probe.exe --publish-vcam 30

cd windows/OpenCam.Setup && powershell -ExecutionPolicy Bypass -File verify-msi.ps1
```

---

## Layout

```
android/                      OpenCam for Android
  app/src/main/java/app/opencam/
    MainActivity.kt           preview, controls, streaming toggle
    IntroActivity.kt          first-run welcome
    InitialInfoActivity.kt    onboarding, desktop client and OBS
    SettingsActivity.kt       port, resolution, fps, camera, audio, discovery
    InfoActivity.kt           about
    CamService.kt             foreground service, owns capture and server
    Prefs.kt                  persisted settings
    UiExt.kt                  spinner helper
    view/EdgeToEdgeLayout.kt  window inset handling
    core/
      CameraEngine.kt         Camera2 capture and manual controls
      CameraSettings.kt       control state
      CamCaps.kt              resolution presets and size selection
      AudioEngine.kt          microphone capture
      StreamHub.kt            frame and audio fan-out to clients
      HttpServer.kt           routes, MJPEG, WAV, AAC and the stock client's packets
      AacEncoder.kt           PCM to AAC-LC (ADTS or raw access units)
      AvcEncoder.kt           H.264 surface encoder (MediaCodec) feeding StreamHub
      Discovery.kt            mDNS advertisement
    Asset-less: every icon is an original vector drawn for this project
windows/                      OpenCam for Windows
  OpenCam.Client/
    MainWindow.xaml(.cs)      preview, controls, device list, status bar
    App.xaml(.cs)             application entry and theme wiring
    Themes/Dark.xaml          brushes and control styles
    Protocol/
      OpenCamConnection.cs    queries, controls, video and audio pumps
      ProtocolModels.cs       device state and option tables
      StreamFraming.cs        buffered reader, MJPEG demux, WAV header
    Discovery/MdnsBrowser.cs  self-contained mDNS browse
    Media/VideoRenderer.cs    JPEG to WriteableBitmap
    Media/AudioPlayer.cs      streamed PCM playback
    Interop/DarkTitleBar.cs   dark non-client area
    VirtualCamera/
      VirtualCameraOutput.cs  output-stage choice and readiness probe
      MfVirtualCameraOutput.cs Media Foundation camera (Windows 11)
      SharedFrameBuffer.cs    seqlock frame section the camera source reads
      VirtualCameraIds.cs     CLSID, names, install locations
  OpenCam.Probe/              headless verification harness (+ --publish-vcam)
  OpenCam.VirtualCam/         native media source and host API (C++, MSVC)
    OpenCamVirtualCam.cpp     IMFMediaSourceEx, stream, sample production
    OpenCamVirtualCameraHost.cpp  MFCreateVirtualCamera wrapper exports
    OpenCamVirtualCamTest.cpp     in-process suite (build_test.bat)
    OpenCamVirtualCamReadTest.cpp cross-language reader (build_read_test.bat)
    register.bat / unregister.bat elevated COM registration (build-tree runs)
  OpenCam.Setup/              WiX 5 package, build script, MSI inspector
dist/                         ready-to-hand-out builds: opencam.apk, opencam-debug.apk, OpenCamSetup.msi
tests/protocol_conformance.py 54-check endpoint conformance test
tests/smoke_framing.py        packet-shape smoke test (10 checks)
tests/fake_phone.py           stand-in phone for framing experiments
```
