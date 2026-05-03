package net.duhowpi.bluemate

data class NearbyDevice(
    val id: String,
    val major: Int,
    val minor: Int,
    var rssi: Int,
    var distance: Double,
    var lastSeen: Long = System.currentTimeMillis(),
    val address: String? = null,
    val displayName: String? = null,
    val isPaired: Boolean = false,
    val isInRange: Boolean = true,
    val compassHeading: Int? = null,
    val customName: String? = null
)
