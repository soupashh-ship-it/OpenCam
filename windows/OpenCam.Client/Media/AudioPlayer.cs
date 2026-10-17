using NAudio.Wave;
using OpenCam.Client.Protocol;

namespace OpenCam.Client.Media;

/// <summary>
/// Plays the 48 kHz mono PCM arriving from `/v1/audio`.
///
/// A short jitter buffer absorbs Wi-Fi jitter; when it overflows the oldest audio
/// is dropped rather than growing latency without bound.
/// </summary>
public sealed class AudioPlayer : IDisposable
{
    private WaveOutEvent? _output;
    private BufferedWaveProvider? _buffer;
    private readonly object _gate = new();

    public bool IsPlaying { get; private set; }

    public string? DeviceName { get; private set; }

    /// <summary>Bytes currently buffered, for a latency readout.</summary>
    public int BufferedBytes => _buffer?.BufferedBytes ?? 0;

    public void Start(WavFormat format)
    {
        Stop();

        if (format.BitsPerSample != 16)
        {
            throw new NotSupportedException(
                $"unsupported sample format: {format.BitsPerSample}-bit");
        }

        var waveFormat = new WaveFormat(format.SampleRate, format.BitsPerSample, format.Channels);

        var buffer = new BufferedWaveProvider(waveFormat)
        {
            BufferDuration = TimeSpan.FromSeconds(2),
            DiscardOnBufferOverflow = true,
            ReadFully = true,
        };

        var output = new WaveOutEvent { DesiredLatency = 120, NumberOfBuffers = 3 };
        output.Init(buffer);
        output.Play();

        lock (_gate)
        {
            _buffer = buffer;
            _output = output;
            IsPlaying = true;
            DeviceName = "Default output";
        }
    }

    public void Push(byte[] data, int count)
    {
        lock (_gate)
        {
            _buffer?.AddSamples(data, 0, count);
        }
    }

    public void Stop()
    {
        lock (_gate)
        {
            IsPlaying = false;
            DeviceName = null;
            try
            {
                _output?.Stop();
            }
            catch
            {
                // the device may already be gone
            }
            _output?.Dispose();
            _output = null;
            _buffer = null;
        }
    }

    public void Dispose() => Stop();
}
