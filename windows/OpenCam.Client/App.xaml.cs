using System.Windows;
using System.Windows.Threading;

namespace OpenCam.Client;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        DispatcherUnhandledException += OnDispatcherUnhandledException;
    }

    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        // A dead UI thread is worse than a visible error, so surface it and keep going.
        MessageBox.Show(
            e.Exception.Message,
            "OpenCam",
            MessageBoxButton.OK,
            MessageBoxImage.Warning);
        e.Handled = true;
    }
}
