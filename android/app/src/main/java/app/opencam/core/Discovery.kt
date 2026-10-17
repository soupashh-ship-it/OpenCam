package app.opencam.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/**
 * Publishes this phone on the LAN over mDNS.
 *
 * The service type matches the one the reference Android server advertises
 * (`_droidcamobs._tcp.`), and OpenCam additionally advertises its own type so a
 * desktop client can prefer OpenCam peers without losing reference interop.
 */
class Discovery(context: Context) {

    private val nsd: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var registration: NsdManager.RegistrationListener? = null
    private var registeredType: String? = null

    val isAdvertising: Boolean get() = registration != null

    fun advertise(name: String, port: Int, deviceName: String) {
        if (nsd == null) {
            Log.w(TAG, "no NsdManager on this device")
            return
        }
        if (registration != null) return

        // Namespace-specific API: on API 34+ registerService must match the
        // protocol used when the service was advertised.
        val protocol = if (Build.VERSION.SDK_INT >= 34) {
            NsdManager.PROTOCOL_DNS_SD
        } else {
            1
        }

        @Suppress("DEPRECATION")
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "advertising ${info.serviceName} on ${info.serviceType}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "registration failed for ${info.serviceType}: $errorCode")
                registration = null
                registeredType = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "stopped advertising ${info.serviceType}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "unregistration failed: $errorCode")
            }
        }

        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("name", deviceName)
            setAttribute("app", "opencam")
        }

        try {
            nsd.registerService(info, protocol, listener)
            registration = listener
            registeredType = SERVICE_TYPE
        } catch (e: Exception) {
            Log.e(TAG, "advertise failed", e)
            registration = null
            registeredType = null
        }
    }

    fun stop() {
        val r = registration ?: return
        registration = null
        registeredType = null
        try {
            nsd?.unregisterService(r)
        } catch (e: Exception) {
            Log.w(TAG, "unregister failed: ${e.message}")
        }
    }

    companion object {
        /** OpenCam's own service type. */
        const val SERVICE_TYPE = "_opencam._tcp."

        /** The reference server's service type, advertised for compatibility. */
        const val LEGACY_SERVICE_TYPE = "_droidcamobs._tcp."

        private const val TAG = "OpenCam/Discovery"
    }
}
