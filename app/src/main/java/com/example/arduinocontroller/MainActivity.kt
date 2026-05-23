package com.example.arduinocontroller

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val REQUEST_BT_PERMISSIONS = 1
        private const val REQUEST_LOCATION_PERMISSION = 2
    }

    //Bluetooth
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var isConnected = false
    private var receiveBuffer = ""
    private val pairedDeviceList = mutableListOf<BluetoothDevice>()

    // --- Odometry ---
    private var displacement = 0f
    private var totalDistance = 0f
    private var metalDetected = false
    private val pathHistory = mutableListOf<Float>()
    private val metalMarkers = mutableListOf<Float>()

    // --- GPS & Compass ---
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var currentLocation: Location? = null
    private var launchLocation: Location? = null   // GPS at the moment we lock heading
    private var headingDeg = 0f                    // compass heading in degrees (0=North)
    private var headingLocked = false
    private val gravityVals  = FloatArray(3)
    private val magneticVals = FloatArray(3)

    // --- Map path tracking ---
    // Each GeoPoint in the robot's world path
    private val geoPath = mutableListOf<GeoPoint>()
    private val geoMetalMarkers = mutableListOf<GeoPoint>()
    private var mapPolyline: Polyline? = null
    private var robotMarker: Marker? = null
    private var startMarker: Marker? = null

    // --- UI ---
    private lateinit var tvStatus: TextView
    private lateinit var tvDisplacement: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvMetal: TextView
    private lateinit var tvGps: TextView
    private lateinit var tvHeading: TextView
    private lateinit var btnForward: Button
    private lateinit var btnBackward: Button
    private lateinit var btnReset: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnLockHeading: Button
    private lateinit var spinnerDevices: Spinner
    private lateinit var btnConnect: Button
    private lateinit var pathView: PathView
    private lateinit var mapView: MapView

    private val mainHandler = Handler(Looper.getMainLooper())

    // -------------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OSMDroid config — must be before setContentView
        Configuration.getInstance().load(this, getPreferences(MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_main)

        // Bind views
        tvStatus       = findViewById(R.id.tvStatus)
        tvDisplacement = findViewById(R.id.tvDisplacement)
        tvDistance     = findViewById(R.id.tvDistance)
        tvMetal        = findViewById(R.id.tvMetal)
        tvGps          = findViewById(R.id.tvGps)
        tvHeading      = findViewById(R.id.tvHeading)
        btnForward     = findViewById(R.id.btnForward)
        btnBackward    = findViewById(R.id.btnBackward)
        btnReset       = findViewById(R.id.btnReset)
        btnRefresh     = findViewById(R.id.btnRefresh)
        btnLockHeading = findViewById(R.id.btnLockHeading)
        spinnerDevices = findViewById(R.id.spinnerDevices)
        btnConnect     = findViewById(R.id.btnConnect)
        pathView       = findViewById(R.id.pathView)
        mapView        = findViewById(R.id.mapView)

        // Setup OSMDroid map
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(19.0)

        // Setup sensors
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        if (bluetoothAdapter == null) { toast("Bluetooth not supported"); return }

        requestAllPermissions()
        populatePairedDevices()
        startLocationUpdates()

        // Button listeners
        btnConnect.setOnClickListener { if (isConnected) disconnect() else connectToSelected() }
        btnRefresh.setOnClickListener { populatePairedDevices() }

        //touch listeners
        btnForward.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    sendCommand('F')
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    sendCommand('S')
                    // small delay then send S again to make sure it registers
                    mainHandler.postDelayed({ sendCommand('S') }, 100)
                    true
                }
                else -> false
            }
        }
        btnBackward.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    sendCommand('B')
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    sendCommand('S')
                    mainHandler.postDelayed({ sendCommand('S') }, 100)
                    true
                }
                else -> false
            }
        }

        btnLockHeading.setOnClickListener {
            if (currentLocation != null) {
                launchLocation = currentLocation
                headingLocked = true
                geoPath.clear()
                geoMetalMarkers.clear()
                // Add start point
                val start = GeoPoint(launchLocation!!.latitude, launchLocation!!.longitude)
                geoPath.add(start)
                setupMapMarkers(start)
                mapView.controller.setCenter(start)
                btnLockHeading.text = "🔒 Heading Locked"
                btnLockHeading.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                toast("Heading locked: %.1f°".format(headingDeg))
            } else {
                toast("Waiting for GPS fix...")
            }
        }

        btnReset.setOnClickListener {
            sendCommand('R')
            displacement = 0f; totalDistance = 0f
            pathHistory.clear(); metalMarkers.clear(); metalDetected = false
            geoPath.clear(); geoMetalMarkers.clear()
            headingLocked = false
            launchLocation = null
            btnLockHeading.text = "🧭 Lock Heading & Start"
            btnLockHeading.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#1565C0"))
            clearMapOverlays()
            updateUI()
        }

        updateUI()
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------
    private fun requestAllPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQUEST_BT_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        populatePairedDevices()
        startLocationUpdates()
    }


    // GPS
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                currentLocation = loc
                mainHandler.post {
                    tvGps.text = "GPS: %.6f, %.6f  ±%.0fm".format(
                        loc.latitude, loc.longitude, loc.accuracy)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0.5f, listener)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 1f, listener)
        } catch (_: Exception) {}
    }


    // Compass

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER  -> System.arraycopy(event.values, 0, gravityVals,  0, 3)
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magneticVals, 0, 3)
        }
        val R = FloatArray(9); val I = FloatArray(9)
        if (SensorManager.getRotationMatrix(R, I, gravityVals, magneticVals)) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(R, orientation)
            headingDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
            if (headingDeg < 0) headingDeg += 360f
            mainHandler.post {
                tvHeading.text = "Heading: %.1f°  %s".format(headingDeg, compassDir(headingDeg))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun compassDir(deg: Float): String = when {
        deg < 22.5 || deg >= 337.5 -> "N"
        deg < 67.5  -> "NE"
        deg < 112.5 -> "E"
        deg < 157.5 -> "SE"
        deg < 202.5 -> "S"
        deg < 247.5 -> "SW"
        deg < 292.5 -> "W"
        else        -> "NW"
    }


    // Map helpers
    private fun setupMapMarkers(start: GeoPoint) {
        mapView.overlays.clear()

        startMarker = Marker(mapView).apply {
            position = start
            title = "Start"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        robotMarker = Marker(mapView).apply {
            position = start
            title = "Robot"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
        mapPolyline = Polyline(mapView).apply {
            outlinePaint.color = Color.parseColor("#2196F3")
            outlinePaint.strokeWidth = 6f
        }

        mapView.overlays.add(mapPolyline)
        mapView.overlays.add(startMarker)
        mapView.overlays.add(robotMarker)
        mapView.invalidate()
    }

    private fun clearMapOverlays() {
        mapView.overlays.clear()
        mapPolyline = null; robotMarker = null; startMarker = null
        mapView.invalidate()
    }


    private fun displacementToGeoPoint(dispCm: Float): GeoPoint {
        val origin = launchLocation!!
        val distM  = dispCm / 100.0  // cm → metres

        val headingRad = Math.toRadians(headingDeg.toDouble())
        val earthR = 6_371_000.0  // metres

        val dLat = (distM / earthR) * cos(headingRad)
        val dLon = (distM / earthR) * sin(headingRad) / cos(Math.toRadians(origin.latitude))

        return GeoPoint(
            origin.latitude  + Math.toDegrees(dLat),
            origin.longitude + Math.toDegrees(dLon)
        )
    }

    private fun updateMapPath() {
        if (!headingLocked || launchLocation == null) return

        val robotGeo = displacementToGeoPoint(displacement)

        // Update polyline — rebuild from geoPath + current robot position
        val points = geoPath.toMutableList()
        points.add(robotGeo)
        mapPolyline?.setPoints(points)

        // Move robot marker
        robotMarker?.position = robotGeo

        // Keep map centered on robot
        mapView.controller.setCenter(robotGeo)
        mapView.invalidate()
    }

    private fun addMetalMarkerOnMap() {
        if (!headingLocked || launchLocation == null) return
        val geo = displacementToGeoPoint(displacement)
        geoMetalMarkers.add(geo)

        val marker = Marker(mapView).apply {
            position = geo
            title = "Metal M${geoMetalMarkers.size}"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(marker)
        mapView.invalidate()
    }

    // Bluetooth
    private fun populatePairedDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            toast("Bluetooth permission needed — please allow it"); return
        }
        pairedDeviceList.clear()
        val names = mutableListOf<String>()
        bluetoothAdapter?.bondedDevices?.forEach { device ->
            pairedDeviceList.add(device)
            names.add("${device.name}  ${device.address}")
        }
        if (names.isEmpty()) {
            toast("No paired devices found.")
            names.add("No paired devices found")
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDevices.adapter = adapter
    }

    private fun connectToSelected() {
        if (pairedDeviceList.isEmpty()) { toast("No paired devices."); return }
        val pos = spinnerDevices.selectedItemPosition
        if (pos >= pairedDeviceList.size) return
        val device = pairedDeviceList[pos]
        tvStatus.text = "Connecting..."
        Thread {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                    mainHandler.post { toast("Bluetooth permission denied") }; return@Thread
                }
                val socket = try {
                    device.createRfcommSocketToServiceRecord(SPP_UUID)
                } catch (e: Exception) {
                    // Fallback for Android 14+ restriction
                    device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                        .invoke(device, 1) as BluetoothSocket
                }
                bluetoothAdapter?.cancelDiscovery()
                socket.connect()
                bluetoothSocket = socket
                outputStream = socket.outputStream
                inputStream  = socket.inputStream
                isConnected  = true
                mainHandler.post {
                    tvStatus.text = "Connected: ${device.name}"
                    btnConnect.text = "Disconnect"
                    toast("Connected!")
                }
                startReadThread()
            } catch (e: IOException) {
                isConnected = false
                mainHandler.post { tvStatus.text = "Connection failed"; toast("Failed: ${e.message}") }
            }
        }.start()
    }

    private fun disconnect() {
        isConnected = false
        try { bluetoothSocket?.close() } catch (_: IOException) {}
        tvStatus.text = "Disconnected"
        btnConnect.text = "Connect"
    }

    private fun startReadThread() {
        Thread {
            val buffer = ByteArray(256)
            while (isConnected) {
                try {
                    val bytes = inputStream!!.read(buffer)
                    receiveBuffer += String(buffer, 0, bytes)
                    while (receiveBuffer.contains("\n")) {
                        val idx  = receiveBuffer.indexOf("\n")
                        val line = receiveBuffer.substring(0, idx).trim()
                        receiveBuffer = receiveBuffer.substring(idx + 1)
                        processLine(line)
                    }
                } catch (_: IOException) {
                    isConnected = false
                    mainHandler.post { tvStatus.text = "Disconnected" }
                    break
                }
            }
        }.start()
    }

    private fun processLine(line: String) {
        if (line.contains("METAL DETECTED")) {
            metalDetected = true
            metalMarkers.add(displacement)
            mainHandler.post {
                addMetalMarkerOnMap()
                updateUI()
            }
            return
        }
        if (line.contains("Displacement") && line.contains("Total Distance")) {
            try {
                val disp = extractValue(line, "Displacement (cm):")
                val dist = extractValue(line, "Total Distance (cm):")
                displacement  = disp
                totalDistance = dist
                pathHistory.add(disp)
                if (pathHistory.size > 500) pathHistory.removeAt(0)
                mainHandler.post {
                    updateMapPath()
                    updateUI()
                }
            } catch (_: Exception) {}
        }
    }

    private fun extractValue(line: String, key: String): Float {
        val start = line.indexOf(key).also { if (it < 0) throw IllegalArgumentException() } + key.length
        val end   = line.indexOf(",", start)
        val raw   = if (end < 0) line.substring(start).trim() else line.substring(start, end).trim()
        return raw.toFloat()
    }

    private fun sendCommand(cmd: Char, retries: Int = 2) {
        if (!isConnected || outputStream == null) { if (cmd != 'S') toast("Not connected"); return }
        Thread {
            var attempts = 0
            while (attempts <= retries) {
                try {
                    outputStream!!.write(cmd.code)
                    outputStream!!.flush()
                    break  // success
                } catch (e: IOException) {
                    attempts++
                    if (attempts > retries) {
                        mainHandler.post { toast("Send failed after $retries retries") }
                    }
                    Thread.sleep(50)
                }
            }
        }.start()
    }

    private fun updateUI() {
        tvDisplacement.text = "Displacement: %.2f cm".format(displacement)
        tvDistance.text     = "Total Distance: %.2f cm".format(totalDistance)
        tvMetal.visibility  = if (metalDetected) View.VISIBLE else View.GONE
        pathView.setData(pathHistory.toList(), displacement, metalMarkers.toList())
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() { super.onDestroy(); disconnect() }

    // Custom View: scrollable 1D path map with metal markers
    class PathView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null
    ) : View(context, attrs) {

        private var path = listOf<Float>()
        private var currentDisplacement = 0f
        private var metalMarkers = listOf<Float>()

        private val PX_PER_CM = 8f
        private var scrollOffsetCm = 0f
        private var lastTouchX = 0f
        // Flag: user is actively scrolling — suppress auto-follow while true
        private var userScrolling = false
        private val scrollResumeHandler = Handler(Looper.getMainLooper())
        private val resumeAutoFollow = Runnable { userScrolling = false }

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2196F3"); strokeWidth = 6f
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#90CAF9"); style = Paint.Style.FILL
        }
        private val robotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5722"); style = Paint.Style.FILL
        }
        private val originPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4CAF50"); style = Paint.Style.FILL
        }
        private val metalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD600"); style = Paint.Style.FILL
        }
        private val metalTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD600"); textSize = 20f; textAlign = Paint.Align.CENTER
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5722"); textSize = 26f
        }
        private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#555555"); strokeWidth = 2f
        }
        private val tickLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#888888"); textSize = 22f; textAlign = Paint.Align.CENTER
        }

        fun setData(history: List<Float>, displacement: Float, markers: List<Float>) {
            path = history
            currentDisplacement = displacement
            metalMarkers = markers

            // Auto-follow only when user is NOT scrolling
            if (!userScrolling && width > 0) {
                val w = width.toFloat()
                val robotScreenX = w / 2f + (displacement - scrollOffsetCm) * PX_PER_CM
                // Only nudge if robot goes outside the middle 60% of the view
                if (robotScreenX > w * 0.8f || robotScreenX < w * 0.2f) {
                    scrollOffsetCm = displacement
                }
            }
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    userScrolling = true
                    // Cancel any pending resume
                    scrollResumeHandler.removeCallbacks(resumeAutoFollow)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    scrollOffsetCm -= dx / PX_PER_CM
                    lastTouchX = event.x
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Resume auto-follow after 3 seconds of inactivity
                    scrollResumeHandler.removeCallbacks(resumeAutoFollow)
                    scrollResumeHandler.postDelayed(resumeAutoFollow, 3000)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val centerY = h / 2f
            val originX = w / 2f - scrollOffsetCm * PX_PER_CM

            canvas.drawColor(Color.parseColor("#1A1A2E"))
            canvas.drawLine(0f, centerY, w, centerY, axisPaint)

            // Tick marks
            val startCm = (scrollOffsetCm - w / (2f * PX_PER_CM) - 10).toInt() / 10 * 10
            val endCm   = (scrollOffsetCm + w / (2f * PX_PER_CM) + 10).toInt() / 10 * 10
            var cm = startCm
            while (cm <= endCm) {
                val tx = originX + cm * PX_PER_CM
                canvas.drawLine(tx, centerY - 8f, tx, centerY + 8f, axisPaint)
                canvas.drawText("${cm}cm", tx, centerY + 30f, tickLabelPaint)
                cm += 10
            }

            // History dots
            path.forEachIndexed { i, v ->
                val x = originX + v * PX_PER_CM
                if (x < -10f || x > w + 10f) return@forEachIndexed
                dotPaint.alpha = 60 + (150f * i / path.size.coerceAtLeast(1)).toInt()
                canvas.drawCircle(x, centerY, 4f, dotPaint)
            }

            // Track line
            if (path.isNotEmpty()) {
                canvas.drawLine(originX, centerY, originX + currentDisplacement * PX_PER_CM, centerY, trackPaint)
            }

            // Metal markers
            metalMarkers.forEachIndexed { i, markerCm ->
                val mx = originX + markerCm * PX_PER_CM
                if (mx < -20f || mx > w + 20f) return@forEachIndexed
                val s = 10f
                val dp = android.graphics.Path().apply {
                    moveTo(mx, centerY - s * 1.8f)
                    lineTo(mx + s, centerY - s * 0.6f)
                    lineTo(mx, centerY + s * 0.6f)
                    lineTo(mx - s, centerY - s * 0.6f)
                    close()
                }
                canvas.drawPath(dp, metalPaint)
                canvas.drawText("M${i + 1}", mx, centerY - s * 2.4f, metalTextPaint)
            }

            // Origin
            canvas.drawCircle(originX, centerY, 12f, originPaint)

            // Robot
            val robotX = originX + currentDisplacement * PX_PER_CM
            canvas.drawCircle(robotX, centerY, 16f, robotPaint)

            // Robot label
            val labelX = robotX.coerceIn(40f, w - 80f)
            canvas.drawText("%.1fcm".format(currentDisplacement), labelX, centerY - 26f, labelPaint)
        }
    }
}
