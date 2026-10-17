#!/usr/bin/env python3
"""A stand-in phone for protocol experiments with the stock DroidCam client.

The stock client (DroidCam Client (OBS) 7.2.1 / droidcam-obs.dll) sends a bare
request line with no CRLF and no HTTP version on the video and audio routes:

    GET /v5/video/jpg/1280x720/port/4750/os/win10.0.26300/obs//client/700/nonce/5912/
    GET /v2/audio

and then reads a raw binary stream. The reply is a sequence of packets:

    tag   u64 big-endian   packet id; 0xFFFFFFFFFFFFFFFF marks a config packet
    size  u32 big-endian   payload length; 0 < size <= 0x1000000

with the payload bytes right after. It gets a config packet only where the
format needs one (AAC); JPEG frames are sent as plain packets.

The client gives up on a read after about five seconds, so the reply has to
start immediately: this server answers the bare request as soon as the line
arrives instead of waiting for the CRLFCRLF that never comes.

Usage:
    python tests/fake_phone.py --port 4750 --mode cycle
    python tests/fake_phone.py --port 4750 --mode raw12
    python tests/fake_phone.py --port 4750 --mode avc
"""

import argparse
import io
import json
import os
import socket
import struct
import sys
import threading
import time

from PIL import Image, ImageDraw

FRAME_W = 640
FRAME_H = 480
FPS = 30.0

CONFIG_TAG = 0xFFFFFFFFFFFFFFFF

HERE = os.path.dirname(os.path.abspath(__file__))
AVC_FILE = os.path.join(HERE, "assets", "avc.h264")
AAC_FILE = os.path.join(HERE, "assets", "aac.adts")


def log(*parts):
    print("[%s]" % time.strftime("%H:%M:%S"), *parts, flush=True)


def pack_packet(tag, payload):
    return struct.pack(">QI", tag, len(payload)) + payload


def make_frame(sequence):
    """A gradient that shifts with the frame number, plus the number drawn on it."""
    image = Image.new("RGB", (FRAME_W, FRAME_H))
    pixels = image.load()
    step = (sequence * 7) % 256
    for y in range(0, FRAME_H, 4):
        for x in range(0, FRAME_W, 4):
            colour = (x // 4 + step) % 256, (y // 6) % 256, (x + y + step * 3) % 256
            for dy in range(4):
                for dx in range(4):
                    pixels[x + dx, y + dy] = colour
    ImageDraw.Draw(image).text((12, 12), f"OpenCam fake phone {sequence}", fill=(255, 255, 255))
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=70)
    return buffer.getvalue()


def http_head(content_type=None, content_length=None):
    head = b"HTTP/1.0 200 OK\r\nServer: FakePhone\r\n"
    if content_type:
        head += b"Content-Type: " + content_type + b"\r\n"
    if content_length is not None:
        head += b"Content-Length: " + str(content_length).encode() + b"\r\n"
    return head + b"Cache-Control: no-store\r\nConnection: close\r\n\r\n"


def multipart_frame(jpeg):
    return (
        b"--BoundaryString\r\nContent-Type: image/jpeg\r\n"
        b"Content-Length: " + str(len(jpeg)).encode() + b"\r\n\r\n" + jpeg + b"\r\n"
    )


def candidates(width, height):
    """(name, response head, frame function) candidates for the cycle mode.

    The frame function takes (counter, jpeg) and returns the bytes to send for
    that one frame. The binary framing candidates come first because that is
    what the client's own reader expects.
    """
    return [
        ("raw12-count", b"", lambda n, j: pack_packet(n + 1, j)),
        ("raw12-ms", b"", lambda n, j: pack_packet(int(time.time() * 1000), j)),
        ("raw12-zero", b"", lambda n, j: pack_packet(0, j)),
        ("no-http-rawjpeg", b"", lambda n, j: j),
        ("http-multipart",
         http_head(b"multipart/x-mixed-replace; boundary=--BoundaryString"),
         lambda n, j: multipart_frame(j)),
    ]


def status_body(port):
    return json.dumps(
        {
            "name": "OpenCam Fake",
            "port": port,
            "streaming": True,
            "video_clients": 1,
            "audio_clients": 0,
            "fps": 30,
            "width": FRAME_W,
            "height": FRAME_H,
            "battery": 100,
            "tally": 0,
        }
    ).encode()


