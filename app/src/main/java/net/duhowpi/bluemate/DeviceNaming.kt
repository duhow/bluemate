package net.duhowpi.bluemate

import android.content.Context

class DeviceNaming(context: Context) {

    private val prefs = context.getSharedPreferences("device_names", Context.MODE_PRIVATE)

    private fun key(major: Int, minor: Int) = "name_${major}_${minor}"

    fun getCustomName(major: Int, minor: Int): String? =
        prefs.getString(key(major, minor), null)

    fun setCustomName(major: Int, minor: Int, name: String?) {
        val editor = prefs.edit()
        if (name.isNullOrBlank()) {
            editor.remove(key(major, minor))
        } else {
            editor.putString(key(major, minor), name.trim())
        }
        editor.apply()
    }

    /** Applies stored custom names (and pre-computed generated names) to [devices]. */
    fun applyNames(devices: List<NearbyDevice>): List<NearbyDevice> =
        devices.map { device ->
            if (device.major >= 0 && device.minor >= 0) {
                device.copy(customName = getCustomName(device.major, device.minor))
            } else {
                device
            }
        }
}
