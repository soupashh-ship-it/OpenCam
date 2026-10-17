using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using OpenCam.Client.Discovery;
using OpenCam.Client.Interop;
using OpenCam.Client.Media;
using OpenCam.Client.Protocol;
using OpenCam.Client.VirtualCamera;

namespace OpenCam.Client;

public partial class MainWindow : Window
{
    private readonly MdnsBrowser _browser = new();
    private readonly ObservableCollection<DiscoveredDevice> _devices = new();
    private readonly VideoRenderer _renderer = new();
    private readonly AudioPlayer _audio = new();
    private readonly IVirtualCameraOutput _virtualCamera = VirtualCameraBackend.Create();

    private readonly DispatcherTimer _renderTimer;
    private readonly DispatcherTimer _pollTimer;

    private OpenCamConnection? _connection;
    private CancellationTokenSource? _streamCts;
    private Task? _videoTask;
    private Task? _audioTask;

    private byte[]? _pendingFrame;
    private readonly object _frameGate = new();

    private bool _initializing = true;
    private bool _suppressControlEvents;
    private bool _audioStarted;

    private long _framesRendered;
    private long _framesLastWindow;
    private int _measuredFps;
    private DateTime _lastFpsSample = DateTime.UtcNow;
    private DateTime _lastFrameAt = DateTime.MinValue;

    public MainWindow()
    {
        InitializeComponent();

        _renderTimer = new DispatcherTimer(DispatcherPriority.Render)
        {
            Interval = TimeSpan.FromMilliseconds(15),
        };
        _renderTimer.Tick += OnRenderTick;
        _renderTimer.Start();

        _pollTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        _pollTimer.Tick += OnPollTick;

        PopulateStaticOptions();
        _initializing = false;

        UpdateControlAvailability(connected: false);
        SetLinkState("Disconnected", "TextDimBrush");

        SourceInitialized += (_, _) => DarkTitleBar.Apply(this);
        Loaded += OnLoaded;
        Closing += OnClosing;
    }

    // ------------------------------------------------------------------- startup

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        _browser.DevicesChanged += OnDevicesChanged;
        _browser.Start();