def read_request(conn):
    """Read one request; answers the bare client request without CRLFCRLF."""
    conn.settimeout(3.0)
    data = b""
    while len(data) < 65536:
        if b"\r\n\r\n" in data:
            break
        if data.startswith((b"GET /v5/", b"GET /v2/audio")) and b"\n" not in data:
            # Bare client request: no HTTP version, no line ending. A tiny
            # grace read covers a request that arrives split in two segments.
            conn.settimeout(0.05)
            try:
                chunk = conn.recv(4096)
            except socket.timeout:
                break
            if not chunk:
                break
            data += chunk
            continue
        try:
            chunk = conn.recv(4096)
        except socket.timeout:
            break
        if not chunk:
            break
        data += chunk
    if not data:
        return None
    head = data.split(b"\r\n\r\n", 1)[0].decode("latin1", "replace")
    lines = head.split("\r\n")
    if not lines or " " not in lines[0]:
        return None
    parts = lines[0].split(" ")
    return parts[0], parts[1]


def parse_size(target):
    for token in target.split("/"):
        if "x" in token:
            a, _, b = token.partition("x")
            if a.isdigit() and b.isdigit():
                return int(a), int(b)
    return FRAME_W, FRAME_H


def split_h264_units(data):
    """Split an annex-B stream into (config, [access units]).

    The config is every SPS/PPS pair; the access units keep only slice NALs,
    split at AUDs (type 9) where ffmpeg emits them.
    """
    starts = []
    i = 0
    while True:
        j = data.find(b"\x00\x00\x01", i)
        if j < 0:
            break
        starts.append(j)
        i = j + 3
    units = []
    for k, s in enumerate(starts):
        e = starts[k + 1] if k + 1 < len(starts) else len(data)
        units.append(((data[s + 3] & 0x1F), data[s:e]))
    config = b"".join(b for t, b in units if t in (7, 8))
    frames = []
    current = bytearray()
    for t, b in units:
        if t == 9 and current:
            frames.append(bytes(current))
            current = bytearray()
        if t in (7, 8):
            continue
        current += b
    if current:
        frames.append(bytes(current))
    return config, frames


def split_adts(data):
    """Split an ADTS AAC stream into (config, [raw aac frames])."""
    frames = []
    i = 0
    profile = sample_rate = channels = None
    while i + 7 <= len(data):
        if data[i] != 0xFF or (data[i + 1] & 0xF0) != 0xF0:
            break
        protection_absent = data[i + 1] & 1
        profile = (data[i + 2] >> 6) & 0x3
        sf_index = (data[i + 2] >> 2) & 0xF
        channels = ((data[i + 2] & 1) << 2) | ((data[i + 3] >> 6) & 0x3)
        frame_len = ((data[i + 3] & 0x3) << 11) | (data[i + 4] << 3) | ((data[i + 5] >> 5) & 0x7)
        header = 7 if protection_absent else 9
        frames.append(data[i + header:i + frame_len])
        i += frame_len
        sample_rate = sf_index
    # AudioSpecificConfig: audioObjectType(5) samplingFrequencyIndex(4)
    # channelConfiguration(4) + 3 bits zero.
    if sample_rate is None:
        return b"", []
    asc = bytes([
        ((2 & 0x1F) << 3) | ((sample_rate >> 1) & 0x7),
        ((sample_rate & 1) << 7) | ((channels & 0xF) << 3),
    ])
    return asc, frames


