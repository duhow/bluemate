package net.duhowpi.bluemate

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity(), SensorEventListener, BleService.DeviceUpdateListener {

    private lateinit var compassText: TextView
    private lateinit var statusText: TextView
    private lateinit var deviceCountText: TextView
    private lateinit var modeSpinner: Spinner
    private lateinit var toggleButton: Button
    private lateinit var deviceList: RecyclerView
    private lateinit var emptyText: TextView

    private lateinit var sensorManager: SensorManager
    private val deviceAdapter = DeviceAdapter()

    private var bleService: BleService? = null
    private var serviceBound = false
    private var didBind = false

    private var accelerometerValues: FloatArray? = null
    private var magnetometerValues: FloatArray? = null
    private var lastCompassUpdateMs: Long = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleService.LocalBinder
            bleService = binder.getService()
            bleService?.listener = this@MainActivity
            serviceBound = true
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService?.listener = null
            bleService = null
            serviceBound = false
            updateUI()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            checkBatteryOptimization()
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isBluetoothEnabled()) {
            startBleService(getSelectedMode())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        compassText = findViewById(R.id.compassText)
        statusText = findViewById(R.id.statusText)
        deviceCountText = findViewById(R.id.deviceCountText)
        modeSpinner = findViewById(R.id.modeSpinner)
        toggleButton = findViewById(R.id.toggleButton)
        deviceList = findViewById(R.id.deviceList)
        emptyText = findViewById(R.id.emptyText)

        modeSpinner.adapter = ArrayAdapter.createFromResource(
            this,
            R.array.discovery_mode_options,
            android.R.layout.simple_spinner_dropdown_item
        )

        deviceList.layoutManager = LinearLayoutManager(this)
        deviceList.adapter = deviceAdapter

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        toggleButton.setOnClickListener {
            if (serviceBound) {
                stopBleService()
            } else {
                requestPermissionsAndStart()
            }
        }

        updateUI()
    }

    override fun onStart() {
        super.onStart()
        if (BleService.isRunning && !didBind) {
            didBind = bindService(
                Intent(this, BleService::class.java),
                serviceConnection,
                0
            )
        }
    }

    override fun onResume() {
        super.onResume()
        registerSensors()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onStop() {
        super.onStop()
        if (didBind) {
            bleService?.listener = null
            unbindService(serviceConnection)
            didBind = false
            serviceBound = false
        }
    }

    private fun registerSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accelerometerValues = event.values.clone()
            Sensor.TYPE_MAGNETIC_FIELD -> magnetometerValues = event.values.clone()
        }

        val accel = accelerometerValues ?: return
        val magnet = magnetometerValues ?: return

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        if (SensorManager.getRotationMatrix(rotationMatrix, null, accel, magnet)) {
            val now = System.currentTimeMillis()
            if (now - lastCompassUpdateMs < 200) return
            lastCompassUpdateMs = now
            SensorManager.getOrientation(rotationMatrix, orientation)
            val azimuthDeg = ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toInt()
            val cardinal = getCardinalDirection(azimuthDeg)
            compassText.text = getString(R.string.compass_format, azimuthDeg, cardinal)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDevicesUpdated(devices: List<NearbyDevice>) {
        deviceAdapter.submitList(devices.toList())
        deviceCountText.text = getString(R.string.nearby_count, devices.size)
        emptyText.visibility = if (devices.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        deviceList.visibility = if (devices.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun requestPermissionsAndStart() {
        val needed = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            checkBatteryOptimization()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    @Suppress("BatteryLife")
    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
        ensureBluetoothAndStart()
    }

    @Suppress("MissingPermission")
    private fun ensureBluetoothAndStart() {
        if (!isBluetoothEnabled()) {
            bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            startBleService(getSelectedMode())
        }
    }

    private fun isBluetoothEnabled(): Boolean {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return manager.adapter?.isEnabled == true
    }

    private fun startBleService(mode: Int) {
        val intent = Intent(this, BleService::class.java).apply {
            putExtra(BleService.EXTRA_MODE, mode)
        }
        ContextCompat.startForegroundService(this, intent)
        didBind = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopBleService() {
        if (didBind) {
            bleService?.listener = null
            unbindService(serviceConnection)
            didBind = false
            serviceBound = false
            bleService = null
        }
        stopService(Intent(this, BleService::class.java))
        deviceAdapter.submitList(emptyList())
        updateUI()
    }

    private fun updateUI() {
        if (serviceBound) {
            val serviceMode = bleService?.mode ?: getSelectedMode()
            modeSpinner.setSelection(
                if (serviceMode == BleService.MODE_BEACON_ONLY) 1 else 0,
                false
            )
            modeSpinner.isEnabled = false
            toggleButton.text = getString(R.string.btn_stop)
            statusText.text = if (serviceMode == BleService.MODE_BEACON_ONLY) {
                getString(R.string.status_beacon_only)
            } else {
                getString(R.string.status_scanning)
            }
        } else {
            modeSpinner.isEnabled = true
            toggleButton.text = getString(R.string.btn_start)
            statusText.text = getString(R.string.status_stopped)
            deviceCountText.text = getString(R.string.nearby_count, 0)
            emptyText.visibility = android.view.View.VISIBLE
            deviceList.visibility = android.view.View.GONE
        }
    }

    private fun getSelectedMode(): Int {
        return if (modeSpinner.selectedItemPosition == 1) {
            BleService.MODE_BEACON_ONLY
        } else {
            BleService.MODE_SCAN
        }
    }

    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions
    }

    private fun getCardinalDirection(degrees: Int): String {
        return when {
            degrees >= 338 || degrees < 23 -> "N"
            degrees < 68 -> "NE"
            degrees < 113 -> "E"
            degrees < 158 -> "SE"
            degrees < 203 -> "S"
            degrees < 248 -> "SW"
            degrees < 293 -> "W"
            else -> "NW"
        }
    }
}
