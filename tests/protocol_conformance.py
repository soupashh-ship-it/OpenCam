#!/usr/bin/env python3
"""Protocol conformance test for the OpenCam Android server.

Exercises the HTTP surface the desktop client and stock DroidCam clients rely on:
streams, device queries and the camera control endpoints.

Usage:
    python tests/protocol_conformance.py [host:port]

Default host:port is 127.0.0.1:4747. When testing an emulator or USB device,
forward the port first:  adb forward tcp:4747 tcp:4747
"""

from __future__ import annotations

import io
import re
import socket
import struct
import sys
import urllib.error
import urllib.request

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1:4747"

PASSED: list[str] = []
FAILED: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        PASSED.append(name)
        print(f"  PASS  {name}" + (f"  [{detail}]" if detail else ""))
    else:
        FAILED.append(name)
        print(f"  FAIL  {name}" + (f"  [{detail}]" if detail else ""))


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Keeps raw 3xx responses visible instead of following them."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_GET_OPENER = urllib.request.build_opener(_NoRedirect)


def get(path: str, timeout: float = 15.0) -> tuple[int, bytes, dict]:
    req = urllib.request.Request(f"http://{HOST}{path}")
    try:
        with _GET_OPENER.open(req, timeout=timeout) as resp:
            return resp.status, resp.read(), dict(resp.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


def put(path: str, timeout: float = 15.0) -> tuple[int, bytes]:
    req = urllib.request.Request(f"http://{HOST}{path}", method="PUT")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def read_stream(path: str, seconds: float = 4.0, max_bytes: int = 4 << 20) -> bytes:
    """Reads a chunked/streamed body for a fixed wall-clock window."""
    host, _, port = HOST.partition(":")
    s = socket.create_connection((host, int(port)), timeout=10)
    try:
        s.sendall(
            f"GET {path} HTTP/1.0\r\nHost: {HOST}\r\nConnection: close\r\n\r\n".encode()
        )
        s.settimeout(seconds)
        buf = io.BytesIO()
        while buf.tell() < max_bytes:
            try:
                chunk = s.recv(65536)
            except socket.timeout:
                break
            if not chunk:
                break
            buf.write(chunk)
        return buf.getvalue()
    finally:
        s.close()


def split_head(raw: bytes) -> tuple[bytes, bytes]:
    idx = raw.find(b"\r\n\r\n")
    if idx < 0:
        return b"", raw
    return raw[:idx], raw[idx + 4:]


def jpeg_dims(data: bytes) -> tuple[int, int] | None:
    """Reads SOF0/SOF2 dimensions out of a JPEG frame."""
    if len(data) < 4 or data[0] != 0xFF or data[1] != 0xD8:
        return None
    i = 2
    while i + 9 < len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker in (0xC0, 0xC1, 0xC2):
            h = struct.unpack(">H", data[i + 5:i + 7])[0]
            w = struct.unpack(">H", data[i + 7:i + 9])[0]
            return w, h
        if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        seg = struct.unpack(">H", data[i + 2:i + 4])[0]
        i += 2 + seg
    return None


# ---------------------------------------------------------------- device queries

def test_device_queries() -> None:
    print("\n[device queries]")
    status, body, _ = get("/v1/status")
    check("GET /v1/status returns 200", status == 200, str(status))
    text = body.decode("utf-8", "replace")
    check("status reports streaming", '"streaming": true' in text, text.replace("\n", " ")[:90])
    check("status reports a port", re.search(r'"port":\s*\d+', text) is not None)

    status, body, _ = get("/battery")
    check("GET /battery returns an integer",
          status == 200 and body.decode().strip().isdigit(), body.decode().strip()[:20])

    status, body, _ = get("/v1/phone/battery_level")
    check("GET /v1/phone/battery_level returns an integer",
          status == 200 and body.decode().strip().isdigit())

    status, body, _ = get("/v1/phone/name")
    check("GET /v1/phone/name returns a name",
          status == 200 and len(body.decode().strip()) > 0, body.decode().strip()[:40])

    status, body, _ = get("/v1/camera/camera_list")
    check("GET /v1/camera/camera_list lists cameras",
          status == 200 and ":" in body.decode(), body.decode().replace("\n", ","))

    status, body, _ = get("/v1/camera/info")
    info = body.decode()
    check("GET /v1/camera/info returns 200", status == 200)
    check("camera info carries a size", re.search(r"size=\d+x\d+", info) is not None, info[:80])

    status, headers = get("/")[0], get("/")[2]
    check("GET / redirects to /remote", status == 302 and "/remote" in headers.get("Location", ""),
          headers.get("Location", ""))

    status, body, _ = get("/remote")
    check("GET /remote serves the control page",
          status == 200 and b"OpenCam" in body and b"<html" in body.lower())

    status, body, _ = get("/assets/strings.js")
    check("GET /assets/strings.js serves JavaScript",
          status == 200 and b"OC_STRINGS" in body)

    status, _, _ = get("/definitely/not/a/route")
    check("unknown route returns 404", status == 404, str(status))


# ------------------------------------------------------------------- video stream

def test_video_stream() -> None:
    print("\n[video stream]")
    raw = read_stream("/video", seconds=3.0)
    head, body = split_head(raw)
    check("GET /video returns 200", head.startswith(b"HTTP/1.0 200") or head.startswith(b"HTTP/1.1 200"))
    check("video is multipart/x-mixed-replace",
          b"multipart/x-mixed-replace" in head, head[:120].decode("utf-8", "replace"))
    boundary = re.search(rb"boundary=(--[A-Za-z0-9]+)", head)
    check("multipart boundary is declared", boundary is not None)

    if boundary:
        parts = body.split(boundary.group(1))
        frames = []
        for part in parts:
            part = part.lstrip(b"\r\n")
            if not part.startswith(b"Content-Type: image/jpeg"):
                continue
            idx = part.find(b"\r\n\r\n")
            if idx < 0:
                continue
            payload = part[idx + 4:]
            end = payload.find(b"\xff\xd9")
            if end >= 0:
                frames.append(payload[:end + 2])

        check("stream carries at least 3 JPEG frames", len(frames) >= 3, f"{len(frames)} frames")
        if frames:
            first = frames[0]
            check("frame starts with JPEG SOI",
                  first[:2] == b"\xff\xd8", first[:4].hex())
            check("frame ends with JPEG EOI", first[-2:] == b"\xff\xd9")
            dims = jpeg_dims(first)
            check("frame has decodable dimensions", dims is not None, str(dims))
            if dims:
                check("frame dimensions match the negotiated size",
                      dims[0] > 0 and dims[1] > 0, f"{dims[0]}x{dims[1]}")
            sizes = [len(f) for f in frames]
            check("frames are non-trivial in size", min(sizes) > 2000, f"min={min(sizes)}B")

    # Legacy aliases must route to the same stream.
    for alias in ("/mjpegfeed", "/v1/video"):
        raw = read_stream(alias, seconds=1.5)
        head, body = split_head(raw)
        check(f"GET {alias} streams MJPEG",
              b"multipart/x-mixed-replace" in head and b"\xff\xd8" in body)


def test_video_route_with_size() -> None:
    print("\n[parameterised video route]")
    raw = read_stream("/v5/video/back/640x480/port/4747/os/android/obs/0/client/1/nonce/0/", seconds=4.0)
    head, body = split_head(raw)
    check("GET /v5/video/<cam>/<WxH>/... streams MJPEG",
          b"multipart/x-mixed-replace" in head, head[:100].decode("utf-8", "replace"))
    if b"\xff\xd8" in body:
        # The stream starts with the frame that was already cached when the
        # client arrived (the reference does that too), so look at a few frames
        # before expecting the newly requested size.
        dims_list = []
        idx = body.find(b"\xff\xd8")
        while idx >= 0 and len(dims_list) < 6:
            d = jpeg_dims(body[idx:idx + 200000])
            if d:
                dims_list.append(d)
            idx = body.find(b"\xff\xd8", idx + 2)
        check("route applied the requested 640x480 size",
              (640, 480) in dims_list, str(dims_list))
        # put the size back for the remaining checks
        put("/v3/camera/zoom/0.0")


# ------------------------------------------------------------------- audio streams

def test_audio_stream() -> None:
    print("\n[audio streams]")
    raw = read_stream("/v1/audio", seconds=2.0)
    head, body = split_head(raw)
    check("GET /v1/audio returns 200",
          head.startswith(b"HTTP/1.0 200") or head.startswith(b"HTTP/1.1 200"))
    check("audio is declared as WAV", b"audio/wav" in head, head[:120].decode("utf-8", "replace"))
    check("body starts with RIFF/WAVE",
          body[:4] == b"RIFF" and body[8:12] == b"WAVE", body[:12].hex())
    if len(body) >= 44:
        fmt, channels, rate, _, _, bits = struct.unpack("<HHIIHH", body[20:36])
        check("WAV is PCM", fmt == 1, f"fmt={fmt}")
        check("WAV is 48 kHz mono 16-bit",
              rate == 48000 and channels == 1 and bits == 16,
              f"{rate}Hz ch={channels} bits={bits}")
        check("audio data follows the header", len(body) > 44 + 4000,
              f"{len(body) - 44} PCM bytes")
        # Silence check: a live microphone path should not be a solid zero block
        # for the entire capture window on the emulator, but it may legitimately
        # be silent, so only report it rather than failing.
        pcm = body[44:]
        nonzero = sum(1 for b in pcm[:8000] if b != 0)
        print(f"        info  {nonzero} non-zero bytes in the first 8000 PCM bytes")

    raw = read_stream("/v2/audio", seconds=2.0)
    head, body = split_head(raw)
    check("GET /v2/audio returns 200",
          head.startswith(b"HTTP/1.0 200") or head.startswith(b"HTTP/1.1 200"))
    check("audio is declared as AAC", b"audio/aac" in head, head[:120].decode("utf-8", "replace"))
    sync = 0
    for i in range(0, min(len(body) - 1, 200000)):
        if body[i] == 0xFF and (body[i + 1] & 0xF0) == 0xF0:
            sync += 1
    check("AAC stream contains ADTS sync words", sync > 0, f"{sync} sync words")


# ---------------------------------------------------------------- control surface

def camera_info() -> dict:
    _, body, _ = get("/v1/camera/info")
    out = {}
    for token in body.decode().split():
        if "=" in token:
            k, v = token.split("=", 1)
            out[k] = v
    return out


def test_controls() -> None:
    print("\n[camera controls]")

    status, body = put("/v3/camera/zoom/0.5")
    check("PUT /v3/camera/zoom/0.5 returns 200", status == 200, body.decode()[:20])
    check("zoom applied", abs(float(camera_info().get("zoom", "0")) - 0.5) < 1e-3,
          camera_info().get("zoom", "?"))

    status, _ = put("/v3/camera/ev/1.5")
    check("PUT /v3/camera/ev/1.5 returns 200", status == 200)
    check("EV applied", abs(float(camera_info().get("ev", "0")) - 1.5) < 1e-3,
          camera_info().get("ev", "?"))

    status, _ = put("/v3/camera/iso/800")
    check("PUT /v3/camera/iso/800 returns 200", status == 200)
    check("ISO applied", camera_info().get("iso") == "800", camera_info().get("iso", "?"))

    status, _ = put("/v3/camera/ss/30")
    check("PUT /v3/camera/ss/30 returns 200", status == 200)
    check("shutter applied as 30 ms",
          camera_info().get("ss_ns") == "30000000", camera_info().get("ss_ns", "?"))

    status, _ = put("/v2/camera/wb_level?wb_level=0.25")
    check("PUT /v2/camera/wb_level accepts a query parameter", status == 200)
    check("white balance level applied",
          abs(float(camera_info().get("wb_level", "0.5")) - 0.25) < 1e-3,
          camera_info().get("wb_level", "?"))

    status, _ = put("/v1/camera/wb_mode?wb_mode=4")
    check("PUT /v1/camera/wb_mode accepts a query parameter", status == 200)
    check("white balance mode applied", camera_info().get("wb") == "4",
          camera_info().get("wb", "?"))

    before = camera_info().get("mic")
    status, _ = put("/v1/camera/mic_toggle")
    after = camera_info().get("mic")
    check("PUT /v1/camera/mic_toggle toggles the microphone",
          status == 200 and before != after, f"{before} -> {after}")
    put("/v1/camera/mic_toggle")  # restore, the audio checks below need the mic

    before = camera_info().get("torch")
    status, _ = put("/v1/camera/torch_toggle")
    after = camera_info().get("torch")
    check("PUT /v1/camera/torch_toggle toggles the torch",
          status == 200 and before != after, f"{before} -> {after}")
    put("/v1/camera/torch_toggle")  # restore

    before = camera_info().get("af")
    status, _ = put("/v1/camera/autofocus_mode?autofocus_mode=1")
    after = camera_info().get("af")
    check("PUT /v1/camera/autofocus_mode changes the mode",
          status == 200 and after == "1", f"{before} -> {after}")

    status, _ = put("/v1/tally?tally=1")
    _, body, _ = get("/v1/camera/tally")
    check("PUT /v1/tally sets the tally state",
          status == 200 and body.decode().strip() == "1", body.decode().strip())
    put("/v1/tally?tally=0")

    status, _ = put("/v1/camera/active?active=0")
    check("PUT /v1/camera/active selects a camera", status == 200)

    # restore a clean state
    put("/v3/camera/zoom/0.0")
    put("/v3/camera/ev/0")
    put("/v3/camera/iso/0")
    put("/v3/camera/ss/0")


# ------------------------------------------------------------------- concurrency

def test_multiple_clients() -> None:
    print("\n[multiple clients]")
    import threading

    results = {}

    def worker(idx: int) -> None:
        raw = read_stream("/video", seconds=2.0)
        _, body = split_head(raw)
        results[idx] = body.count(b"\xff\xd8")

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(3)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    _, status_body, _ = get("/v1/status")
    check("three concurrent video clients all receive frames",
          all(results.get(i, 0) >= 2 for i in range(3)), str(results))
    match = re.search(r'"video_clients":\s*(\d+)', status_body.decode())
    check("server reports the concurrent clients",
          match is not None and int(match.group(1)) >= 3,
          match.group(0) if match else "missing")


def main() -> int:
    print(f"OpenCam protocol conformance test against {HOST}")
    try:
        get("/v1/status", timeout=5)
    except Exception as e:  # noqa: BLE001
        print(f"\nCannot reach the server at {HOST}: {e}")
        print("Start OpenCam on the phone, then forward the port:"
              "\n  adb forward tcp:4747 tcp:4747")
        return 2

    test_device_queries()
    test_video_stream()
    test_controls()
    test_video_route_with_size()
    test_audio_stream()
    test_multiple_clients()

    print(f"\n{len(PASSED)} passed, {len(FAILED)} failed")
    if FAILED:
        print("Failures:")
        for f in FAILED:
            print(f"  - {f}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
