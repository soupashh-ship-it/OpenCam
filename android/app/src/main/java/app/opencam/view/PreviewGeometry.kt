package app.opencam.view

import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * Geometry for an aspect-preserving camera preview.
 *
 * A preview buffer is laid out the way the reference app does it in GL: in fit
 * mode the whole frame is visible inside a letterboxed rectangle, in fill mode
 * the frame is scaled up until it covers the view and the overflow is cropped.
 * Both rectangles are centred on the view.
 */
object PreviewGeometry {

    /**
     * Rectangle occupied by the image inside a [viewWidth] by [viewHeight] view.
     * The returned rectangle is clamped to the view, so in fill mode it is the
     * full view and callers that need the real (oversized) image rectangle use
     * [unclampedRect] instead.
     */
    fun rect(viewWidth: Int, viewHeight: Int, sourceWidth: Int, sourceHeight: Int, fill: Boolean): Rect {
        val r = unclampedRect(viewWidth, viewHeight, sourceWidth, sourceHeight, fill)
        return Rect(
            r.left.coerceAtLeast(0),
            r.top.coerceAtLeast(0),
            r.right.coerceAtMost(viewWidth),
            r.bottom.coerceAtMost(viewHeight)
        )
    }

    /** The real image rectangle, which can extend past the view in fill mode. */
    fun unclampedRect(
        viewWidth: Int,
        viewHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        fill: Boolean
    ): Rect {
        if (viewWidth <= 0 || viewHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return Rect(0, 0, 0, 0)
        }
        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

        val imageWidth: Float
        val imageHeight: Float
        if (fill) {
            if (viewAspect > sourceAspect) {
                imageWidth = viewWidth.toFloat()
                imageHeight = imageWidth / sourceAspect
            } else {
                imageHeight = viewHeight.toFloat()
                imageWidth = imageHeight * sourceAspect
            }
        } else {
            if (viewAspect > sourceAspect) {
                imageHeight = viewHeight.toFloat()
                imageWidth = imageHeight * sourceAspect
            } else {
                imageWidth = viewWidth.toFloat()
                imageHeight = imageWidth / sourceAspect
            }
        }

        val left = ((viewWidth - imageWidth) / 2f).roundToInt()
        val top = ((viewHeight - imageHeight) / 2f).roundToInt()
        return Rect(left, top, left + imageWidth.roundToInt(), top + imageHeight.roundToInt())
    }
}
