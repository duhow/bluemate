package net.duhowpi.bluemate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.ParcelUuid
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

class BleService : Service() {

    companion object {
        private const val TAG = "BleService"
        const val NOTIFICATION_CHANNEL_ID = "bluemate_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "net.duhowpi.bluemate.STOP_SERVICE"

        val BEACON_UUID: UUID = UUID.fromString("b10e0a7e-d0b1-4e00-8a7e-b10e0a7ed0b1")
        const val APPLE_COMPANY_ID = 0x004C
        const val IBEACON_PREFIX = 0x0215
        const val TX_POWER_AT_1M: Byte = -59
        const val DEVICE_TIMEOUT_MS = 30_000L
        const val CLEANUP_INTERVAL_MS = 5_000L

        @Volatile
        var isRunning = false
            private set
    }

    interface DeviceUpdateListener {
        fun onDevicesUpdated(devices: List<NearbyDevice>)
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    private val binder = LocalBinder()
    var listener: DeviceUpdateListener? = null

    private val nearbyDevices = ConcurrentHashMap<String, NearbyDevice>()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var isAdvertising = false
    private var isScanning = false

    var deviceMajor: Int = 0
        private set
    var deviceMinor: Int = 0
        private set

    private val handler = Handler(Looper.getMainLooper())

    private val cleanupRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val removed = nearbyDevices.entries.removeAll { now - it.value.lastSeen > DEVICE_TIMEOUT_MS }
            if (removed) notifyDevicesUpdated()
            handler.postDelayed(this, CLEANUP_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Derive stable major/minor identifiers from the device's ANDROID_ID so that
        // the same device always advertises the same iBeacon identity across restarts.
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val hash = deviceId.hashCode()
        deviceMajor = (hash ushr 16) and 0xFFFF
        deviceMinor = hash and 0xFFFF

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        startAdvertising()
        startScanning()
        handler.postDelayed(cleanupRunnable, CLEANUP_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(cleanupRunnable)
        stopAdvertising()
        stopScanning()
        nearbyDevices.clear()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BleService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_bluetooth_notification)
            .setContentIntent(openPending)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.btn_stop),
                    stopPending
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    @Suppress("MissingPermission")
    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "BLE advertising not supported on this device")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addManufacturerData(APPLE_COMPANY_ID, buildIBeaconData())
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BEACON_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    @Suppress("MissingPermission")
    private fun stopAdvertising() {
        if (isAdvertising) {
            advertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
        }
    }

    @Suppress("MissingPermission")
    private fun startScanning() {
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BLE scanner not available")
            return
        }

        val filter = ScanFilter.Builder()
            .setManufacturerData(APPLE_COMPANY_ID, buildIBeaconFilterData(), buildIBeaconFilterMask())
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        scanner?.startScan(listOf(filter), scanSettings, scanCallback)
        isScanning = true
    }

    @Suppress("MissingPermission")
    private fun stopScanning() {
        if (isScanning) {
            scanner?.stopScan(scanCallback)
            isScanning = false
        }
    }

    private fun buildIBeaconData(): ByteArray {
        val buffer = ByteBuffer.allocate(23)
        buffer.putShort(IBEACON_PREFIX.toShort())
        buffer.putLong(BEACON_UUID.mostSignificantBits)
        buffer.putLong(BEACON_UUID.leastSignificantBits)
        buffer.putShort(deviceMajor.toShort())
        buffer.putShort(deviceMinor.toShort())
        buffer.put(TX_POWER_AT_1M)
        return buffer.array()
    }

    private fun buildIBeaconFilterData(): ByteArray {
        val buffer = ByteBuffer.allocate(23)
        buffer.putShort(IBEACON_PREFIX.toShort())
        buffer.putLong(BEACON_UUID.mostSignificantBits)
        buffer.putLong(BEACON_UUID.leastSignificantBits)
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.put(0)
        return buffer.array()
    }

    private fun buildIBeaconFilterMask(): ByteArray {
        val mask = ByteArray(23)
        for (i in 0 until 18) {
            mask[i] = 0xFF.toByte()
        }
        return mask
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.i(TAG, "iBeacon advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "iBeacon advertising failed: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val manufacturerData = result.scanRecord?.getManufacturerSpecificData(APPLE_COMPANY_ID)
                ?: return

            if (manufacturerData.size < 23) return

            val buffer = ByteBuffer.wrap(manufacturerData)
            val prefix = buffer.short.toInt() and 0xFFFF
            if (prefix != IBEACON_PREFIX) return

            val msb = buffer.long
            val lsb = buffer.long
            val uuid = UUID(msb, lsb)
            if (uuid != BEACON_UUID) return

            val major = buffer.short.toInt() and 0xFFFF
            val minor = buffer.short.toInt() and 0xFFFF
            val txPower = buffer.get().toInt()

            if (major == deviceMajor && minor == deviceMinor) return

            val distance = calculateDistance(txPower, result.rssi)
            val id = "$major.$minor"

            nearbyDevices[id] = NearbyDevice(
                id = id,
                major = major,
                minor = minor,
                rssi = result.rssi,
                distance = distance,
                lastSeen = System.currentTimeMillis()
            )
            notifyDevicesUpdated()
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Log.e(TAG, "BLE scan failed: $errorCode")
        }
    }

    // Attempt to estimate distance using the iBeacon distance formula described at
    // https://stackoverflow.com/a/20434019 — constants are empirically-derived
    // calibration values widely used in iBeacon implementations.
    private fun calculateDistance(txPower: Int, rssi: Int): Double {
        if (rssi == 0) return -1.0
        val ratio = rssi.toDouble() / txPower.toDouble()
        return if (ratio < 1.0) {
            ratio.pow(10.0)
        } else {
            0.89976 * ratio.pow(7.7095) + 0.111
        }
    }

    private fun notifyDevicesUpdated() {
        val devices = nearbyDevices.values.sortedBy { it.distance }
        handler.post { listener?.onDevicesUpdated(devices) }
    }
}
