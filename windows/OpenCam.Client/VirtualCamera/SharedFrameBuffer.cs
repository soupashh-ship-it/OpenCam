using System.IO;
using System.IO.MemoryMappedFiles;

namespace OpenCam.Client.VirtualCamera;

/// <summary>
/// Publishes decoded camera frames to the OpenCam virtual camera source.
///
/// The client and the camera source are separate processes, and the camera source
/// is loaded into whichever process is consuming the camera (Teams, Zoom, the
/// Windows Camera app, even the frame server services). A named shared memory
/// section is the simplest way to hand frames across that boundary without
/// copying through a socket or a file.
///
/// Synchronisation is a seqlock: the writer bumps <c>Sequence</c> to an odd value
/// before touching the pixels and to an even value afterwards, so a reader that
/// sees the same even value before and after its copy knows it read a whole
/// frame. That keeps the writer lock-free, which matters because the writer is
/// the UI thread.
/// </summary>
public sealed class SharedFrameBuffer : IDisposable
{
    /// <summary>Must match <c>OPENCAM_FRAME_MAGIC</c> in the camera source.</summary>
    private const uint Magic = 0x4F43414D; // 'OCAM'

    private const int Version = 1;

    /// <summary>Largest frame the section can carry.</summary>
    public const int MaxWidth = 1920;
    public const int MaxHeight = 1080;

    /// <summary>Header size, which must match <c>OpenCamFrameHeader</c> in C++.</summary>
    public const int HeaderSize = 56;

    public const int PixelFormatBgra32 = 1;

    private MemoryMappedFile? _file;
    private MemoryMappedViewAccessor? _view;
    private long _sequence;
    private long _frames;

    public bool IsOpen => _view is not null;

    public long FramesPublished => _frames;

    public void Open()
    {
        if (_view is not null) return;

        var capacity = HeaderSize + (long)MaxWidth * MaxHeight * 4;
        _file = FrameSection.OpenOrCreate("OpenCamFrameBuffer", capacity);
        _view = _file.CreateViewAccessor(0, capacity, MemoryMappedFileAccess.ReadWrite);

        // Initialise the header once so a reader that attaches first sees a
        // well-formed, empty frame rather than uninitialised memory.
        _view.Write(0, 0L);                    // sequence
        _view.Write(8, Magic);                 // magic
        _view.Write(12, Version);              // version
        _view.Write(16, 0);                    // width
        _view.Write(20, 0);                    // height
        _view.Write(24, 0);                    // stride
        _view.Write(28, PixelFormatBgra32);    // pixel format
        _view.Write(32, 0);                    // fps
        _view.Write(36, 0);                    // reserved
        _view.Write(40, 0L);                   // frame counter
        _view.Write(48, 0L);                   // timestamp
    }

    /// <summary>
    /// Publishes one BGRA frame. <paramref name="stride"/> is bytes per row, which
    /// may exceed <c>width * 4</c> when the source buffer is padded.
    /// </summary>
    public void Publish(byte[] pixels, int width, int height, int stride, int fps)
    {
        var view = _view;
        if (view is null) return;
        if (width <= 0 || height <= 0 || width > MaxWidth || height > MaxHeight) return;

        var rowBytes = width * 4;
        if (stride < rowBytes) return;
        if ((long)stride * height > pixels.Length) return;

        // Enter the write: odd sequence means "in progress".
        var writing = ++_sequence | 1L;
        view.Write(0, writing);

        view.Write(16, width);
        view.Write(20, height);
        view.Write(24, rowBytes);
        view.Write(28, PixelFormatBgra32);
        view.Write(32, fps);

        var destination = HeaderSize;
        if (stride == rowBytes)
        {
            view.WriteArray(destination, pixels, 0, rowBytes * height);
        }
        else
        {
            for (var row = 0; row < height; row++)
            {
                view.WriteArray(destination + (long)row * rowBytes, pixels, row * stride, rowBytes);
            }
        }

        _frames++;
        view.Write(40, _frames);
        view.Write(48, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());

        // Leave the write: even sequence means "consistent".
        view.Write(0, writing + 1);
        _sequence = writing + 1;
    }

    public void Dispose()
    {
        _view?.Dispose();
        _view = null;

        // The section outlives this process on purpose: a camera consumer may
        // still be attached. Only release our handle.
        _file?.Dispose();
        _file = null;
    }
}

internal static class FrameSection
{
    /// <summary>
    /// Opens the existing named section, or creates it when absent. The framework
    /// has no combined "create or open" entry point.
    /// </summary>
    public static MemoryMappedFile OpenOrCreate(string name, long capacity)
    {
        try
        {
            return MemoryMappedFile.OpenExisting(name, MemoryMappedFileRights.ReadWrite);
        }
        catch (FileNotFoundException)
        {
            return MemoryMappedFile.CreateNew(name, capacity, MemoryMappedFileAccess.ReadWrite);
        }
    }
}