        ComboDevices.ItemsSource = _devices;
        SetStatus("Searching the network for phones…");
        UpdateVirtualCameraStatus();
    }

    private void OnClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        Disconnect();
        _browser.DevicesChanged -= OnDevicesChanged;
        _browser.Dispose();
        _audio.Dispose();
        _ = _virtualCamera.DisposeAsync();
        _renderTimer.Stop();
        _pollTimer.Stop();
    }

    private void PopulateStaticOptions()
    {
        foreach (var preset in Resolutions.Presets)
        {
            ComboResolution.Items.Add($"{preset.Name}  ({preset.Width}x{preset.Height})");
        }
        ComboResolution.SelectedIndex = 3; // 720p

        foreach (var label in WbModes.Labels) ComboWbMode.Items.Add(label);
        ComboWbMode.SelectedIndex = 0;

        foreach (var label in AfModes.Labels) ComboAfMode.Items.Add(label);
        ComboAfMode.SelectedIndex = 4; // continuous picture

        // Frame rate is set on the phone; this shows what the phone is producing.
        ComboFps.IsEnabled = false;
    }

    private void OnDevicesChanged(IReadOnlyList<DiscoveredDevice> devices)
    {
        Dispatcher.BeginInvoke(() =>
        {
            var selected = ComboDevices.SelectedItem as DiscoveredDevice;

            _devices.Clear();
            foreach (var device in devices) _devices.Add(device);

            if (selected is not null)
            {
                var match = _devices.FirstOrDefault(d =>
                    d.Address == selected.Address && d.Port == selected.Port);
                if (match is not null)
                {
                    // Reselecting the same phone during a refresh must not fire
                    // SelectionChanged, or it would overwrite an address the
                    // user typed by hand.
                    _suppressControlEvents = true;
                    try
                    {
                        ComboDevices.SelectedItem = match;
                    }
                    finally
                    {
                        _suppressControlEvents = false;
                    }
                }
            }
            else if (_devices.Count > 0)
            {
                // First phone found: auto-select it so pressing Connect works
                // out of the box — but never rewrite the address box while a
                // connection is already up.
                if (_connection is not null)
                {
                    _suppressControlEvents = true;
                    try
                    {
                        ComboDevices.SelectedIndex = 0;
                    }
                    finally
                    {
                        _suppressControlEvents = false;
                    }
                }
                else
                {
                    ComboDevices.SelectedIndex = 0;
                }
            }

            if (_connection is null)
            {
                SetStatus(_devices.Count == 0
                    ? "Searching the network for phones… (you can also type an address)"
                    : $"Found {_devices.Count} phone(s).");
            }
        });
    }

    // --------------------------------------------------------------- connecting

    private void OnDeviceSelected(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (_initializing || _suppressControlEvents) return;
        if (ComboDevices.SelectedItem is DiscoveredDevice device)
        {
            TextManualAddress.Text = $"{device.Address}:{device.Port}";
        }
    }

    private void OnAddManualClicked(object sender, RoutedEventArgs e) => ConnectFromAddressBox();

    private void OnConnectClicked(object sender, RoutedEventArgs e) => ConnectFromAddressBox();

    private void ConnectFromAddressBox()
    {
        var text = TextManualAddress.Text.Trim();
        if (text.Length == 0)
        {
            SetStatus("Enter an address such as 192.168.1.10:4747");
            return;
        }

        var host = text;
        var port = 4747;
        var colon = text.LastIndexOf(':');
        if (colon > 0)
        {
            host = text[..colon];
            if (!int.TryParse(text[(colon + 1)..], out port) || port <= 0) port = 4747;
        }

        _ = ConnectAsync(host, port);
    }

    private async Task ConnectAsync(string host, int port)
    {
        Disconnect();

        // Let the queued disconnect UI updates land first so they cannot
        // overwrite the "connecting" state we are about to show.
        await Dispatcher.Yield(DispatcherPriority.Background);

        SetStatus($"Connecting to {host}:{port}…");
        SetLinkState("Connecting…", "AccentAmberBrush");
        BtnConnect.IsEnabled = false;

        var connection = new OpenCamConnection(host, port);
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            var status = await connection.GetStatusAsync(cts.Token);

            if (status is null && await connection.GetDeviceNameAsync(cts.Token) is null)
            {
                SetStatus($"{host}:{port} did not answer as an OpenCam or DroidCam server.");
                connection.Dispose();
                BtnConnect.IsEnabled = true;
                SetLinkState("Disconnected", "TextDimBrush");
                return;
            }

            _connection = connection;
            _streamCts = new CancellationTokenSource();
            _audioStarted = false;
            _renderer.Reset();
            _framesRendered = 0;
            _framesLastWindow = 0;
            _measuredFps = 0;

            PreviewPlaceholder.Visibility = Visibility.Collapsed;
            BtnConnect.IsEnabled = false;
            BtnDisconnect.IsEnabled = true;

            await RefreshDeviceInfoAsync();
            await RefreshCameraListAsync();

            var size = _connection.RequestedSize;
            _videoTask = Task.Run(() => RunVideoAsync(connection, _streamCts.Token));
            _audioTask = Task.Run(() => RunAudioAsync(connection, _streamCts.Token));

            _pollTimer.Start();

            // Mark the phone as live so its tally lamp lights up, the same way a
            // broadcast consumer would.
            _ = connection.SetTallyAsync(1);

            SetLinkState("Connected", "AccentGreenBrush");
            UpdateControlAvailability(connected: true);

            SetStatus($"Connected to {host}:{port}");
        }
        catch (Exception ex)
        {
            connection.Dispose();
            BtnConnect.IsEnabled = true;
            SetLinkState("Disconnected", "TextDimBrush");
            SetStatus($"Connection failed: {ex.Message}");
        }
    }

    private void OnDisconnectClicked(object sender, RoutedEventArgs e) => Disconnect();

    private void Disconnect()
    {
        var connection = _connection;
        _connection = null;

        _pollTimer.Stop();

        try
        {
            _streamCts?.Cancel();
        }
        catch
        {
            // already disposed
        }

        if (connection is not null)
        {
            // Best effort: clear the tally before tearing the socket down.
            try
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                connection.SetTallyAsync(0, cts.Token).GetAwaiter().GetResult();
            }
            catch
            {
            }
        }

        _audio.Stop();
        _streamCts?.Dispose();
        _streamCts = null;
        _videoTask = null;
        _audioTask = null;

        connection?.Dispose();

        Dispatcher.BeginInvoke(() =>
        {
            lock (_frameGate)
            {
                _pendingFrame = null;
            }

            PreviewImage.Source = null;
            PreviewPlaceholder.Visibility = Visibility.Visible;
            BtnConnect.IsEnabled = true;
            BtnDisconnect.IsEnabled = false;
            TallyLamp.Fill = (Brush)FindResource("TextDimBrush");
            TallyText.Text = "idle";
            OverlayTitle.Text = "Disconnected";
            OverlayStats.Text = "—";
            OverlayClientStats.Text = "—";
            InfoName.Text = "No device";
            InfoAddress.Text = "—";
            InfoBattery.Text = "—";
            InfoStream.Text = "—";
            TextAudioStatus.Text = "Not connected";
            SetLinkState("Disconnected", "TextDimBrush");
            UpdateControlAvailability(connected: false);
            SetStatus("Disconnected");
        });
    }

    // ------------------------------------------------------------------ streams

    private async Task RunVideoAsync(OpenCamConnection connection, CancellationToken ct)
    {
        try
        {
            await connection.PumpVideoAsync(frame =>
            {
                lock (_frameGate)
                {
                    _pendingFrame = frame.Jpeg;
                }
                _lastFrameAt = DateTime.UtcNow;
            }, ct);
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception e)
        {
            _ = Dispatcher.BeginInvoke(() => SetStatus($"Video stream stopped: {e.Message}"));
        }
    }

    private async Task RunAudioAsync(OpenCamConnection connection, CancellationToken ct)
    {
        try
        {
            await connection.PumpAudioAsync(
                format =>
                {
                    if (_audioStarted) return;
                    _audioStarted = true;
                    _audio.Start(format);
                    _ = Dispatcher.BeginInvoke(() => TextAudioStatus.Text =
                        $"{format.SampleRate} Hz · {format.Channels} channel · " +
                        $"{format.BitsPerSample}-bit");
                },
                (data, count) => _audio.Push(data, count),
                ct);
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception e)
        {
            _ = Dispatcher.BeginInvoke(() => TextAudioStatus.Text = $"Audio unavailable: {e.Message}");
        }
    }

    /// <summary>
    /// Draws the newest available frame. Rendering on a timer instead of per
    /// arrival keeps a fast phone from flooding the dispatcher queue.
    /// </summary>
    private void OnRenderTick(object? sender, EventArgs e)
    {
        byte[]? frame;
        lock (_frameGate)
        {
            frame = _pendingFrame;
            _pendingFrame = null;
        }

        if (frame is null) return;

        var bitmap = _renderer.Render(frame);
        if (bitmap is not null && !ReferenceEquals(PreviewImage.Source, bitmap))
        {
            PreviewImage.Source = bitmap;
            PreviewPlaceholder.Visibility = Visibility.Collapsed;
        }

        // Hand the virtual camera the frame that was just decoded for the preview,
        // so publishing costs a shared-memory copy and no second JPEG decode.
        if (_renderer.Pixels is { } pixels && _renderer.Width > 0 && _renderer.Height > 0)
        {
            _virtualCamera.PushFrame(pixels, _renderer.Width, _renderer.Height, _renderer.Stride);
        }

        _framesRendered++;

        var elapsed = DateTime.UtcNow - _lastFpsSample;
        if (elapsed.TotalSeconds >= 1.0)
        {
            _measuredFps = (int)Math.Round((_framesRendered - _framesLastWindow) / elapsed.TotalSeconds);
            _framesLastWindow = _framesRendered;
            _lastFpsSample = DateTime.UtcNow;

            var width = _renderer.Width;
            var height = _renderer.Height;
            OverlayStats.Text = $"{width}x{height} · {_measuredFps} fps · {_renderer.FrameCount} frames";
            StatusRight.Text =
                $"{_measuredFps} fps · {(_audio.IsPlaying ? "audio on" : "audio off")} · " +
                $"{bitmap?.PixelWidth ?? 0}x{bitmap?.PixelHeight ?? 0}";
        }
    }

    // --------------------------------------------------------------- device info

    private async void OnPollTick(object? sender, EventArgs e)
    {
        await RefreshDeviceInfoAsync();
    }

    private async Task RefreshDeviceInfoAsync()
    {
        var connection = _connection;
        if (connection is null) return;

        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(6));
            var status = await connection.GetStatusAsync(cts.Token);
            var info = await connection.GetCameraInfoAsync(cts.Token);

            if (status is not null)
            {
                InfoName.Text = string.IsNullOrWhiteSpace(status.Name) ? "Phone" : status.Name;
                InfoBattery.Text = status.Battery >= 0 ? $"Battery {status.Battery}%" : "Battery —";
                InfoStream.Text = status.Streaming
                    ? $"Streaming {status.Width}x{status.Height} @ {status.Fps}fps"
                    : "Phone idle";
                OverlayClientStats.Text =
                    $"clients: {status.VideoClients} video / {status.AudioClients} audio";
                OverlayTitle.Text = InfoName.Text;

                if (string.IsNullOrWhiteSpace(ComboFps.Text))
                {
                    ComboFps.Items.Clear();
                    ComboFps.Items.Add(status.Fps.ToString());
                    ComboFps.SelectedIndex = 0;
                }
                else
                {
                    ComboFps.Items.Clear();
                    ComboFps.Items.Add(status.Fps.ToString());
                    ComboFps.SelectedIndex = 0;
                    ComboFps.Text = status.Fps.ToString();
                }

                var connected = _connection is not null
                    ? $"{_connection.Host}:{_connection.Port}"
                    : "—";
                InfoAddress.Text = connected;

                TallyLamp.Fill = (Brush)FindResource(status.Tally switch
                {
                    1 => "AccentRedBrush",
                    2 => "AccentGreenBrush",
                    _ => "TextDimBrush",
                });
                TallyText.Text = status.Tally switch
                {
                    1 => "program",
                    2 => "preview",
                    _ => "idle",
                };
            }

            if (info is not null) SyncControlsFromDevice(info);
        }
        catch
        {
            // polling is best effort
        }
    }

    private async Task RefreshCameraListAsync()
    {
        var connection = _connection;
        if (connection is null) return;

        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(6));
            var cameras = await connection.GetCameraListAsync(cts.Token);

            _suppressControlEvents = true;
            ComboCamera.Items.Clear();
            foreach (var camera in cameras) ComboCamera.Items.Add(camera.Label);

            var info = await connection.GetCameraInfoAsync(cts.Token);
            ComboCamera.SelectedIndex = info is not null && info.Active < ComboCamera.Items.Count
                ? info.Active
                : 0;
            _suppressControlEvents = false;
        }
        catch
        {
            _suppressControlEvents = false;
        }
    }

    /// <summary>Mirrors the phone's state into the sliders without echoing requests back.</summary>
    private void SyncControlsFromDevice(CameraInfo info)
    {
        _suppressControlEvents = true;
        try
        {
            SliderZoom.Value = Math.Clamp(info.Zoom, 0, 1);
            LabelZoom.Text = info.Zoom.ToString("0.00");

            SliderEv.Value = Math.Clamp(info.Ev, -4, 4);
            LabelEv.Text = info.Ev.ToString("0.0");

            SliderIso.Value = Math.Clamp(info.Iso, 0, 6400);
            LabelIso.Text = info.Iso.ToString();

            var shutterMs = (int)(info.ShutterNs / 1_000_000);
            SliderShutter.Value = Math.Clamp(shutterMs, 0, 500);
            LabelShutter.Text = shutterMs.ToString();

            SliderFocus.Value = Math.Clamp(info.ManualFocus, 0, 1);
            LabelFocus.Text = info.ManualFocus.ToString("0.00");

            SliderWbLevel.Value = Math.Clamp(info.WbLevel, 0, 1);
            LabelWbLevel.Text = info.WbLevel.ToString("0.00");

            if (info.WbMode >= 0 && info.WbMode < ComboWbMode.Items.Count)
                ComboWbMode.SelectedIndex = info.WbMode;

            if (info.AfMode >= 0 && info.AfMode < ComboAfMode.Items.Count)
                ComboAfMode.SelectedIndex = info.AfMode;

            BtnMic.IsChecked = info.Mic;
            BtnTorch.IsChecked = info.Torch;
        }
        finally
        {
            _suppressControlEvents = false;
        }
    }

    // ------------------------------------------------------------------ controls

    private void OnRefreshClicked(object sender, RoutedEventArgs e)
    {
        SetStatus("Refreshing…");
        _ = RefreshCameraListAsync();
        _ = RefreshDeviceInfoAsync();
    }

    private void OnCameraSelected(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (_suppressControlEvents || _connection is null) return;
        _ = _connection.SelectCameraAsync(ComboCamera.SelectedIndex);
    }

    private void OnResolutionSelected(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (_initializing || _connection is null) return;
        var index = ComboResolution.SelectedIndex;
        if (index < 0 || index >= Resolutions.Presets.Length) return;

        var preset = Resolutions.Presets[index];
        _connection.RequestedSize = (preset.Width, preset.Height);

        // The classic `/video` route always sends whatever the phone has selected.
        // Asking for a specific size is done on the versioned route, which OpenCam
        // servers honour by reconfiguring the capture.
        _connection.UseLegacyVideoRoute = false;
        RestartVideoPump();
        SetStatus($"Requested {preset.Width}x{preset.Height} from the phone");
    }

    private void OnFpsSelected(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        // Frame rate is chosen on the phone.
    }

    private void RestartVideoPump()
    {
        var connection = _connection;
        if (connection is null || _streamCts is null) return;

        var ct = _streamCts.Token;
        _videoTask = Task.Run(() => RunVideoAsync(connection, ct));
    }

    private void OnZoomChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        LabelZoom.Text = e.NewValue.ToString("0.00");
        if (_suppressControlEvents || _connection is null) return;
        _ = _connection.SetZoomAsync((float)e.NewValue);
    }

    private void OnEvChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        LabelEv.Text = e.NewValue.ToString("0.0");
        if (_suppressControlEvents || _connection is null) return;
        _ = _connection.SetEvAsync((float)e.NewValue);
    }

    private void OnIsoChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        var value = (int)Math.Round(e.NewValue);
        LabelIso.Text = value.ToString();
        if (_suppressControlEvents || _connection is null) return;
        _ = _connection.SetIsoAsync(value);
    }

    private void OnShutterChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        var value = (int)Math.Round(e.NewValue);
        LabelShutter.Text = value.ToString();
        if (_suppressControlEvents || _connection is null) return;
        _ = _connection.SetShutterMsAsync(value);
    }

    private void OnFocusChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        LabelFocus.Text = e.NewValue.ToString("0.00");
        if (_suppressControlEvents || _connection is null) return;
        _ = _connection.SetManualFocusAsync((float)e.NewValue);
    }

    private void OnWbLevelChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        LabelWbLevel.Text = e.NewValue.ToString("0.00");
        if (_suppressControlEvents || _connection is null) return;
        _ = _connection.SetWhiteBalanceLevelAsync((float)e.NewValue);
    }

    private void OnWbModeSelected(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (_suppressControlEvents || _connection is null) return;
        _ = _connection.SetWhiteBalanceModeAsync(ComboWbMode.SelectedIndex);
    }

    private void OnAfModeSelected(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (_suppressControlEvents || _connection is null) return;
        _ = _connection.SetAutofocusModeAsync(ComboAfMode.SelectedIndex);
    }

    private void OnMicClicked(object sender, RoutedEventArgs e)
    {
        if (_suppressControlEvents || _connection is null) return;
        _ = _connection.ToggleMicrophoneAsync();
    }

    private void OnTorchClicked(object sender, RoutedEventArgs e)
    {
        if (_suppressControlEvents || _connection is null) return;
        _ = _connection.ToggleTorchAsync();
    }

    private void OnAeLockClicked(object sender, RoutedEventArgs e)
    {
        if (_suppressControlEvents || _connection is null) return;
        _ = _connection.ToggleAeLockAsync();
    }

    private void OnWbLockClicked(object sender, RoutedEventArgs e)
    {
        if (_suppressControlEvents || _connection is null) return;
        _ = _connection.ToggleWhiteBalanceLockAsync();
    }

    private void OnAutofocusClicked(object sender, RoutedEventArgs e)
    {
        _ = _connection?.TriggerAutofocusAsync();
    }

    private void OnResetImageClicked(object sender, RoutedEventArgs e)
    {
        var connection = _connection;
        if (connection is null) return;

        _ = connection.SetZoomAsync(0);
        _ = connection.SetEvAsync(0);
        _ = connection.SetIsoAsync(0);
        _ = connection.SetShutterMsAsync(0);
        _ = connection.SetManualFocusAsync(0);
        _ = connection.SetWhiteBalanceLevelAsync(0.5f);
        _ = connection.SetWhiteBalanceModeAsync(0);
        SetStatus("Image controls reset");
    }

    // ------------------------------------------------------------ virtual camera

    private void UpdateVirtualCameraStatus()
    {
        var status = _virtualCamera.Probe();
        TextVirtualCameraStatus.Text = status.Detail;
        BtnVirtualCamera.IsEnabled = status.CanPublish;
        BtnVirtualCamera.ToolTip = status.RuntimePath is null
            ? status.Detail
            : $"{status.Detail}\nRuntime: {status.RuntimePath}";
    }

    private async void OnVirtualCameraClicked(object sender, RoutedEventArgs e)
    {
        var status = _virtualCamera.Probe();
        if (!status.CanPublish)
        {
            TextVirtualCameraStatus.Text = status.Detail;
            BtnVirtualCamera.IsChecked = false;
            return;
        }

        try
        {
            if (BtnVirtualCamera.IsChecked == true)
            {
                var width = _renderer.Width > 0 ? _renderer.Width : 1280;
                var height = _renderer.Height > 0 ? _renderer.Height : 720;
                await _virtualCamera.StartAsync(width, height, _measuredFps > 0 ? _measuredFps : 30,
                    CancellationToken.None);
                SetStatus("Virtual camera started");
            }
            else
            {
                await _virtualCamera.StopAsync();
                SetStatus("Virtual camera stopped");
            }
        }
        catch (Exception ex)
        {
            BtnVirtualCamera.IsChecked = false;
            TextVirtualCameraStatus.Text = ex.Message;
        }
    }

    private void OnAddressKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key != System.Windows.Input.Key.Enter) return;
        e.Handled = true;
        ConnectFromAddressBox();
    }

    /// <summary>Updates the header pill that mirrors the link state.</summary>
    private void SetLinkState(string text, string brushKey)
    {
        LinkText.Text = text;
        if (FindResource(brushKey) is Brush brush) LinkDot.Fill = brush;
    }

    /// <summary>
    /// The camera controls only do something with a phone attached: grey them
    /// out instead of accepting input that silently no-ops.
    /// </summary>
    private void UpdateControlAvailability(bool connected)
    {
        FrameworkElement[] cameraControls =
        {
            ComboCamera, ComboResolution, ComboWbMode, ComboAfMode,
            SliderZoom, SliderEv, SliderIso, SliderShutter, SliderFocus, SliderWbLevel,
            BtnMic, BtnTorch, BtnAeLock, BtnWbLock, BtnAutofocus, BtnResetImage,
        };

        foreach (var control in cameraControls) control.IsEnabled = connected;
        BtnDisconnect.IsEnabled = connected;
    }

    private void SetStatus(string text) => StatusText.Text = text;
}
