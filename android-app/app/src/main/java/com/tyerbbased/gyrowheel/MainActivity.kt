package com.tyerbbased.gyrowheel

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sign

class MainActivity : Activity(), SensorEventListener {

    private var targetIp = "192.168.0.104"
    private var port = 8888
    private var maxAngle = 90f
    private var deadzoneDeg = 2f
    private var smoothingPct = 40
    private var invert = true
    private var axisIndex = 0
    private var orientIndex = 0
    private var vibStrength = 60
    private var keepScreenOn = true

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var qCal: FloatArray? = null
    private var nWorld = floatArrayOf(0f, 0f, 1f)
    private var smoothNorm = 0f
    private var accumulatedDeg = 0f
    private var prevTwistDeg = 0f
    @Volatile private var steerOut = 0
    @Volatile private var twistDeg = 0f

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var netError: String? = null
    @Volatile private var packetsSent = 0L
    @Volatile private var lastAck = 0L
    @Volatile private var running = false
    private var sendThread: Thread? = null
    private var ackThread: Thread? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var vibrator: Vibrator? = null
    @Volatile private var vibrating = false

    private val handler = Handler(Looper.getMainLooper())
    private var rate = 0
    private var lastPackets = 0L
    private var lastRateTime = System.currentTimeMillis()

    private lateinit var statusText: TextView
    private lateinit var angleText: TextView
    private lateinit var valueText: TextView
    private lateinit var rateText: TextView
    private lateinit var bar: ProgressBar
    private lateinit var ipEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var startBtn: Button
    private lateinit var calibBtn: Button
    private lateinit var maxAngleLabel: TextView
    private lateinit var maxAngleSeek: SeekBar
    private lateinit var deadzoneLabel: TextView
    private lateinit var deadzoneSeek: SeekBar
    private lateinit var smoothingLabel: TextView
    private lateinit var smoothingSeek: SeekBar
    private lateinit var vibLabel: TextView
    private lateinit var vibSeek: SeekBar
    private lateinit var axisGroup: RadioGroup
    private lateinit var orientGroup: RadioGroup
    private lateinit var invertCheck: CheckBox
    private lateinit var screenCheck: CheckBox

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPrefs()
        applyOrientation()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        buildUi()
        applyKeepScreenOn()

        handler.postDelayed(uiTick, 250)
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        scroll.addView(root)

        fun label(text: String, size: Float = 14f, bold: Boolean = false, color: Int = 0xFFE0E0E0.toInt()): TextView =
            TextView(this).apply {
                this.text = text; textSize = size; setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }

        root.addView(label("GYRO STEERING WHEEL", 24f, true, 0xFF64B5F6.toInt()).apply {
            gravity = Gravity.CENTER
        })

        statusText = label("STOPPED", 18f, true, Color.GRAY).apply { gravity = Gravity.CENTER }
        root.addView(statusText)

