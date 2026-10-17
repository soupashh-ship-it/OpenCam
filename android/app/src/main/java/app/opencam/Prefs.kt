package app.opencam

import android.content.Context
import android.content.SharedPreferences

/** Persisted OpenCam settings. Every feature is available to every user. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var port: Int
        get() = sp.getInt(K_PORT, DEFAULT_PORT)
        set(v) = sp.edit().putInt(K_PORT, v.coerceIn(1024, 65000)).apply()

    var resolution: String
        get() = sp.getString(K_RES, RES_DEFAULT) ?: RES_DEFAULT
        set(v) = sp.edit().putString(K_RES, v).apply()

    var fps: Int
        get() = sp.getInt(K_FPS, 30)
        set(v) = sp.edit().putInt(K_FPS, v).apply()

    var cameraFacing: Int
        get() = sp.getInt(K_FACING, 0)
        set(v) = sp.edit().putInt(K_FACING, v).apply()

    var audioEnabled: Boolean
        get() = sp.getBoolean(K_AUDIO, true)
        set(v) = sp.edit().putBoolean(K_AUDIO, v).apply()

    var mirrorFront: Boolean
        get() = sp.getBoolean(K_MIRROR, true)
        set(v) = sp.edit().putBoolean(K_MIRROR, v).apply()

    var keepAwake: Boolean
        get() = sp.getBoolean(K_AWAKE, true)
        set(v) = sp.edit().putBoolean(K_AWAKE, v).apply()

    var discovery: Boolean
        get() = sp.getBoolean(K_DISCOVERY, true)
        set(v) = sp.edit().putBoolean(K_DISCOVERY, v).apply()

    var grid: Boolean
        get() = sp.getBoolean(K_GRID, false)
        set(v) = sp.edit().putBoolean(K_GRID, v).apply()

    var crop: Boolean
        get() = sp.getBoolean(K_CROP, false)
        set(v) = sp.edit().putBoolean(K_CROP, v).apply()

    var flipVertical: Boolean
        get() = sp.getBoolean(K_FLIP_V, false)
        set(v) = sp.edit().putBoolean(K_FLIP_V, v).apply()

    var onboarded: Boolean
        get() = sp.getBoolean(K_ONBOARDED, false)
        set(v) = sp.edit().putBoolean(K_ONBOARDED, v).apply()

    fun restoreDefaults() {
        sp.edit()
            .putInt(K_PORT, DEFAULT_PORT)
            .putString(K_RES, RES_DEFAULT)
            .putInt(K_FPS, 30)
            .putInt(K_FACING, 0)
            .putBoolean(K_AUDIO, true)
            .putBoolean(K_MIRROR, true)
            .putBoolean(K_AWAKE, true)
            .putBoolean(K_DISCOVERY, true)
            .putBoolean(K_GRID, false)
            .putBoolean(K_CROP, false)
            .putBoolean(K_FLIP_V, false)
            .apply()
    }

    companion object {
        const val FILE = "opencam"
        const val DEFAULT_PORT = 4747
        const val RES_DEFAULT = "720p"

        private const val K_PORT = "port"
        private const val K_RES = "resolution"
        private const val K_FPS = "fps"
        private const val K_FACING = "facing"
        private const val K_AUDIO = "audio"
        private const val K_MIRROR = "mirror"
        private const val K_AWAKE = "keep_awake"
        private const val K_DISCOVERY = "discovery"
        private const val K_GRID = "grid"
        private const val K_CROP = "crop"
        private const val K_FLIP_V = "flip_v"
        private const val K_ONBOARDED = "onboarded"
    }
}
