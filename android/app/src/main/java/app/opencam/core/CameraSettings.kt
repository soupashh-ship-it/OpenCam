package app.opencam.core

import android.hardware.camera2.CameraMetadata

/**
 * Live camera control state. Values are normalised the same way the reference
 * control surface exposes them: zoom / wbLevel are 0..1, ev is in EV steps,
 * iso and exposure are raw sensor units with 0 meaning "let the camera decide".
 */
data class CameraSettings(
    var cameraId: String? = null,
    var facing: Int = CameraMetadata.LENS_FACING_BACK,
    var width: Int = 1280,
    var height: Int = 720,
    var fps: Int = 30,

    var zoom: Float = 0f,
    var ev: Float = 0f,
    var iso: Int = 0,
    var exposureNs: Long = 0L,

    var wbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    var wbLevel: Float = 0.5f,
    var wbLock: Boolean = false,

    var afMode: Int = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
    var manualFocus: Float = 0f,

    var aeLock: Boolean = false,
    var torch: Boolean = false,
    var micEnabled: Boolean = true,

    var mirror: Boolean = false,
    var flipVertical: Boolean = false
) {
    val sensorIsManualExposure: Boolean get() = iso > 0 || exposureNs > 0L

    /**
     * White balance as the control endpoint numbers it.
     *
     * The Camera2 constants are not contiguous (AUTO is 1, DAYLIGHT is 5), so a
     * client that writes index N must read index N back. Reporting the raw
     * constant would make round-tripping the value look like a failure.
     */
    val wbModeIndex: Int
        get() = WB_MODES.indexOf(wbMode).let { if (it < 0) 0 else it }

    /** Autofocus mode as the control endpoint numbers it. */
    val afModeIndex: Int
        get() = AF_MODES.indexOf(afMode).let { if (it < 0) 0 else it }

    companion object {
        /** AWB modes in the order the control endpoint accepts them. */
        val WB_MODES = listOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO,
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
            CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_TWILIGHT,
            CameraMetadata.CONTROL_AWB_MODE_SHADE
        )

        val WB_LABELS = listOf(
            "Auto", "Incandescent", "Fluorescent", "Warm Fluorescent",
            "Daylight", "Cloudy", "Twilight", "Shade"
        )

        /** AF modes exposed by the control endpoint. */
        val AF_MODES = listOf(
            CameraMetadata.CONTROL_AF_MODE_OFF,
            CameraMetadata.CONTROL_AF_MODE_AUTO,
            CameraMetadata.CONTROL_AF_MODE_MACRO,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CameraMetadata.CONTROL_AF_MODE_EDOF
        )

        val AF_LABELS = listOf(
            "Off", "Auto", "Macro", "Continuous Video", "Continuous Picture", "EDOF"
        )
    }
}
