"""Live smoke test for the wire protocols OpenCam speaks.

Talks to a running OpenCam server (or the fake phone) and validates:
  * bare `/v5/video/jpg` -> 12-byte framed JPEG packets,
  * bare `/v5/video/avc` -> SPS+PPS config packet then annex-B access units,
  * bare `/v2/audio`     -> AudioSpecificConfig packet then raw AAC,
  * HTTP `/v5/video/back/...` -> multipart MJPEG (the OpenCam client dialect).

Usage: python smoke_framing.py [host] [port] [--live]   (--live = tall ceilings
because the stream is paced by the real camera; default is the same anyway)
"""

import socket
import struct
import sys
import time

HOST = "127.0.0.1"
PORT = 4747
for i, a in enumerate(sys.argv[1:]):
    if a.isdigit():
        PORT = int(a)
    elif "." in a or a == "localhost":
        HOST = a

CONFIG_TAG = 0xFFFFFFFFFFFFFFFF
failures = []


def check(name, cond, detail=""):
    tag = "ok  " if cond else "FAIL"
    print(f"[{tag}] {name} {detail}")
    if not cond:
        failures.append(name)


def connect():
    s = socket.create_connection((HOST, PORT), timeout=10)
    s.settimeout(10)
    return s


def recv_exact(s, n):
    buf = b""
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise ConnectionError(f"closed after {len(buf)}/{n} bytes")
        buf += chunk
    return buf


def read_packet(s):
    head = recv_exact(s, 12)
    tag, size = struct.unpack(">QI", head)
    payload = recv_exact(s, size) if size else b""
    return tag, size, payload


def test_jpg():
    s = connect()
    s.sendall(b"GET /v5/video/jpg/1280x720/port/0/os/win10.0.26300/obs//client/700/nonce/1/")
    tags = []
    jpeg = None
    for _ in range(5):
        tag, size, payload = read_packet(s)
        tags.append(tag)
        if jpeg is None and payload[:2] == b"\xff\xd8":
            jpeg = size
    s.close()
    check("jpg: tags increase", tags == sorted(tags) and len(set(tags)) == 5, str(tags))
    check("jpg: JPEG payload", jpeg is not None, f"size={jpeg}")


def test_avc():
    s = connect()
    s.sendall(b"GET /v5/video/avc/1280x720/port/0/os/win10.0.26300/obs//client/700/nonce/2/")
    tag, size, payload = read_packet(s)
    check("avc: config packet", tag == CONFIG_TAG and payload[:4] == b"\x00\x00\x00\x01",
          f"tag={tag:#x} size={size} head={payload[:8].hex()}")
    has_sps = b"\x67" in payload or b"\x27" in payload
    check("avc: SPS in config", has_sps)
    # first access unit(s): wait for a payload that is an IDR slice (NAL type 5)
    idr = False
    frames = 0
    deadline = time.time() + 15
    while time.time() < deadline and not idr:
        tag, size, payload = read_packet(s)
        frames += 1
        if len(payload) > 10:
            # annex-B: find first start code, read nal type
            for off in range(0, min(32, len(payload) - 5)):
                if payload[off:off + 3] == b"\x00\x00\x01":
                    nal = payload[off + 3] & 0x1F
                    if nal == 5:
                        idr = True
                    elif nal == 1:
                        pass
                    break
    s.close()
    check("avc: key frame arrives", idr, f"after {frames} frames")


def test_audio():
    s = connect()
    s.sendall(b"GET /v2/audio")
    tag, size, payload = read_packet(s)
    check("aac: config packet 0x1188", tag == CONFIG_TAG and payload == b"\x11\x88",
          f"tag={tag:#x} size={size} payload={payload.hex()}")
    sizes = []
    for _ in range(8):
        tag, size, payload = read_packet(s)
        sizes.append(size)
        check_ok = 20 <= size <= 800
        if not check_ok:
            check("aac: frame size sane", False, f"size={size}")
            break
    else:
        check("aac: raw AAC units flowing", True, f"sizes={sizes[:5]}...")
    s.close()


def test_multipart():
    s = connect()
    s.sendall(
        b"GET /v5/video/back/640x480/port/4747/os/win10.0.26300/obs/0/client/opencam/nonce/3/ HTTP/1.1\r\n"
        b"Host: 127.0.0.1\r\n\r\n"
    )
    head = b""
    deadline = time.time() + 10
    while time.time() < deadline:
        chunk = s.recv(4096)
        if not chunk:
            break
        head += chunk
        if b"\r\n\r\n" in head and b"----BoundaryString" in head.split(b"\r\n\r\n", 1)[-1]:
            break
    s.close()
    check("multipart: HTTP 200", head.startswith(b"HTTP/1.0 200 OK"), head[:60])
    check("multipart: boundary", b"multipart/x-mixed-replace; boundary=--BoundaryString" in head)
    body = head.split(b"\r\n\r\n", 1)[-1] if b"\r\n\r\n" in head else b""
    check("multipart: first frame", b"----BoundaryString" in body and b"Content-Type: image/jpeg" in body,
          f"body={body[:80]!r}")


if __name__ == "__main__":
    print(f"== smoke framing against {HOST}:{PORT} ==")
    for fn in (test_jpg, test_avc, test_audio, test_multipart):
        try:
            fn()
        except Exception as e:
            check(fn.__name__ + " crashed", False, repr(e))
    print("== result:", "PASS" if not failures else f"FAILURES: {failures}")
    sys.exit(1 if failures else 0)
