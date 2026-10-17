using System.Buffers;
using System.IO;
using System.Text;

namespace OpenCam.Client.Protocol;

/// <summary>
/// Buffered reader over a network stream with the two operations the streaming
/// demuxers need: read a CRLF-terminated header line, and read an exact byte
/// count without going through a one-byte-at-a-time path.
/// </summary>
public sealed class FramedStreamReader
{
    private readonly Stream _stream;
    private byte[] _buffer;
    private int _start;
    private int _end;

    public FramedStreamReader(Stream stream, int capacity = 256 * 1024)
    {
        _stream = stream;
        _buffer = ArrayPool<byte>.Shared.Rent(capacity);
    }

    private async ValueTask<bool> FillAsync(CancellationToken ct)
    {
        if (_start > 0)
        {
            var live = _end - _start;
            if (live > 0) Buffer.BlockCopy(_buffer, _start, _buffer, 0, live);
            _start = 0;
            _end = live;
        }

        if (_end >= _buffer.Length)
        {
            var bigger = ArrayPool<byte>.Shared.Rent(_buffer.Length * 2);
            Buffer.BlockCopy(_buffer, 0, bigger, 0, _end);
            ArrayPool<byte>.Shared.Return(_buffer);
            _buffer = bigger;
        }

        var read = await _stream.ReadAsync(_buffer.AsMemory(_end), ct).ConfigureAwait(false);
        if (read <= 0) return false;
        _end += read;
        return true;
    }

    /// <summary>Reads one CRLF or LF terminated line without its terminator.</summary>
    public async Task<string?> ReadLineAsync(CancellationToken ct)
    {
        while (true)
        {
            for (var i = _start; i < _end; i++)
            {
                if (_buffer[i] != (byte)'\n') continue;
                var length = i - _start;
                var text = Encoding.ASCII.GetString(_buffer, _start, length).TrimEnd('\r');
                _start = i + 1;
                return text;
            }

            if (!await FillAsync(ct).ConfigureAwait(false))
            {
                if (_end <= _start) return null;
                var text = Encoding.ASCII.GetString(_buffer, _start, _end - _start).TrimEnd('\r');
                _start = _end;
                return text;
            }
        }
    }

    /// <summary>Reads exactly <paramref name="count"/> bytes, or null on a short stream.</summary>
    public async Task<byte[]?> ReadExactAsync(int count, CancellationToken ct)
    {
        if (count < 0) return null;
        var result = new byte[count];
        var written = 0;
        while (written < count)
        {
            if (_start >= _end && !await FillAsync(ct).ConfigureAwait(false)) return null;
            var available = Math.Min(_end - _start, count - written);
            Buffer.BlockCopy(_buffer, _start, result, written, available);
            _start += available;
            written += available;
        }
        return result;
    }

    /// <summary>Copies up to <paramref name="count"/> bytes into <paramref name="dest"/>.</summary>
    public async Task<int> ReadSomeAsync(byte[] dest, int offset, int count, CancellationToken ct)
    {
        if (_start >= _end && !await FillAsync(ct).ConfigureAwait(false)) return 0;
        var available = Math.Min(_end - _start, count);
        Buffer.BlockCopy(_buffer, _start, dest, offset, available);
        _start += available;
        return available;
    }

    /// <summary>Number of buffered bytes still available without touching the network.</summary>
    public int Buffered => _end - _start;

    public void Release() => ArrayPool<byte>.Shared.Return(_buffer);
}

/// <summary>A single decoded JPEG frame plus the dimensions the server reported.</summary>
public sealed record VideoFrame(byte[] Jpeg, int Width, int Height, DateTimeOffset ReceivedAt);

/// <summary>
/// Demultiplexes the `multipart/x-mixed-replace` MJPEG stream the phone serves.
/// Prefers the declared `Content-Length`; falls back to scanning for the JPEG
/// end-of-image marker when a client variant omits it.
/// </summary>
public static class MjpegDemuxer
{
    private const int MaxFrameBytes = 16 * 1024 * 1024;

