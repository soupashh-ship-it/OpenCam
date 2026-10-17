package app.opencam

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.opencam.databinding.ActivityInfoBinding

/** About screen. No upsell, no ad slot, no account. */
class InfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = ActivityInfoBinding.inflate(layoutInflater)
        setContentView(ui.root)

        ui.infoVersion.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)
        ui.infoAddress.text = getString(
            R.string.connect_hint
        )
    }
}