def serve_video(conn, target, args, state):
    width, height = parse_size(target)
    period = 1.0 / FPS

    token = ""
    parts = target.split("/")
    if len(parts) > 3:
        token = parts[3].lower()

    if args.mode == "cycle":
        options = candidates(width, height)
        with state["lock"]:
            index = state["index"] % len(options)
            state["index"] += 1
        name, head, frame_bytes = options[index]
    else:
        name = args.mode
        head, frame_bytes = None, None

    if head is None:
        if name == "raw12":
            head, frame_bytes = b"", lambda n, j: pack_packet(n + 1, j)
        elif name == "raw12-ms":
            head, frame_bytes = b"", lambda n, j: pack_packet(int(time.time() * 1000), j)
        elif name == "multipart":
            head, frame_bytes = (http_head(b"multipart/x-mixed-replace; boundary=--BoundaryString"),
                                 lambda n, j: multipart_frame(j))
        elif name == "jpegs":
            head, frame_bytes = http_head(), lambda n, j: j
        elif name == "avc" or name == "avc-inband":
            head, frame_bytes = None, None
        else:
            raise SystemExit(f"unknown mode {name}")

    log(f"  video: candidate={name} token={token!r} size={width}x{height}")

    try:
        if name == "avc" or name == "avc-inband":
            if not os.path.exists(AVC_FILE):
                log(f"  {AVC_FILE} missing; run tests/make_assets.sh")
                conn.close()
                return
            with open(AVC_FILE, "rb") as f:
                config, units = split_h264_units(f.read())
            log(f"  avc: config={len(config)} bytes, {len(units)} access units")
            if name == "avc":
                conn.sendall(pack_packet(CONFIG_TAG, config))
                log(f"  avc config packet sent: size={len(config)}")
            deadline = time.time() + args.seconds
            sent = 0
            while time.time() < deadline:
                for unit in units:
                    payload = unit
                    if name == "avc-inband" and sent == 0:
                        payload = config + unit
                    packet = pack_packet(1 + sent, payload)
                    if sent == 0:
                        log(f"  avc first packet header: {packet[:12].hex()} size={len(payload)}")
                    conn.sendall(packet)
                    sent += 1
                    time.sleep(period)
            log(f"  [{name}] SURVIVED {sent} frames - this framing is accepted")
            return

        if name == "raw12" or name.startswith("raw12") or name == "cycle":
            pass
        head_bytes = head or b""
        conn.sendall(head_bytes)

        deadline = time.time() + args.seconds
        frames = 0
        while time.time() < deadline:
            bytes_to_send = frame_bytes(frames, make_frame(frames))
            if frames == 0 and head_bytes == b"":
                log(f"  [{name}] first packet header: {bytes_to_send[:12].hex()}")
            conn.sendall(bytes_to_send)
            frames += 1
            time.sleep(period)

        log(f"  [{name}] SURVIVED {frames} frames - this framing is accepted")
    except OSError as e:
        log(f"  [{name}] client closed after some frames ({e})")


def handle(conn, addr, args, state):
    try:
        request = read_request(conn)
        if not request:
            log(f"{addr[0]}:{addr[1]} (no request)")
            return
        method, target = request
        log(f"{addr[0]}:{addr[1]} {method} {target}")

        path = target.split("?", 1)[0]
        if path == "/v1/status":
            body = status_body(args.port)
            conn.sendall(http_head(b"application/json", len(body)) + body)
        elif path == "/v1/phone/name":
            body = b"OpenCam Fake"
            conn.sendall(http_head(b"text/plain", len(body)) + body)
        elif path.startswith("/battery"):
            conn.sendall(http_head(b"text/plain", 3) + b"100")
        elif path.startswith("/v1/tally"):
            conn.sendall(http_head(b"text/plain", 0))
        elif path.startswith("/v1/camera/"):
            conn.sendall(http_head(b"text/plain", 0))
        elif "/video" in path:
            serve_video(conn, path, args, state)
        elif path == "/v2/audio":
            if not os.path.exists(AAC_FILE):
                log(f"  {AAC_FILE} missing; run tests/make_assets.sh")
                return
            with open(AAC_FILE, "rb") as f:
                asc, frames = split_adts(f.read())
            log(f"  audio: asc={asc.hex()} frames={len(frames)}")
            conn.sendall(pack_packet(CONFIG_TAG, asc))
            deadline = time.time() + args.seconds
            sent = 0
            next_t = time.time()
            while time.time() < deadline:
                for frame in frames:
                    conn.sendall(pack_packet(sent + 1, frame))
                    sent += 1
                    next_t += 1024 / 48000.0
                    dt = next_t - time.time()
                    if dt > 0:
                        time.sleep(dt)
                    if time.time() >= deadline:
                        break
            log(f"  audio SURVIVED {sent} frames - this framing is accepted")
        else:
            log("  unhandled route, answering 404")
            conn.sendall(b"HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n")
    except OSError as e:
        log("connection error:", e)
    finally:
        try:
            conn.close()
        except OSError:
            pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=4750)
    parser.add_argument(
        "--mode",
        choices=["multipart", "jpegs", "cycle", "raw12", "raw12-ms", "avc", "avc-inband"],
        default="cycle")
    parser.add_argument("--seconds", type=float, default=20.0)
    args = parser.parse_args()

    state = {"index": 0, "lock": threading.Lock()}

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", args.port))
    server.listen(8)
    log(f"fake phone on 127.0.0.1:{args.port}, mode={args.mode}, frame {FRAME_W}x{FRAME_H}")

    while True:
        conn, addr = server.accept()
        log("accepted", f"{addr[0]}:{addr[1]}")
        threading.Thread(target=handle, args=(conn, addr, args, state), daemon=True).start()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
