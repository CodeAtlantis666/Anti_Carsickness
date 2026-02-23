package com.antcarsickness

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

class VehicleMotionEngine(
    context: Context,
    private val onState: (MotionCueState) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val mainHandler = Handler(Looper.getMainLooper())

    private var started = false
    private var lastAccelNs = 0L

    private var gravityInit = false
    private val gravity = FloatArray(3)
    private val linear = FloatArray(3)

    private val latestGyro = FloatArray(3)
    private var lastAccMag = 0f

    private val windowSize = 120
    private val accWindow = FloatArray(windowSize)
    private val gyroWindow = FloatArray(windowSize)
    private val jerkWindow = FloatArray(windowSize)
    private var windowIndex = 0
    private var windowCount = 0

    private var speedX = 0f
    private var speedY = 0f
    private var confidence = 0f

    private var smoothCueX = 0f
    private var smoothCueY = 0f
    private var smoothIntensity = 0f
    private var smoothTurn = 0f
    private var smoothLongitudinal = 0f
    private var smoothLateral = 0f

    fun start() {
        if (started) return
        started = true

        resetState()

        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                latestGyro[0] = event.values[0]
                latestGyro[1] = event.values[1]
                latestGyro[2] = event.values[2]
            }

            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
        }
    }

    private fun handleAccelerometer(event: SensorEvent) {
        val ts = event.timestamp
        if (lastAccelNs == 0L) {
            lastAccelNs = ts
            if (!gravityInit) {
                gravity[0] = event.values[0]
                gravity[1] = event.values[1]
                gravity[2] = event.values[2]
                gravityInit = true
            }
            return
        }

        val dt = ((ts - lastAccelNs) / 1_000_000_000f).coerceIn(0.001f, 0.08f)
        lastAccelNs = ts

        val alpha = exp(-dt / 0.20f)
        if (!gravityInit) {
            gravity[0] = event.values[0]
            gravity[1] = event.values[1]
            gravity[2] = event.values[2]
            gravityInit = true
        }

        for (i in 0..2) {
            gravity[i] = gravity[i] * alpha + event.values[i] * (1f - alpha)
            linear[i] = event.values[i] - gravity[i]
        }

        val accMag = magnitude(linear[0], linear[1], linear[2])
        val jerk = abs(accMag - lastAccMag) / dt
        lastAccMag = accMag

        val gyroMag = magnitude(latestGyro[0], latestGyro[1], latestGyro[2])
        pushWindow(accMag, gyroMag, jerk)

        // Velocity proxy from horizontal acceleration with leakage to suppress integration drift.
        speedX += linear[0] * dt
        speedY += linear[1] * dt
        val leak = exp(-dt / 0.85f)
        speedX *= leak
        speedY *= leak

        val accRms = rms(accWindow, windowCount)
        val jerkRms = rms(jerkWindow, windowCount)
        val gyroRms = rms(gyroWindow, windowCount)
        val speedEstimate = magnitude(speedX, speedY, 0f)

        val likelyVehicleBySignal =
            accRms in 0.08f..2.8f && jerkRms < 8.0f && gyroRms < 1.9f

        val likelyVehicleBySpeed = speedEstimate > 0.32f

        val rise = if (likelyVehicleBySignal || likelyVehicleBySpeed) 1.05f else -0.70f
        confidence = (confidence + rise * dt).coerceIn(0f, 1f)
        val isVehicleLikely = confidence > 0.56f

        // Portrait assumption:
        // +longitudinal -> accelerating forward, -longitudinal -> braking.
        val longitudinalAccelRaw = -linear[1]
        val lateralAccelRaw = linear[0]
        val longitudinalNorm = (longitudinalAccelRaw / 3.1f).coerceIn(-1f, 1f)
        val lateralNorm = (lateralAccelRaw / 2.4f).coerceIn(-1f, 1f)
        val turnGyroNorm = (latestGyro[2] / 1.35f).coerceIn(-1f, 1f)
        val turnNorm = ((lateralNorm * 0.56f) + (turnGyroNorm * 0.92f)).coerceIn(-1f, 1f)

        val accelSmooth = 1f - exp(-dt / 0.10f)
        smoothLongitudinal += (longitudinalNorm - smoothLongitudinal) * accelSmooth
        smoothLateral += (lateralNorm - smoothLateral) * accelSmooth
        smoothTurn += (turnNorm - smoothTurn) * (1f - exp(-dt / 0.11f))

        val cueXRaw = ((smoothLateral * 0.45f) + (smoothTurn * 0.95f) + (speedX * 0.18f)).coerceIn(-1f, 1f)
        val cueYRaw = ((-smoothLongitudinal * 0.92f) + (speedY * 0.14f)).coerceIn(-1f, 1f)

        val accelIntensity =
            (abs(smoothLongitudinal) * 0.52f + abs(smoothTurn) * 0.66f + abs(smoothLateral) * 0.28f)
                .coerceIn(0f, 1f)
        val targetIntensity = (accelIntensity * 0.72f + ((accRms / 2.2f).coerceIn(0f, 1f)) * 0.28f)
            .coerceIn(0f, 1f)

        val smooth = 1f - exp(-dt / 0.12f)
        smoothCueX += (cueXRaw - smoothCueX) * smooth
        smoothCueY += (cueYRaw - smoothCueY) * smooth
        smoothIntensity += (targetIntensity - smoothIntensity) * smooth

        val state = MotionCueState(
            isVehicleLikely = isVehicleLikely,
            confidence = confidence,
            cueX = smoothCueX,
            cueY = smoothCueY,
            turnRate = smoothTurn,
            intensity = smoothIntensity,
            speedEstimate = speedEstimate,
            linearAx = linear[0],
            linearAy = linear[1],
            linearAz = linear[2],
            omegaZ = latestGyro[2],
            timestampMs = ts / 1_000_000L,
            longitudinalAccel = smoothLongitudinal * 3.1f,
            lateralAccel = smoothLateral * 2.4f
        )

        mainHandler.post { onState(state) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun pushWindow(acc: Float, gyro: Float, jerk: Float) {
        accWindow[windowIndex] = acc
        gyroWindow[windowIndex] = gyro
        jerkWindow[windowIndex] = jerk

        windowIndex = (windowIndex + 1) % windowSize
        windowCount = (windowCount + 1).coerceAtMost(windowSize)
    }

    private fun rms(window: FloatArray, validSize: Int): Float {
        if (validSize <= 0) return 0f
        var sum = 0f
        for (i in 0 until validSize) {
            val v = window[i]
            sum += v * v
        }
        return sqrt(sum / validSize)
    }

    private fun magnitude(x: Float, y: Float, z: Float): Float = sqrt((x * x) + (y * y) + (z * z))

    private fun resetState() {
        lastAccelNs = 0L
        gravityInit = false
        lastAccMag = 0f

        for (i in 0..2) {
            gravity[i] = 0f
            linear[i] = 0f
            latestGyro[i] = 0f
        }

        for (i in 0 until windowSize) {
            accWindow[i] = 0f
            gyroWindow[i] = 0f
            jerkWindow[i] = 0f
        }

        windowIndex = 0
        windowCount = 0

        speedX = 0f
        speedY = 0f
        confidence = 0f

        smoothCueX = 0f
        smoothCueY = 0f
        smoothIntensity = 0f
        smoothTurn = 0f
        smoothLongitudinal = 0f
        smoothLateral = 0f
    }
}
