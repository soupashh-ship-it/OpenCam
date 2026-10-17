package app.opencam.core

/**
 * Everything the HTTP control surface is allowed to do to the running service.
 * Implemented by [app.opencam.CamService]; keeps the server independent of the
 * Android service lifecycle.
 */
interface ControlSurface {

    fun settings(): CameraSettings

    /** Mutates the live settings and pushes them to the camera. */
    fun apply(mutate: (CameraSettings) -> Unit)

    /** Switches to the camera at [index] in [cameraList]. */
    fun switchCamera(index: Int)

    /** Requests a size change, e.g. from the /v5/video/<W>x<H>/ route. */
    fun requestSize(width: Int, height: Int)

    /** Restarts the capture pipeline (PUT /v1/restart). */
    fun restartStreaming()

    /** Stops the foreground service (PUT /v1/stop). */
    fun stopStreaming()

    fun cameraList(): List<String>

    fun activeCameraIndex(): Int

    fun deviceName(): String

    fun batteryLevel(): Int

    fun isCharging(): Boolean

    /** Tally state: 0 idle, 1 program, 2 preview. */
    fun setTally(state: Int)

    fun tallyState(): Int

    fun port(): Int
}
