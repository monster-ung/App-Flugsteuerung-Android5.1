package de.ungethuem.flugsteuerung

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Hauptaktivität der Gleitflieger-Steuerung.
 *
 * Unterstützt zwei Achsen:
 *  - Roll (Links/Rechts)
 *  - Pitch (Hoch/Runter)
 *
 * Elevon-Mixing findet auf dem Arduino-Empfänger statt.
 *
 * UDP-Paketformat (4 Bytes):
 *  [0] 0xAA       – Magic Byte / Sync
 *  [1] Armed-Flag – 1 = aktiv, 0 = Failsafe/Neutral
 *  [2] Roll       – 0..180 (90 = Neutral)
 *  [3] Pitch      – 0..180 (90 = Neutral)
 */
class MainActivity : Activity(), SensorEventListener {

    private companion object {
        const val DEFAULT_HOST = "192.168.0.1"
        const val DEFAULT_PORT = 5005
        const val NEUTRAL_ANGLE = 90
        const val MIN_ANGLE = 0
        const val MAX_ANGLE = 180
        const val MAX_DEFLECTION = 50
        const val TILT_ROLL_DEFLECTION_DEG = 35f
        const val TILT_PITCH_DEFLECTION_DEG = 25f
        const val SEND_INTERVAL_MS = 50L
    }

    // --- Netzwerk ---
    private val commandLock = Any()
    private val networkExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var targetHost = DEFAULT_HOST
    @Volatile private var targetPort = DEFAULT_PORT
    @Volatile private var armed = false
    private var socket: DatagramSocket? = null
    private var lastUiNetworkUpdate = 0L
    private var packetsSent = 0L
    private var cachedAddress: InetAddress? = null  // Cache für InetAddress (verhindert GC)
    private var cachedHostString = ""  // Track letzten Host für Cache-Invalidierung
    private var wifiLock: WifiManager.WifiLock? = null  // High-Performance WiFi Lock

    // --- Steuerungswerte ---
    private var rollAngle = NEUTRAL_ANGLE
    private var pitchAngle = NEUTRAL_ANGLE
    private var rollNormalized = 0f
    private var pitchNormalized = 0f

    // --- Trim (einstellbare Neutralposition) ---
    private var trimRollNeutral = NEUTRAL_ANGLE    // Roll neutral (default 90)
    private var trimPitchNeutral = NEUTRAL_ANGLE   // Pitch neutral (default 90)

    // --- Modus ---
    private var tiltMode = true
    private var invertRoll = false
    private var invertPitch = false

    // --- Sensoren & Kalibrierung ---
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    /**
     * Kalibrierungs-Offsets für die Neutralposition.
     * Werden beim Start oder manuell per "Kalibrieren"-Button gesetzt.
     * Erlaubt das Halten des Handys in ergonomischer Position (~30-45°) als Neutral.
     */
    private var calibRoll = 0f
    private var calibPitch = 0f
    private var isCalibrated = false

    // --- UI-Referenzen ---
    private lateinit var connectionIndicator: View
    private lateinit var titleText: TextView
    private lateinit var ipText: TextView
    private lateinit var portText: TextView
    private lateinit var txCountText: TextView
    private lateinit var lagText: TextView
    private lateinit var modeLabel: TextView
    private lateinit var armButton: Button
    private lateinit var rollServoText: TextView
    private lateinit var pitchServoText: TextView
    private lateinit var modeManualButton: Button
    private lateinit var modeGyroButton: Button
    private lateinit var joystick: JoystickView
    private lateinit var joystickContainer: FrameLayout
    private lateinit var gyroContainer: FrameLayout
    private lateinit var artificialHorizon: ArtificialHorizonView
    private lateinit var calibrateButton: Button
    private lateinit var rollTrimText: TextView
    private lateinit var pitchTrimText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Vollbildmodus (Immersive Sticky)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        // WiFi High-Performance Lock erstellen (verhindert Scanning/Power-Saving)
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FlightControlLock")

