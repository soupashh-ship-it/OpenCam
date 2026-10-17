using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace OpenCam.Client.Media;

/// <summary>
/// Turns the JPEG frames arriving from the phone into something WPF can draw.
///
/// The target <see cref="WriteableBitmap"/> is allocated once and reused for
/// every frame of the same size, so steady-state rendering does not churn
/// image sources on the UI thread.
/// </summary>
public sealed class VideoRenderer
{
    private WriteableBitmap? _target;
    private byte[]? _scratch;
    private int _width;
    private int _height;

    /// <summary>The bitmap to bind to an <c>Image</c>. Reused between frames.</summary>
    public WriteableBitmap? Bitmap => _target;

    public int Width => _width;

    public int Height => _height;

    /// <summary>Frames rendered so far, used for the on-screen rate display.</summary>
    public long FrameCount { get; private set; }

    /// <summary>Bytes per row of <see cref="Pixels"/>.</summary>
    public int Stride => _width * 4;

    /// <summary>
    /// The most recent frame as BGRA32. The buffer is reused between frames, so a
    /// consumer must copy out anything it needs to keep. Null before the first
    /// successful decode.
    /// </summary>
    public byte[]? Pixels => _scratch;

    /// <summary>
    /// Decodes one frame. Must be called on the UI thread because it writes into
    /// a bitmap owned by the rendering thread.
    /// </summary>
    public WriteableBitmap? Render(byte[] jpeg)
    {
        try
        {
            using var stream = new MemoryStream(jpeg, writable: false);
            var decoder = new JpegBitmapDecoder(
                stream, BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
            if (decoder.Frames.Count == 0) return _target;

            var frame = decoder.Frames[0];
            var source = frame.Format == PixelFormats.Bgra32
                ? (BitmapSource)frame
                : new FormatConvertedBitmap(frame, PixelFormats.Bgra32, null, 0);

            var width = source.PixelWidth;
            var height = source.PixelHeight;
            if (width <= 0 || height <= 0) return _target;

            var stride = width * 4;
            if (_target is null || width != _width || height != _height ||
                _scratch is null || _scratch.Length < stride * height)
            {
                _width = width;
                _height = height;
                _scratch = new byte[stride * height];
                _target = new WriteableBitmap(width, height, 96, 96, PixelFormats.Bgra32, null);
            }

            source.CopyPixels(_scratch, stride, 0);
            _target.WritePixels(new Int32Rect(0, 0, width, height), _scratch, stride, 0);
            FrameCount++;
            return _target;
        }
        catch
        {
            // A dropped or truncated frame is not worth interrupting the stream for.
            return _target;
        }
    }

    public void Reset()
    {
        _target = null;
        _scratch = null;
        _width = 0;
        _height = 0;
        FrameCount = 0;
    }
}
