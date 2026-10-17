package app.opencam.core

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size
import kotlin.math.abs

/**
 * Resolution presets. The names match the ones the reference client advertises
 * over its control surface (480p / 720p / 1080p / 1440p / 2160p), with two extra
 * square-ish presets used by the older "720s"/"768s" modes.
 */
object CamCaps {

    data class Preset(val name: String, val width: Int, val height: Int) {
        val label: String get() = "$width x $height"
    }

    val PRESETS = listOf(
        Preset("480p", 640, 480),
        Preset("720s", 960, 720),
        Preset("768s", 1024, 768),
        Preset("720p", 1280, 720),
        Preset("1080p", 1920, 1080),
        Preset("1440p", 2560, 1440),
        Preset("2160p", 3840, 2160)
    )

    fun preset(name: String?): Preset =
        PRESETS.firstOrNull { it.name == name } ?: PRESETS.first { it.name == "720p" }

    /**
     * Picks the JPEG size closest to [target] that keeps the same aspect ratio
     * where possible, so the desktop client never receives a stretched frame.
     */
    fun chooseSize(map: StreamConfigurationMap, target: Preset): Size {
        val sizes = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(target.width, target.height)

        val targetRatio = target.width.toFloat() / target.height

        fun ratioDistance(s: Size): Float =
            abs(s.width.toFloat() / s.height - targetRatio)

        val sameRatio = sizes.filter { ratioDistance(it) < 0.02f && it.width >= it.height }
        val pool = sameRatio.ifEmpty { sizes.filter { it.width >= it.height } }
            .ifEmpty { sizes }

        // Prefer the smallest size that is still at least the requested area so
        // capture is not upscaled, then fall back to the nearest size below it.
        val targetArea = target.width.toLong() * target.height

        val atLeast = pool.filter { it.width.toLong() * it.height >= targetArea }
            .minByOrNull { it.width.toLong() * it.height }
        if (atLeast != null) return atLeast

        return pool.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(target.width, target.height)
    }

    /** Max zoom ratio the sensor exposes, used to scale the normalised zoom value. */
    fun maxZoom(chars: CameraCharacteristics): Float =
        chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

    /** Smallest exposure time the sensor supports, in nanoseconds. */
    fun minExposure(chars: CameraCharacteristics): Long =
        chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.lower ?: 0L

    fun maxExposure(chars: CameraCharacteristics): Long =
        chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.upper ?: 0L

    fun isoRange(chars: CameraCharacteristics): IntRange {
        val r = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: return 100..3200
        val lo = if (r.lower <= 0) 100 else r.lower
        return lo..r.upper
    }
}
