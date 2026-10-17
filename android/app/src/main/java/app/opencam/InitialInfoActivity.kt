package app.opencam

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.opencam.databinding.InitialInfoBinding

/** Explains what the desktop side does, then marks onboarding complete. */
class InitialInfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = InitialInfoBinding.inflate(layoutInflater)
        setContentView(ui.root)

        ui.btnIntroNext2.setOnClickListener {
            Prefs(this).onboarded = true
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
