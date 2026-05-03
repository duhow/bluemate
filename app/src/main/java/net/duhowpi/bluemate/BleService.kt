package net.duhowpi.bluemate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Log
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import androidx.core.content.ContextCompat
import kotlin.math.pow

class BleService : Service() {

    companion object {
        private const val TAG = "BleService"
        const val NOTIFICATION_CHANNEL_ID = "bluemate_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "net.duhowpi.bluemate.STOP_SERVICE"
        const val EXTRA_MODE = "net.duhowpi.bluemate.EXTRA_MODE"
        const val MODE_SCAN = 0
        const val MODE_BEACON_ONLY = 1

        val BEACON_UUID: UUID = UUID.fromString("b10e0a7e-d0b1-4e00-8a7e-b10e0a7ed0b1")
        val BEACON_PARCEL_UUID = ParcelUuid(BEACON_UUID)
        const val TX_POWER_AT_1M: Byte = -59
        const val DEVICE_TIMEOUT_MS = 30_000L
        const val CLEANUP_INTERVAL_MS = 5_000L
        const val BEACON_REFRESH_INTERVAL_MS = 1_000L

        // Ping/call packet constants
        const val PACKET_TYPE_PING: Byte = 0x01
        const val PING_PACKET_SIZE = 10
        const val PING_TTL = 4
        const val PING_ADVERTISE_DURATION_MS = 2_000L
        const val PING_FORWARDED_EXPIRY_MS = 30_000L

        @Volatile
        var isRunning = false
            private set
    }

    interface DeviceUpdateListener {
        fun onDevicesUpdated(devices: List<NearbyDevice>)
        fun onCallReceived(senderMajor: Int, senderMinor: Int) {}
        fun onAckReceived(targetMajor: Int, targetMinor: Int) {}
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
    var mode: Int = MODE_SCAN
        private set
    var compassHeading: Int = 0
        private set

    // Ping/call state
    private var pingAdvertising = false
    private val pendingRelays = ArrayDeque<ByteArray>()
    private val forwardedPings = LinkedHashMap<String, Long>()
    private var currentRingtone: Ringtone? = null

    private val handler = Handler(Looper.getMainLooper())

    private fun pairedId(address: String): String = "paired:$address"

    private val cleanupRunnable = object : Runnable {
        override fun run() {
            mergeBondedDevices()
            val now = System.currentTimeMillis()
            val removed = nearbyDevices.entries.removeAll {
                !it.value.isPaired && now - it.value.lastSeen > DEVICE_TIMEOUT_MS
            }
            if (removed) notifyDevicesUpdated()
            handler.postDelayed(this, CLEANUP_INTERVAL_MS)
        }
    }

