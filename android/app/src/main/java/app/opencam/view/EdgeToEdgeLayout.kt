package app.opencam.view

import android.content.Context
import android.graphics.Insets
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout

/**
 * FrameLayout that applies system window insets as padding so overlays never sit
 * under the status bar, navigation bar or a display cutout. Mirrors the
 * edge-to-edge behaviour the reference client relies on.
 */
class EdgeToEdgeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var left = 0
    private var top = 0
    private var right = 0
    private var bottom = 0

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars: Insets = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            left = bars.left
            top = bars.top
            right = bars.right
            bottom = bars.bottom
        } else {
            @Suppress("DEPRECATION")
            left = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            right = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            bottom = insets.systemWindowInsetBottom
        }
        applyPadding()
        return insets
    }

    private fun applyPadding() {
        if (paddingLeft != left || paddingTop != top ||
            paddingRight != right || paddingBottom != bottom
        ) {
            setPadding(left, top, right, bottom)
        }
    }

    fun systemInsets(): IntArray = intArrayOf(left, top, right, bottom)

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) requestApplyInsets()
    }
}
