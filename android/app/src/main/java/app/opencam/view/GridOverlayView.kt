package app.opencam.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Rule-of-thirds overlay shown on top of the camera preview. It is laid out by
 * [PreviewLayout] over the visible image area, not over the letterbox.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        alpha = 150
        strokeWidth = density
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val inset = density / 2f
        for (i in 1..2) {
            val x = w * i / 3f
            canvas.drawLine(x, inset, x, h - inset, paint)
            val y = h * i / 3f
            canvas.drawLine(inset, y, w - inset, y, paint)
        }
    }
}