    private val beaconRefreshRunnable = object : Runnable {
        override fun run() {
            if (isAdvertising && !pingAdvertising) {
                restartAdvertising()
            }
            handler.postDelayed(this, BEACON_REFRESH_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        if (!isAdvertising && !isScanning) {
            applyMode(mode)
        }
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Derive stable major/minor identifiers from the device's ANDROID_ID so that
        // the same device always advertises the same identity across restarts.
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val hash = deviceId.hashCode()
        deviceMajor = (hash ushr 16) and 0xFFFF
        deviceMinor = hash and 0xFFFF

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        mergeBondedDevices()
        handler.postDelayed(cleanupRunnable, CLEANUP_INTERVAL_MS)
        handler.postDelayed(beaconRefreshRunnable, BEACON_REFRESH_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val requestedMode = intent?.getIntExtra(EXTRA_MODE, mode) ?: mode
        applyMode(requestedMode)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(cleanupRunnable)
        handler.removeCallbacks(beaconRefreshRunnable)
        stopAdvertising()
        stopScanning()
        stopCallAudio()
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
            .setContentText(
                if (mode == MODE_BEACON_ONLY) {
                    getString(R.string.notification_text_beacon)
                } else {
                    getString(R.string.notification_text)
                }
            )
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

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun applyMode(requestedMode: Int) {
        val newMode = if (requestedMode == MODE_BEACON_ONLY) MODE_BEACON_ONLY else MODE_SCAN
        mode = newMode
        if (newMode == MODE_BEACON_ONLY) {
            startAdvertising()
            stopScanning()
        } else {
            startAdvertising()
            startScanning()
        }
        updateNotification()
    }

    @Suppress("MissingPermission")
    private fun startAdvertising() {
        if (isAdvertising) return
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

        // Encode device identity (major, minor, txPower) as service data keyed
        // by the app's own UUID so any device running Bluemate can discover it.
        val serviceData = buildServiceData()

        val data = AdvertiseData.Builder()
            .addServiceData(BEACON_PARCEL_UUID, serviceData)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    @Suppress("MissingPermission")
    private fun stopAdvertising() {
        if (isAdvertising) {
            advertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
        }
    }

    @Suppress("MissingPermission")
    private fun restartAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false
        startAdvertising()
    }

    @Suppress("MissingPermission")
    private fun startScanning() {
        if (isScanning) return
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BLE scanner not available")
            return
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()

        // Do not apply strict service-data filters here. Some Android devices behave
        // inconsistently with zero/partial masks and can drop valid packets.
        // Scan broadly and validate our app UUID in callback parsing.
        scanner?.startScan(emptyList(), scanSettings, scanCallback)
        isScanning = true
    }

    @Suppress("MissingPermission")
    private fun mergeBondedDevices() {
        if (!hasBluetoothConnectPermission()) return
        val bonded = bluetoothAdapter?.bondedDevices ?: emptySet()
        bonded.forEach { device ->
            val address = device.address ?: return@forEach
            val key = pairedId(address)
            nearbyDevices.putIfAbsent(
                key,
                NearbyDevice(
                    id = key,
                    major = -1,
                    minor = -1,
                    rssi = Int.MIN_VALUE,
                    distance = Double.POSITIVE_INFINITY,
                    lastSeen = 0L,
                    address = address,
                    displayName = device.name,
                    isPaired = true,
                    isInRange = false
                )
            )
        }
    }

    @Suppress("MissingPermission")
    private fun stopScanning() {
        if (isScanning) {
            scanner?.stopScan(scanCallback)
            isScanning = false
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun setCompassHeading(heading: Int) {
        compassHeading = heading
    }

    /** Send a call ping to the device identified by [targetMajor] and [targetMinor]. */
    fun sendPing(targetMajor: Int, targetMinor: Int) {
        val data = buildPingData(
            srcMajor = deviceMajor, srcMinor = deviceMinor,
            dstMajor = targetMajor, dstMinor = targetMinor,
            ack = 0, ttl = PING_TTL
        )
        advertisePing(data)
    }

    /** Stop any currently playing call ringtone. */
    fun stopCallAudio() {
        currentRingtone?.stop()
        currentRingtone = null
    }

    /** Build a 10-byte ping packet:
     *  [0]   type = PACKET_TYPE_PING (0x01)
     *  [1-2] source major
     *  [3-4] source minor
     *  [5-6] destination major
     *  [7-8] destination minor
     *  [9]   (ack bit 7) | (ttl bits 6-0)
     */
    private fun buildPingData(
        srcMajor: Int, srcMinor: Int,
        dstMajor: Int, dstMinor: Int,
        ack: Int, ttl: Int
    ): ByteArray {
        val buffer = ByteBuffer.allocate(PING_PACKET_SIZE)
        buffer.put(PACKET_TYPE_PING)
        buffer.putShort(srcMajor.toShort())
        buffer.putShort(srcMinor.toShort())
        buffer.putShort(dstMajor.toShort())
        buffer.putShort(dstMinor.toShort())
        buffer.put(((ack shl 7) or (ttl and 0x7F)).toByte())
        return buffer.array()
    }

    /** Advertise [data] as a ping packet for [PING_ADVERTISE_DURATION_MS] ms, then revert to beacon. */
    @Suppress("MissingPermission")
    private fun advertisePing(data: ByteArray) {
        if (pingAdvertising) {
            pendingRelays.addLast(data)
            return
        }
        pingAdvertising = true

        advertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setTimeout(0)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .addServiceData(BEACON_PARCEL_UUID, data)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        advertiser?.startAdvertising(settings, advertiseData, advertiseCallback)

        handler.postDelayed({
            pingAdvertising = false
            val next = pendingRelays.removeFirstOrNull()
            if (next != null) {
                advertisePing(next)
            } else {
                // Resume normal beacon advertising
                advertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
                startAdvertising()
            }
        }, PING_ADVERTISE_DURATION_MS)
    }

    /** Parse a received ping packet and act: ring if we are the target, relay if we are an intermediary. */
    private fun handlePingPacket(serviceData: ByteArray) {
        val buffer = ByteBuffer.wrap(serviceData)
        buffer.get() // skip type byte
        val srcMajor = buffer.short.toInt() and 0xFFFF
        val srcMinor = buffer.short.toInt() and 0xFFFF
        val dstMajor = buffer.short.toInt() and 0xFFFF
        val dstMinor = buffer.short.toInt() and 0xFFFF
        val flags = buffer.get().toInt() and 0xFF
        val ack = (flags ushr 7) and 0x01
        val ttl = flags and 0x7F

        // Ignore packets originating from this device
        if (srcMajor == deviceMajor && srcMinor == deviceMinor) return

        if (dstMajor == deviceMajor && dstMinor == deviceMinor) {
            if (ack == 0) {
                // Incoming call: play audio and reply with ACK
                handler.post {
                    startCallAudio()
                    listener?.onCallReceived(srcMajor, srcMinor)
                }
                val ackData = buildPingData(
                    srcMajor = deviceMajor, srcMinor = deviceMinor,
                    dstMajor = srcMajor, dstMinor = srcMinor,
                    ack = 1, ttl = PING_TTL
                )
                advertisePing(ackData)
            } else {
                // ACK received: the target confirmed the call
                handler.post { listener?.onAckReceived(srcMajor, srcMinor) }
            }
            return
        }

        // Relay: forward if TTL allows and we have not already forwarded this ping
        if (ttl <= 0) return

        val pingId = "$srcMajor.$srcMinor->$dstMajor.$dstMinor:$ack"
        val now = System.currentTimeMillis()
        forwardedPings.entries.removeAll { now - it.value > PING_FORWARDED_EXPIRY_MS }
        if (forwardedPings.containsKey(pingId)) return
        forwardedPings[pingId] = now

        val relayData = buildPingData(srcMajor, srcMinor, dstMajor, dstMinor, ack, ttl - 1)
        advertisePing(relayData)
    }

    /** Play the default ringtone using the alarm audio stream to bypass Do Not Disturb. */
    private fun startCallAudio() {
        stopCallAudio()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ringtone = RingtoneManager.getRingtone(applicationContext, uri) ?: return
        ringtone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ringtone.isLooping = true
        }
        ringtone.play()
        currentRingtone = ringtone
    }

    /** Encode major (2 bytes) + minor (2 bytes) + txPower (1 byte) + heading (2 bytes) = 7 bytes of service data. */
    private fun buildServiceData(): ByteArray {
        val buffer = ByteBuffer.allocate(7)
        buffer.putShort(deviceMajor.toShort())
        buffer.putShort(deviceMinor.toShort())
        buffer.put(TX_POWER_AT_1M)
        buffer.putShort(compassHeading.toShort())
        return buffer.array()
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.i(TAG, "BLE advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "BLE advertising failed: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @Suppress("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val serviceData = result.scanRecord?.getServiceData(BEACON_PARCEL_UUID)
                ?: return

            // Ping/call packets are 10 bytes and start with PACKET_TYPE_PING
            if (serviceData.size == PING_PACKET_SIZE && serviceData[0] == PACKET_TYPE_PING) {
                handlePingPacket(serviceData)
                return
            }

            if (serviceData.size < 5) return

            val buffer = ByteBuffer.wrap(serviceData)
            val major = buffer.short.toInt() and 0xFFFF
            val minor = buffer.short.toInt() and 0xFFFF
            val txPower = buffer.get().toInt()
            // Buffer position is 5 after reading major (2), minor (2), txPower (1).
            // If the payload is at least 7 bytes there are 2 more bytes available for heading.
            val heading: Int? = if (serviceData.size >= 7) buffer.short.toInt() and 0xFFFF else null

            if (major == deviceMajor && minor == deviceMinor) return

            val distance = calculateDistance(txPower, result.rssi)
            val id = "$major.$minor"
            val now = System.currentTimeMillis()
            val device = result.device
            val address = device?.address
            val isBonded = if (hasBluetoothConnectPermission()) {
                device?.bondState == BluetoothDevice.BOND_BONDED
            } else {
                false
            }
            if (!address.isNullOrEmpty()) {
                nearbyDevices.remove(pairedId(address))
            }

            nearbyDevices[id] = NearbyDevice(
                id = id,
                major = major,
                minor = minor,
                rssi = result.rssi,
                distance = distance,
                lastSeen = now,
                address = address,
                displayName = device?.name,
                isPaired = isBonded,
                isInRange = true,
                compassHeading = heading
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

    private var updatePending = false

    private fun notifyDevicesUpdated() {
        if (updatePending) return
        updatePending = true
        handler.postDelayed({
            updatePending = false
            val devices = nearbyDevices.values
                .filter { it.isInRange }
                .sortedBy { it.distance }
            listener?.onDevicesUpdated(devices)
        }, 300L)
    }
}
