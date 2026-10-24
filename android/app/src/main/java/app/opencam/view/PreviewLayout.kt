package app.opencam.view

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * Hosts the preview [TextureView] and the grid overlay. The texture always
 * fills the layout and applies its own aspect transform; the grid is measured
 * and positioned over the visible image rectangle so it never draws over the
 * letterbox.
 */
class PreviewLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var preview: PreviewTextureView? = null
    private var grid: GridOverlayView? = null

    private var sourceWidth = 16
    private var sourceHeight = 9
    private var fill = false
    private var mirror = false
    private var flipVertical = false
    private var gridVisible = false

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        clipToPadding = true
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        preview = getChildAt(0) as? PreviewTextureView
        grid = getChildAt(1) as? GridOverlayView
        applyGeometry()
        applyGridVisibility()
    }

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
        applyGeometry()
        requestLayout()
        invalidate()
    }

    fun setGridVisible(visible: Boolean) {
        if (gridVisible == visible) return
        gridVisible = visible
        applyGridVisibility()
    }

    private fun applyGeometry() {
        preview?.setGeometry(sourceWidth, sourceHeight, fill, mirror, flipVertical)
    }

    private fun applyGridVisibility() {
        grid?.visibility = if (gridVisible) View.VISIBLE else View.GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        preview?.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        val image = PreviewGeometry.rect(width, height, sourceWidth, sourceHeight, fill)
        grid?.measure(
            MeasureSpec.makeMeasureSpec(image.width().coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(image.height().coerceAtLeast(0), MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        preview?.layout(0, 0, width, height)
        val image = PreviewGeometry.rect(width, height, sourceWidth, sourceHeight, fill)
        grid?.layout(image.left, image.top, image.right, image.bottom)
    }
}