        buildUi()
        startSender()
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        // WiFi Lock aktivieren für optimale Latenz
        wifiLock?.acquire()
    }

    override fun onPause() {
        setArmed(false)
        sensorManager.unregisterListener(this)
        // WiFi Lock freigeben
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
        super.onPause()
    }

    override fun onStop() {
        setArmed(false)
        super.onStop()
    }

    override fun onDestroy() {
        setArmed(false)
        networkExecutor.shutdownNow()
        socket?.close()
        socket = null
        // WiFi Lock sicher freigeben
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
        wifiLock = null
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────
    //  Sensor-Callbacks
    // ─────────────────────────────────────────────────────────────

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (!tiltMode || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Roll: Kippen links/rechts – im Landscape ist Y die Querneigung
        val rawRoll = Math.toDegrees(
            atan2(y.toDouble(), sqrt((x * x + z * z).toDouble()))
        ).toFloat()

        // Pitch: Kippen vor/zurück – im Landscape ist X die Längsneigung
        val rawPitch = Math.toDegrees(
            atan2(x.toDouble(), sqrt((y * y + z * z).toDouble()))
        ).toFloat()

        // Automatische Kalibrierung beim ersten Sensor-Event
        if (!isCalibrated) {
            calibRoll = rawRoll
            calibPitch = rawPitch
            isCalibrated = true
        }

        // Offset-korrigierte Werte: Abweichung von der kalibrierten Neutralposition
        val rollDeg = rawRoll - calibRoll
        val pitchDeg = rawPitch - calibPitch

        // Normalisierung auf [-1, 1] mit konfigurierbarer Empfindlichkeit
        val normRoll = (rollDeg / TILT_ROLL_DEFLECTION_DEG).coerceIn(-1f, 1f)
        val normPitch = (pitchDeg / TILT_PITCH_DEFLECTION_DEG).coerceIn(-1f, 1f)

        val finalRoll = if (invertRoll) normRoll else -normRoll
        val finalPitch = if (invertPitch) normPitch else -normPitch  // Pitch invertiert: Handy nach vorne → Flieger runter

        updateCommand(finalRoll, finalPitch)

        // Künstlicher Horizont aktualisieren
        if (::artificialHorizon.isInitialized) {
            runOnUiThread { artificialHorizon.setAttitude(finalRoll, finalPitch) }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  UI-Aufbau (Infineon Alula Pilot – Landscape)
    // ─────────────────────────────────────────────────────────────

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.surface))
        }

        // ─── A. Top-Statusleiste ───
        root.addView(buildStatusBar())

        // ─── Hauptbereich: Links (Info 1/3) | Rechts (Steuerung 2/3) ───
        val mainArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        mainArea.addView(buildLeftInfoPanel())
        mainArea.addView(buildRightControlPanel())

        root.addView(mainArea)
        setContentView(root)
    }

    private fun buildStatusBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.status_bar_background)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )

            // Links: LED + Titel
            connectionIndicator = View(context).apply {
                val size = dp(10)
                layoutParams = LinearLayout.LayoutParams(size, size).also { it.marginEnd = dp(8) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color(R.color.connection_red))
                }
            }
            addView(connectionIndicator)

            titleText = TextView(context).apply {
                text = "ALULA PILOT"
                textSize = 13f
                setTextColor(color(R.color.text_primary))
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                letterSpacing = 0.06f
            }
            addView(titleText)

            // Flexible Mitte: IP:Port + Stats
            val centerFlex = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(12), 0, dp(12), 0)
            }

            ipText = TextView(context).apply {
                text = targetHost
                textSize = 11f
                setTextColor(color(R.color.text_secondary))
                typeface = Typeface.MONOSPACE
                setPadding(dp(6), dp(2), dp(6), dp(2))
                setBackgroundColor(color(R.color.surface_elevated))
                setOnClickListener { showEditDialog("Server IP", targetHost) { targetHost = it.ifEmpty { DEFAULT_HOST }; ipText.text = targetHost } }
            }
            centerFlex.addView(ipText)

            centerFlex.addView(TextView(context).apply {
                text = ":"
                textSize = 11f
                setTextColor(color(R.color.text_secondary))
                setPadding(dp(2), 0, dp(2), 0)
            })

            portText = TextView(context).apply {
                text = targetPort.toString()
                textSize = 11f
                setTextColor(color(R.color.text_secondary))
                typeface = Typeface.MONOSPACE
                setPadding(dp(6), dp(2), dp(6), dp(2))
                setBackgroundColor(color(R.color.surface_elevated))
                setOnClickListener { showEditDialog("Port", targetPort.toString()) { targetPort = it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: DEFAULT_PORT; portText.text = targetPort.toString() } }
            }
            centerFlex.addView(portText)

            // Separator
            centerFlex.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(12), 0)
            })

            txCountText = TextView(context).apply {
                text = "TX: 0"
                textSize = 10f
                setTextColor(color(R.color.text_secondary))
                typeface = Typeface.MONOSPACE
            }
            centerFlex.addView(txCountText)

            centerFlex.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), 0)
            })

            lagText = TextView(context).apply {
                text = "Lag: --"
                textSize = 10f
                setTextColor(color(R.color.text_secondary))
                typeface = Typeface.MONOSPACE
            }
            centerFlex.addView(lagText)

            centerFlex.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), 0)
            })

            modeLabel = TextView(context).apply {
                text = "GYRO"
                textSize = 10f
                setTextColor(color(R.color.accent))
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            centerFlex.addView(modeLabel)
            addView(centerFlex)

            // Rechts: ARM Button (prominent, immer sichtbar)
            armButton = Button(context).apply {
                text = "ARM"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                minWidth = dp(90)
                minimumWidth = dp(90)
                minHeight = dp(36)
                minimumHeight = dp(36)
                setPadding(dp(16), dp(4), dp(16), dp(4))
                background = GradientDrawable().apply {
                    setColor(color(R.color.accent))
                    cornerRadius = dp(12).toFloat()
                }
                setOnClickListener {
                    setArmed(!armed)
                    text = if (armed) "DISARM" else "ARM"
                    background = GradientDrawable().apply {
                        setColor(if (armed) color(R.color.armed_green) else color(R.color.accent))
                        cornerRadius = dp(12).toFloat()
                    }
                }
            }
            addView(armButton)
        }
    }

    private fun buildLeftInfoPanel(): ScrollView {
        return ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.33f).also {
                it.marginEnd = dp(6)
            }
            setBackgroundResource(R.drawable.panel_background)
            isVerticalScrollBarEnabled = false
            isFillViewport = true

            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(dp(14), dp(10), dp(14), dp(10))

                // Servo-Daten Header
                addView(TextView(context).apply {
                    text = "LIVE SERVO DATA"
                    textSize = 10f
                    setTextColor(color(R.color.accent))
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    letterSpacing = 0.15f
                    setPadding(0, 0, 0, dp(6))
                })

                // Servo values in compact horizontal rows
                addView(servoRow("Roll", "90°").also { rollServoText = it.getChildAt(1) as TextView })
                addView(servoRow("Pitch", "90°").also { pitchServoText = it.getChildAt(1) as TextView })

                // Mode Toggle
                addView(TextView(context).apply {
                    text = "MODUS"
                    textSize = 10f
                    setTextColor(color(R.color.accent))
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    letterSpacing = 0.12f
                    setPadding(0, dp(10), 0, dp(6))
                })

                val toggleContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                modeManualButton = Button(context).apply {
                    text = "MANUELL"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(color(R.color.text_secondary))
                    setBackgroundResource(R.drawable.toggle_inactive)
                    minHeight = dp(38)
                    minimumHeight = dp(38)
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                        it.marginEnd = dp(3)
                    }
                    setOnClickListener { switchMode(false) }
                }
                toggleContainer.addView(modeManualButton)

                modeGyroButton = Button(context).apply {
                    text = "GYRO"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(color(R.color.text_primary))
                    setBackgroundResource(R.drawable.toggle_active)
                    minHeight = dp(38)
                    minimumHeight = dp(38)
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                        it.marginStart = dp(3)
                    }
                    setOnClickListener { switchMode(true) }
                }
                toggleContainer.addView(modeGyroButton)
                addView(toggleContainer)

                // ─── Trim Section ───
                addView(TextView(context).apply {
                    text = "TRIM"
                    textSize = 10f
                    setTextColor(color(R.color.accent))
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    letterSpacing = 0.12f
                    setPadding(0, dp(10), 0, dp(6))
                })

                addView(buildTrimRow("R", trimRollNeutral) { value ->
                    trimRollNeutral = value
                    updateCommand(rollNormalized, pitchNormalized)
                }.also { rollTrimText = it.findViewWithTag("trimValue") })

                addView(buildTrimRow("P", trimPitchNeutral) { value ->
                    trimPitchNeutral = value
                    updateCommand(rollNormalized, pitchNormalized)
                }.also { pitchTrimText = it.findViewWithTag("trimValue") })
            }
            addView(content)
        }
    }

    private fun servoRow(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(2) }

            addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(color(R.color.text_secondary))
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT)
            })

            addView(TextView(context).apply {
                text = value
                textSize = 24f
                setTextColor(color(R.color.text_primary))
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            })
        }
    }

    private fun buildTrimRow(label: String, initialValue: Int, onChange: (Int) -> Unit): LinearLayout {
        var current = initialValue
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(4) }

            // Label
            addView(TextView(context).apply {
                text = label
                textSize = 11f
                setTextColor(color(R.color.text_secondary))
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(dp(20), LinearLayout.LayoutParams.WRAP_CONTENT)
            })

            // Minus button
            addView(Button(context).apply {
                text = "−"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.text_primary))
                setBackgroundResource(R.drawable.toggle_inactive)
                minWidth = dp(32)
                minimumWidth = dp(32)
                minHeight = dp(32)
                minimumHeight = dp(32)
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).also {
                    it.marginEnd = dp(4)
                }
                setOnClickListener {
                    current = (current - 1).coerceIn(MIN_ANGLE, MAX_ANGLE)
                    onChange(current)
                    (parent as LinearLayout).findViewWithTag<TextView>("trimValue").text = "${current}°"
                }
            })

            // Value (tappable to type)
            addView(TextView(context).apply {
                tag = "trimValue"
                text = "${current}°"
                textSize = 16f
                setTextColor(color(R.color.text_primary))
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    showEditDialog("Trim $label", current.toString()) { input ->
                        val parsed = input.toIntOrNull()?.coerceIn(MIN_ANGLE, MAX_ANGLE) ?: current
                        current = parsed
                        onChange(current)
                        text = "${current}°"
                    }
                }
            })

            // Plus button
            addView(Button(context).apply {
                text = "+"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.text_primary))
                setBackgroundResource(R.drawable.toggle_inactive)
                minWidth = dp(32)
                minimumWidth = dp(32)
                minHeight = dp(32)
                minimumHeight = dp(32)
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).also {
                    it.marginStart = dp(4)
                }
                setOnClickListener {
                    current = (current + 1).coerceIn(MIN_ANGLE, MAX_ANGLE)
                    onChange(current)
                    (parent as LinearLayout).findViewWithTag<TextView>("trimValue").text = "${current}°"
                }
            })
        }
    }

    private fun buildRightControlPanel(): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.67f)
            setBackgroundResource(R.drawable.panel_background)
            setPadding(dp(10), dp(10), dp(10), dp(10))

            // ─── Manueller Modus: Joystick ───
            joystickContainer = FrameLayout(context).apply {
                visibility = if (tiltMode) View.GONE else View.VISIBLE
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            joystick = JoystickView(
                context,
                color(R.color.joystick_base),
                color(R.color.joystick_thumb),
                color(R.color.joystick_ring)
            ).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                listener = object : JoystickView.Listener {
                    override fun onJoystickMoved(x: Float, y: Float) {
                        if (!tiltMode) {
                            val finalX = if (invertRoll) -x else x
                            val finalY = if (invertPitch) -y else y
                            updateCommand(-finalX, finalY)
                        }
                    }
                }
            }
            joystickContainer.addView(joystick)
            addView(joystickContainer)

            // ─── Gyro-Modus: Künstlicher Horizont + Kalibrieren ───
            gyroContainer = FrameLayout(context).apply {
                visibility = if (tiltMode) View.VISIBLE else View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            // Horizont zentriert
            artificialHorizon = ArtificialHorizonView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).also { it.setMargins(dp(10), dp(10), dp(140), dp(10)) }
            }
            gyroContainer.addView(artificialHorizon)

            // Kalibrieren-Button rechts
            calibrateButton = Button(context).apply {
                text = "NEUTRALPUNKT\nKALIBRIEREN"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.calibrate_button)
                layoutParams = FrameLayout.LayoutParams(
                    dp(120),
                    dp(80),
                    Gravity.END or Gravity.CENTER_VERTICAL
                ).also { it.marginEnd = dp(8) }
                setOnClickListener { calibrateSensors() }
            }
            gyroContainer.addView(calibrateButton)

            addView(gyroContainer)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Steuerlogik
    // ─────────────────────────────────────────────────────────────

    /**
     * Berechnet Servo-Winkel aus normalisierten Eingaben.
     * @param roll [-1, 1]: -1 = voll links, +1 = voll rechts
     * @param pitch [-1, 1]: -1 = Nase runter, +1 = Nase hoch
     */
    private fun updateCommand(roll: Float, pitch: Float) {
        val clampedRoll = roll.coerceIn(-1f, 1f)
        val clampedPitch = pitch.coerceIn(-1f, 1f)

        // Rohe Achsenwerte – Elevon-Mixing erfolgt auf dem Arduino
        val nextRoll = (trimRollNeutral + clampedRoll * MAX_DEFLECTION).roundToInt().coerceIn(MIN_ANGLE, MAX_ANGLE)
        val nextPitch = (trimPitchNeutral + clampedPitch * MAX_DEFLECTION).roundToInt().coerceIn(MIN_ANGLE, MAX_ANGLE)

        synchronized(commandLock) {
            rollNormalized = clampedRoll
            pitchNormalized = clampedPitch
            rollAngle = nextRoll
            pitchAngle = nextPitch
        }

        // UI-Update der Servo-Anzeige
        runOnUiThread {
            if (::rollServoText.isInitialized) {
                rollServoText.text = "${nextRoll}°"
                pitchServoText.text = "${nextPitch}°"
            }
        }
    }

    /**
     * Setzt die aktuelle Handy-Position als Neutralreferenz.
     * Erlaubt ergonomisches Halten (~30-45° Neigung).
     */
    private fun calibrateSensors() {
        isCalibrated = false // Wird beim nächsten Sensor-Event neu gesetzt
        updateCommand(0f, 0f)
        if (::artificialHorizon.isInitialized) {
            artificialHorizon.setAttitude(0f, 0f)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Netzwerk & Sicherheit
    // ─────────────────────────────────────────────────────────────

    private fun startSender() {
        networkExecutor.scheduleAtFixedRate({
            if (armed) {
                val payload = synchronized(commandLock) {
                    byteArrayOf(
                        0xAA.toByte(),
                        1,
                        rollAngle.toByte(),
                        pitchAngle.toByte()
                    )
                }
                sendUdp(payload)
                packetsSent++
            }
        }, 0, SEND_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun setArmed(enabled: Boolean) {
        val changed = armed != enabled
        armed = enabled
        if (!enabled && changed) {
            sendFailsafeBurst()
        }
        runOnUiThread { updateConnectionIndicator() }
    }

    private fun sendFailsafeBurst() {
        networkExecutor.execute {
            repeat(5) {
                sendUdp(byteArrayOf(
                    0xAA.toByte(),
                    0,
                    trimRollNeutral.toByte(),
                    trimPitchNeutral.toByte()
                ))
                try {
                    Thread.sleep(30)
                } catch (_: InterruptedException) {
                    return@execute
                }
            }
        }
    }

    private fun sendUdp(payload: ByteArray) {
        try {
            // InetAddress cachen um GC-Pauses zu vermeiden
            if (cachedAddress == null || cachedHostString != targetHost) {
                cachedAddress = InetAddress.getByName(targetHost)
                cachedHostString = targetHost
            }
            
            val packet = DatagramPacket(payload, payload.size, cachedAddress, targetPort)
            val datagramSocket = socket ?: DatagramSocket().also { socket = it }
            val sendStart = System.currentTimeMillis()
            datagramSocket.send(packet)
            val lag = System.currentTimeMillis() - sendStart

            val now = System.currentTimeMillis()
            if (now - lastUiNetworkUpdate > 200L) {
                lastUiNetworkUpdate = now
                runOnUiThread {
                    if (::txCountText.isInitialized) {
                        txCountText.text = "TX: $packetsSent pkts"
                        lagText.text = "Lag: ${lag} ms"
                    }
                }
            }
        } catch (exception: Exception) {
            // Cache invalidieren bei Fehler
            cachedAddress = null
            runOnUiThread {
                if (::connectionIndicator.isInitialized) {
                    (connectionIndicator.background as? GradientDrawable)?.setColor(color(R.color.connection_red))
                }
            }
        }
    }

    private fun updateConnectionIndicator() {
        if (!::connectionIndicator.isInitialized) return
        runOnUiThread {
            val color = if (armed) color(R.color.armed_green) else color(R.color.connection_red)
            (connectionIndicator.background as? GradientDrawable)?.setColor(color)
            if (::armButton.isInitialized) {
                armButton.text = if (armed) "DISARM" else "ARM"
                armButton.background = GradientDrawable().apply {
                    setColor(if (armed) color(R.color.armed_green) else color(R.color.accent))
                    cornerRadius = dp(12).toFloat()
                }
            }
        }
    }

    private fun switchMode(gyro: Boolean) {
        tiltMode = gyro
        joystickContainer.visibility = if (gyro) View.GONE else View.VISIBLE
        gyroContainer.visibility = if (gyro) View.VISIBLE else View.GONE
        modeGyroButton.setBackgroundResource(if (gyro) R.drawable.toggle_active else R.drawable.toggle_inactive)
        modeGyroButton.setTextColor(color(if (gyro) R.color.text_primary else R.color.text_secondary))
        modeManualButton.setBackgroundResource(if (!gyro) R.drawable.toggle_active else R.drawable.toggle_inactive)
        modeManualButton.setTextColor(color(if (!gyro) R.color.text_primary else R.color.text_secondary))
        modeLabel.text = if (gyro) "GYRO" else "MANUELL"
        if (!gyro) {
            updateCommand(joystick.getNormalizedY(), joystick.getNormalizedX())
        }
    }

    private fun showEditDialog(title: String, currentValue: String, onResult: (String) -> Unit) {
        val editText = EditText(this).apply {
            setText(currentValue)
            inputType = if (title.contains("Port")) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            setTextColor(color(R.color.text_primary))
            setBackgroundColor(color(R.color.surface_elevated))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(editText)
            .setPositiveButton("OK") { _, _ -> onResult(editText.text.toString().trim()) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────
    //  UI-Hilfsfunktionen
    // ─────────────────────────────────────────────────────────────

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    /**
     * API-level-safe color lookup. Activity.getColor() requires API 23;
     * resources.getColor(int) works on API 22 and below.
     */
    @Suppress("DEPRECATION")
    private fun color(colorRes: Int): Int = resources.getColor(colorRes)
}
