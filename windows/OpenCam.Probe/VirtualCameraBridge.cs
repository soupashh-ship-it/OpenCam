using OpenCam.Client.VirtualCamera;

namespace OpenCam.Probe;

/// <summary>
/// Publishes synthetic frames through the client's own <see cref="SharedFrameBuffer"/>.
///
/// This exists so the native virtual camera source can be tested without a phone:
/// run this in one process and <c>OpenCamVirtualCamReadTest.exe</c> in another, and
/// the two have to agree on the shared memory layout, the seqlock and the pixel
/// order for the read test to pass.
/// </summary>
public static class VirtualCameraBridge
{
    /// <summary>Blue = x, green = y, red is a mix. Mirrored in the native read test.</summary>
    public static void FillPattern(byte[] pixels, int width, int height, int stride)
    {
        for (var y = 0; y < height; y++)
        {
            var row = y * stride;
            for (var x = 0; x < width; x++)
            {
                var offset = row + x * 4;
                pixels[offset] = (byte)(x & 0xFF);                    // B
                pixels[offset + 1] = (byte)(y & 0xFF);                // G
                pixels[offset + 2] = (byte)((x * 7 + y * 13) & 0xFF); // R
                pixels[offset + 3] = 0xFF;                            // A
            }
        }
    }

    /// <summary>
    /// <c>--publish-vcam [seconds] [width] [height]</c>. Returns a process exit code.
    /// </summary>
    public static int Publish(string[] args)
    {
        var seconds = args.Length > 1 && int.TryParse(args[1], out var s) ? s : 10;
        var width = args.Length > 2 && int.TryParse(args[2], out var w) ? w : 640;
        var height = args.Length > 3 && int.TryParse(args[3], out var h) ? h : 480;

        if (width <= 0 || height <= 0 || width > SharedFrameBuffer.MaxWidth ||
            height > SharedFrameBuffer.MaxHeight)
        {
            Console.WriteLine($"Unsupported frame size {width}x{height}.");
            return 2;
        }

        Console.WriteLine($"Publishing {width}x{height} BGRA frames for {seconds}s ...");

        // Report whether this machine has an output stage at all. The publishing
        // loop below does not depend on it: the buffer is the same either way.
        var status = VirtualCameraBackend.Create().Probe();
        Console.WriteLine($"  output stage: {status.Availability} - {status.Detail}");

        var stride = width * 4;
        var pixels = new byte[stride * height];
        FillPattern(pixels, width, height, stride);

        using var buffer = new SharedFrameBuffer();
        buffer.Open();
        if (!buffer.IsOpen)
        {
            Console.WriteLine("  could not open the shared frame buffer.");
            return 3;
        }

        var interval = TimeSpan.FromMilliseconds(1000.0 / 30.0);
        var deadline = DateTime.UtcNow + TimeSpan.FromSeconds(seconds);
        var next = DateTime.UtcNow;

        while (DateTime.UtcNow < deadline)
        {
            buffer.Publish(pixels, width, height, stride, 30);
            next += interval;
            var wait = next - DateTime.UtcNow;
            if (wait > TimeSpan.Zero) Thread.Sleep(wait);
            else next = DateTime.UtcNow;
        }

        Console.WriteLine($"  published {buffer.FramesPublished} frames.");
        return buffer.FramesPublished > 0 ? 0 : 1;
    }
}
