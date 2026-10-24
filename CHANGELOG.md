# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.2] — 2026-10-24

### Fixed

- **DroidCam 7.2.1 AVC playback** — the H.264 config packet carried only the
  SPS on encoders that report `csd-0` and `csd-1` separately, so the client
  logged `error decoding video` and showed no picture. The server now
  concatenates every codec-specific-data buffer, producing an SPS+PPS config.
- **Framed-stream timestamps** — AVC, JPEG and AAC packets now carry the
  microsecond presentation time the stock client expects instead of a 1, 2, 3
  packet counter.

## [1.0.1] — 2026-10-17 · first public release

### Added

- **OpenCam for Android** (`app.opencam`) — Camera2 capture (hardware JPEG) and a
  hand-rolled HTTP server: MJPEG multipart video, WAV PCM and ADTS AAC audio, plus
  the packed H.264/JPEG framing DroidCam-family clients read. Resolution presets
  from 480p to 2160p, selectable frame rate, front/back/external camera, full
  manual controls (zoom, exposure, ISO, shutter, white balance, focus, torch),
  mDNS discovery, foreground service, dark UI.
- **OpenCam for Windows** — WPF client: automatic LAN discovery, manual
  `host:port` connect, live preview, connection/statistics readout and a
  virtual-camera toggle.
- **Virtual camera** — a Media Foundation camera (Windows 11, build 22000+)
  registered machine-wide, so any camera app can use the phone as a webcam.
- **Installer** — a single MSI that lays down the client, registers the camera
  under `HKLM`, adds a firewall exception for the local subnet, creates a
  Start-menu shortcut, optionally launches the app when the wizard finishes, and
  removes everything on uninstall.

### Notes

- The wire protocol is compatible with the DroidCam client/server pair — either
  half can be replaced by the other.
- The Android APK is signed with a development key: it installs by sideloading
  and is not ready for store submission as-is.
- HEVC (`video_format 2`) is not served; H.264 and JPEG are.

[1.0.1]: https://github.com/soupashh-ship-it/OpenCam/releases/tag/v1.0.1
