package net.duhowpi.bluemate

data class NearbyDevice(
    val id: String,
    val major: Int,
    val minor: Int,
    var rssi: Int,
    var distance: Double,
    var lastSeen: Long = System.currentTimeMillis()
)