        angleText = label("0°", 40f, true, 0xFFFFF176.toInt()).apply { gravity = Gravity.CENTER }
        root.addView(angleText)

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 2000; progress = 1000
        }
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20)))

        valueText = label("steer = 0   (right = +)", 13f).apply { gravity = Gravity.CENTER }
        root.addView(valueText)

        rateText = label("", 14f, true, Color.GRAY).apply { gravity = Gravity.CENTER }
        root.addView(rateText)

        val rowIp = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ipEdit = EditText(this).apply {
            hint = "PC IP (напр. 192.168.0.104)"
            setText(targetIp)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        portEdit = EditText(this).apply {
            hint = "Port"; setText(port.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        rowIp.addView(ipEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f))
        rowIp.addView(portEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(rowIp)

        val rowBtn = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = Button(this).apply { text = "START"; isAllCaps = false }
        calibBtn = Button(this).apply { text = "CALIBRATE (hold wheel centered)"; isAllCaps = false }
        rowBtn.addView(startBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rowBtn.addView(calibBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        root.addView(rowBtn)

        root.addView(label("Steering axis (how you hold the phone):", 14f, true))
        axisGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val axes = arrayOf(
            "Z — screen faces you (hold like a wheel)",
            "X — long edge horizontal",
            "Y — short edge vertical",
        )
        axes.forEachIndexed { i, t ->
            axisGroup.addView(RadioButton(this).apply {
                text = t; id = 100 + i; isChecked = (i == axisIndex); textSize = 13f
            })
        }
        axisGroup.setOnCheckedChangeListener { _, _ ->
            axisIndex = axisGroup.checkedRadioButtonId - 100
            reCalibrate()
            savePrefs()
        }
        root.addView(axisGroup)

        root.addView(label("Screen orientation:", 14f, true))
        orientGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val orients = arrayOf(
            "Landscape — fixed, never flips (upside-down safe)",
            "Portrait — fixed",
            "Follow system rotation setting",
        )
        orients.forEachIndexed { i, t ->
            orientGroup.addView(RadioButton(this).apply {
                text = t; id = 200 + i; isChecked = (i == orientIndex); textSize = 13f
            })
        }
        orientGroup.setOnCheckedChangeListener { _, _ ->
            orientIndex = orientGroup.checkedRadioButtonId - 200
            applyOrientation()
            savePrefs()
        }
        root.addView(orientGroup)

        val rowChk = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        invertCheck = CheckBox(this).apply {
            text = "Invert"; isChecked = invert; textSize = 13f
            setOnCheckedChangeListener { _, c -> invert = c; savePrefs() }
        }
        screenCheck = CheckBox(this).apply {
            text = "Keep screen on"; isChecked = keepScreenOn; textSize = 13f
            setOnCheckedChangeListener { _, c -> keepScreenOn = c; applyKeepScreenOn(); savePrefs() }
        }
        rowChk.addView(invertCheck, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rowChk.addView(screenCheck, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(rowChk)

        maxAngleLabel = label("Full lock at: ${maxAngle.roundToInt()}°", 14f, true)
        root.addView(maxAngleLabel)
        maxAngleSeek = SeekBar(this).apply { max = 198; progress = ((maxAngle - 30) / 15f).roundToInt() }
        maxAngleSeek.setOnSeekBarChangeListener(simpleSeek {
            maxAngle = 30 + it * 15f
            maxAngleLabel.text = "Full lock at: ${maxAngle.roundToInt()}°"
            savePrefs()
        })
        root.addView(maxAngleSeek)

        deadzoneLabel = label("Deadzone: ${deadzoneDeg.roundToInt()}°", 14f, true)
        root.addView(deadzoneLabel)
        deadzoneSeek = SeekBar(this).apply { max = 10; progress = deadzoneDeg.roundToInt() }
        deadzoneSeek.setOnSeekBarChangeListener(simpleSeek {
            deadzoneDeg = it.toFloat()
            deadzoneLabel.text = "Deadzone: ${it}°"
            savePrefs()
        })
        root.addView(deadzoneSeek)

        smoothingLabel = label("Smoothing: $smoothingPct%", 14f, true)
        root.addView(smoothingLabel)
        smoothingSeek = SeekBar(this).apply { max = 95; progress = smoothingPct }
        smoothingSeek.setOnSeekBarChangeListener(simpleSeek {
            smoothingPct = it
            smoothingLabel.text = "Smoothing: $it%"
            savePrefs()
        })
        root.addView(smoothingSeek)

        vibLabel = label("Vibration beyond full lock: $vibStrength%", 14f, true)
        root.addView(vibLabel)
        vibSeek = SeekBar(this).apply { max = 100; progress = vibStrength }
        vibSeek.setOnSeekBarChangeListener(simpleSeek {
            vibStrength = it
            vibLabel.text = "Vibration beyond full lock: $it%"
            if (vibrating) stopVibration()
            savePrefs()
        })
        root.addView(vibSeek)

        root.addView(label(
            "ПК: запустіть steering_receiver_linux.py (порт $port).\n" +
            "Тримайте телефон як кермо, екраном до себе. CALIBRATE = нуль.",
            12f, color = Color.GRAY
        ))

        startBtn.setOnClickListener { if (running) stopStreaming() else startStreaming() }
        calibBtn.setOnClickListener {
            reCalibrate()
            Toast.makeText(this, "Calibrated at current pose", Toast.LENGTH_SHORT).show()
        }

        setContentView(scroll)
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { onChange(p) }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    private fun applyKeepScreenOn() {
        if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun applyOrientation() {
        requestedOrientation = when (orientIndex) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                 else ActivityInfo.SCREEN_ORIENTATION_USER
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "steering-wheel").apply { setReferenceCounted(false) }
        }
        try {
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
        } catch (_: Exception) {}
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    private fun startVibration() {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp = (1 + vibStrength * 254 / 100).coerceIn(1, 255)
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80), intArrayOf(0, amp), 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 60, 40), 0)
            }
            vibrating = true
        } catch (_: Exception) {}
    }

    private fun stopVibration() {
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrating = false
    }

    private fun startStreaming() {
        if (running) return
        val ip = ipEdit.text.toString().trim()
        val p = portEdit.text.toString().toIntOrNull() ?: 8888
        if (ip.isEmpty()) {
            rateText.text = "Введіть IP комп'ютера!"; rateText.setTextColor(Color.RED)
            return
        }
        targetIp = ip; port = p; savePrefs()

        if (rotationSensor == null) {
            rateText.text = "Немає сенсора обертання!"; rateText.setTextColor(Color.RED)
            return
        }

        netError = null
        lastAck = 0
        packetsSent = 0; lastPackets = 0; lastRateTime = System.currentTimeMillis()
        qCal = null; smoothNorm = 0f

        acquireWifiLock()
        sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_FASTEST)
        running = true

        sendThread = Thread { senderLoop(ip, p) }.apply { name = "steer-send"; start() }
        ackThread = Thread { ackLoop() }.apply { name = "steer-ack"; start() }

        startBtn.text = "STOP"
        statusText.text = "STREAMING → $ip:$p"
        statusText.setTextColor(Color.parseColor("#66BB6A"))
    }

    private fun stopStreaming() {
        running = false
        sendThread?.interrupt()
        try { socket?.close() } catch (_: Exception) {}
        sensorManager.unregisterListener(this)
        releaseWifiLock()
        stopVibration()
        steerOut = 0; twistDeg = 0f
        startBtn.text = "START"
        statusText.text = "STOPPED"
        statusText.setTextColor(Color.GRAY)
        rateText.text = ""
    }

    private val uiTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val dt = (now - lastRateTime) / 1000.0
            if (dt >= 0.5) {
                rate = ((packetsSent - lastPackets) / dt).roundToInt()
                lastPackets = packetsSent
                lastRateTime = now
            }
            angleText.text = "${twistDeg.roundToInt()}°"
            bar.progress = (steerOut + 1000).coerceIn(0, 2000)
            valueText.text = "steer = $steerOut"
            if (running) {
                val err = netError
                when {
                    err != null -> {
                        rateText.text = "✖ Помилка: $err"
                        rateText.setTextColor(Color.RED)
                    }
                    now - lastAck < 2500 -> {
                        rateText.text = "● $rate pkt/s — ПК ОК"
                        rateText.setTextColor(Color.parseColor("#66BB6A"))
                    }
                    else -> {
                        rateText.text = "● $rate pkt/s — ПК мовчить…"
                        rateText.setTextColor(Color.parseColor("#FFB74D"))
                    }
                }
            }
            handler.postDelayed(this, 200)
        }
    }

    private fun senderLoop(ip: String, p: Int) {
        try {
            val s = DatagramSocket()
            s.soTimeout = 1000
            val a = InetAddress.getByName(ip)
            socket = s
            netError = null
            while (running) {
                val data = "S,$steerOut".toByteArray(Charsets.US_ASCII)
                s.send(DatagramPacket(data, data.size, a, p))
                packetsSent++
                Thread.sleep(10)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (running) netError = e.message ?: e.toString()
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun ackLoop() {
        val buf = ByteArray(16)
        while (running) {
            val s = socket
            if (s == null) {
                try { Thread.sleep(50) } catch (_: InterruptedException) { return }
                continue
            }
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                lastAck = System.currentTimeMillis()
            } catch (e: Exception) {
                if (!running) return
            }
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values
        if (v.size < 4) return
        val x = v[0]; val y = v[1]; val z = v[2]; val w = v[3]

        val cal = qCal
        if (cal == null) {
            qCal = floatArrayOf(x, y, z, w)
            nWorld = rotateAxis(x, y, z, w, axisVec())
            return
        }

        val cx = -cal[0]; val cy = -cal[1]; val cz = -cal[2]; val cw = cal[3]
        val rx = cw * x + w * cx + y * cz - z * cy
        val ry = cw * y - w * cy - x * cz + z * cx
        val rz = cw * z - w * cz + x * cy - y * cx
        val rw = cw * w - x * cx - y * cy - z * cz

        val twist = 2f * atan2(
            rx * nWorld[0] + ry * nWorld[1] + rz * nWorld[2],
            rw
        )
        val twDeg = Math.toDegrees(twist.toDouble()).toFloat()
        var d = twDeg - prevTwistDeg
        if (d > 180f) d -= 360f else if (d < -180f) d += 360f
        accumulatedDeg += d
        prevTwistDeg = twDeg
        twistDeg = accumulatedDeg

        val outOfBounds = abs(accumulatedDeg) > maxAngle + 1f
        val backInBounds = abs(accumulatedDeg) < maxAngle - 3f
        if (vibStrength > 0 && vibrator != null) {
            if (outOfBounds && !vibrating) startVibration()
            else if (backInBounds && vibrating) stopVibration()
        } else if (vibrating) {
            stopVibration()
        }

        var norm = accumulatedDeg / maxAngle
        if (norm > 1f) norm = 1f else if (norm < -1f) norm = -1f

        val dz = (deadzoneDeg / maxAngle).coerceIn(0f, 0.9f)
        val an = abs(norm)
        if (an <= dz) norm = 0f else norm = sign(norm) * ((an - dz) / (1f - dz))

        if (invert) norm = -norm

        val alpha = ((100 - smoothingPct) / 100f).coerceIn(0.05f, 1f)
        smoothNorm += alpha * (norm - smoothNorm)

        steerOut = (smoothNorm * 1000f).roundToInt().coerceIn(-1000, 1000)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun reCalibrate() {
        qCal = null
        smoothNorm = 0f
        accumulatedDeg = 0f
        prevTwistDeg = 0f
    }

    private fun axisVec(): FloatArray = when (axisIndex) {
        1 -> floatArrayOf(1f, 0f, 0f)
        2 -> floatArrayOf(0f, 1f, 0f)
        else -> floatArrayOf(0f, 0f, 1f)
    }

    private fun rotateAxis(qx: Float, qy: Float, qz: Float, qw: Float, v: FloatArray): FloatArray {
        val tx = 2f * (qy * v[2] - qz * v[1])
        val ty = 2f * (qz * v[0] - qx * v[2])
        val tz = 2f * (qx * v[1] - qy * v[0])
        return floatArrayOf(
            v[0] + qw * tx + (qy * tz - qz * ty),
            v[1] + qw * ty + (qz * tx - qx * tz),
            v[2] + qw * tz + (qx * ty - qy * tx),
        )
    }

    override fun onPause() {
        super.onPause()
        savePrefs()
        if (running) stopStreaming()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopVibration()
        if (running) stopStreaming()
    }

    private fun savePrefs() {
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().apply {
            putString("ip", targetIp)
            putInt("port", port)
            putFloat("maxAngle", maxAngle)
            putFloat("deadzone", deadzoneDeg)
            putInt("smoothing", smoothingPct)
            putBoolean("invert", invert)
            putInt("axis", axisIndex)
            putInt("orient", orientIndex)
            putInt("vib", vibStrength)
            putBoolean("screenOn", keepScreenOn)
            apply()
        }
    }

    private fun loadPrefs() {
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        targetIp = p.getString("ip", targetIp)!!
        port = p.getInt("port", port)
        maxAngle = p.getFloat("maxAngle", maxAngle)
        deadzoneDeg = p.getFloat("deadzone", deadzoneDeg)
        smoothingPct = p.getInt("smoothing", smoothingPct)
        invert = p.getBoolean("invert", invert)
        axisIndex = p.getInt("axis", axisIndex)
        orientIndex = p.getInt("orient", orientIndex)
        vibStrength = p.getInt("vib", vibStrength)
        keepScreenOn = p.getBoolean("screenOn", keepScreenOn)
    }
}
