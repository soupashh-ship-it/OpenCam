package app.opencam.view

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.TextureView

/**
 * [TextureView] that maps a camera buffer into the view with the same fit / fill
 * and flip behaviour the reference app implements in its GL preview. The view
 * itself always fills its parent; the transform creates the letterbox in fit
 * mode and the centre crop in fill mode.
 */
class PreviewTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr) {

    private var sourceWidth = 16
    private var sourceHeight = 9
    private var fill = false
    private var mirror = false
    private var flipVertical = false

    fun setGeometry(
        sourceWidth: Int,
        sourceHeight: Int,
        fill: Boolean,
        mirror: Boolean,
        flipVertical: Boolean
    ) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        if (this.sourceWidth == sourceWidth && this.sourceHeight == sourceHeight &&
            this.fill == fill && this.mirror == mirror && this.flipVertical == flipVertical
        ) {
            return
        }
        this.sourceWidth = sourceWidth
        this.sourceHeight = sourceHeight
        this.fill = fill
        this.mirror = mirror
        this.flipVertical = flipVertical
        applyTransform()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTransform()
    }

    /**
     * The content of a TextureView is stretched to the view by default. Scale
     * it back to the aspect-correct rectangle, around the view centre, and use
     * a negative scale for the requested flips. The pivot makes the flipped
     * image stay centred instead of jumping off-screen.
     */
    private fun applyTransform() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val target = PreviewGeometry.unclampedRect(w, h, sourceWidth, sourceHeight, fill)
        if (target.width() <= 0 || target.height() <= 0) return

        val sx = target.width().toFloat() / w.toFloat()
        val sy = target.height().toFloat() / h.toFloat()
        val matrix = Matrix()
        matrix.setScale(
            if (mirror) -sx else sx,
            if (flipVertical) -sy else sy,
            w / 2f,
            h / 2f
        )
        setTransform(matrix)
    }
}