    /// <summary>
    /// Reads one part. Returns null when the stream ends.
    /// </summary>
    public static async Task<byte[]?> ReadFrameAsync(FramedStreamReader reader, CancellationToken ct)
    {
        // Walk header blocks until we find one that carries an image.
        while (true)
        {
            var inHeaders = true;
            var sawContentTypeHeader = false;
            var isImage = false;
            var contentLength = -1;

            while (inHeaders)
            {
                var line = await reader.ReadLineAsync(ct).ConfigureAwait(false);
                if (line is null) return null;

                if (line.Length == 0)
                {
                    inHeaders = false;
                    break;
                }

                // The first line of a part is the boundary itself.
                if (line.StartsWith("--", StringComparison.Ordinal) && !line.Contains(':'))
                {
                    continue;
                }

                var colon = line.IndexOf(':');
                if (colon <= 0) continue;
                var name = line[..colon].Trim();
                var value = line[(colon + 1)..].Trim();

                if (name.Equals("Content-Type", StringComparison.OrdinalIgnoreCase))
                {
                    sawContentTypeHeader = true;
                    isImage = value.Contains("image/jpeg", StringComparison.OrdinalIgnoreCase)
                              || value.Contains("image/jpg", StringComparison.OrdinalIgnoreCase);
                }
                else if (name.Equals("Content-Length", StringComparison.OrdinalIgnoreCase))
                {
                    int.TryParse(value, out contentLength);
                }
            }

            if (!sawContentTypeHeader)
            {
                // Either a stray boundary block or the end of the stream; keep reading.
                continue;
            }

            if (!isImage)
            {
                // Non-image part (some servers send text parts); skip its body.
                if (contentLength is > 0 and < MaxFrameBytes)
                {
                    await reader.ReadExactAsync(contentLength, ct).ConfigureAwait(false);
                }
                continue;
            }

            if (contentLength is > 0 and < MaxFrameBytes)
            {
                var payload = await reader.ReadExactAsync(contentLength, ct).ConfigureAwait(false);
                if (payload is null) return null;
                // The part is followed by CRLF before the next boundary.
                return payload;
            }

            return await ScanForJpegAsync(reader, ct).ConfigureAwait(false);
        }
    }

    /// <summary>
    /// Fallback for servers that stream JPEGs without a Content-Length: collect
    /// bytes between the SOI and EOI markers.
    /// </summary>
    private static async Task<byte[]?> ScanForJpegAsync(FramedStreamReader reader, CancellationToken ct)
    {
        using var accumulated = new MemoryStream(256 * 1024);
        var chunk = new byte[64 * 1024];
        var started = false;
        var previous = -1;

        while (true)
        {
            var read = await reader.ReadSomeAsync(chunk, 0, chunk.Length, ct).ConfigureAwait(false);
            if (read == 0) return accumulated.Length > 0 ? accumulated.ToArray() : null;

            for (var i = 0; i < read; i++)
            {
                var b = chunk[i];

                if (!started)
                {
                    if (previous == 0xFF && b == 0xD8)
                    {
                        accumulated.WriteByte(0xFF);
                        accumulated.WriteByte(0xD8);
                        started = true;
                    }
                    previous = b;
                    continue;
                }

                accumulated.WriteByte(b);
                if (previous == 0xFF && b == 0xD9)
                {
                    return accumulated.ToArray();
                }
                previous = b;
            }
        }
    }
}

/// <summary>Parsed WAV format information from the `/v1/audio` stream header.</summary>
public sealed record WavFormat(int SampleRate, int Channels, int BitsPerSample, long DataOffset)
{
    public int BytesPerSample => BitsPerSample / 8;

    public int BlockAlign => Channels * BytesPerSample;

    public TimeSpan DurationOf(int byteCount) =>
        TimeSpan.FromSeconds(byteCount / (double)(SampleRate * BlockAlign));

    /// <summary>
    /// Reads a RIFF/WAVE header, walking the chunk list until `data` is found.
    /// Returns null when the stream is not a WAV header.
    /// </summary>
    public static async Task<WavFormat?> ReadAsync(FramedStreamReader reader, CancellationToken ct)
    {
        var fixedHeader = await reader.ReadExactAsync(12, ct).ConfigureAwait(false);
        if (fixedHeader is null) return null;
        if (Encoding.ASCII.GetString(fixedHeader, 0, 4) != "RIFF") return null;
        if (Encoding.ASCII.GetString(fixedHeader, 8, 4) != "WAVE") return null;

        var sampleRate = 0;
        var channels = 0;
        var bits = 0;
        var offset = 12L;

        // Chunk list: [4-byte id][4-byte size][payload]
        for (var guard = 0; guard < 64; guard++)
        {
            var chunkHeader = await reader.ReadExactAsync(8, ct).ConfigureAwait(false);
            if (chunkHeader is null) return null;
            var id = Encoding.ASCII.GetString(chunkHeader, 0, 4);
            var size = BitConverter.ToUInt32(chunkHeader, 4);
            offset += 8;

            if (id == "fmt ")
            {
                var fmt = await reader.ReadExactAsync((int)Math.Min(size, 64), ct).ConfigureAwait(false);
                if (fmt is null) return null;
                if (fmt.Length >= 16)
                {
                    channels = BitConverter.ToUInt16(fmt, 2);
                    sampleRate = BitConverter.ToInt32(fmt, 4);
                    bits = BitConverter.ToUInt16(fmt, 14);
                }
                var padding = (int)size - fmt.Length;
                if (padding > 0) await reader.ReadExactAsync(padding, ct).ConfigureAwait(false);
                offset += size;
            }
            else if (id == "data")
            {
                if (sampleRate == 0 || channels == 0 || bits == 0) return null;
                return new WavFormat(sampleRate, channels, bits, offset);
            }
            else
            {
                // Unknown chunk: skip it (chunks are word aligned).
                var skip = (int)size + (int)(size % 2);
                var skipped = await reader.ReadExactAsync(skip, ct).ConfigureAwait(false);
                if (skipped is null) return null;
                offset += skip;
            }
        }

        return null;
    }
}
