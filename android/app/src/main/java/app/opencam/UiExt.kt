package app.opencam

import android.view.View
import android.widget.AdapterView
import android.widget.Spinner

/** Lambda-friendly adapter selection listener (Spinner has no SAM setter). */
fun Spinner.onItemSelected(action: (Int) -> Unit) {
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
            parent: AdapterView<*>?,
            view: View?,
            position: Int,
            id: Long
        ) {
            action(position)
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {
        }
    }
}
