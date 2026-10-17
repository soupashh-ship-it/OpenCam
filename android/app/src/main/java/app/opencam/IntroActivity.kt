package app.opencam

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.opencam.databinding.IntroPageBinding

/** Welcome page. Skipped once the user has been through onboarding. */
class IntroActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = Prefs(this)
        if (prefs.onboarded) {
            goToMain()
            return
        }

        val ui = IntroPageBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.btnIntroNext1.setOnClickListener {
            startActivity(Intent(this, InitialInfoActivity::class.java))
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
