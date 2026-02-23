package com.antcarsickness

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sign

data class DotSensorData(
    val ax: Float,
    val ay: Float,
    val az: Float,
    val omegaZ: Float,
    val timestampMs: Long
)

data class DotState(
    val x: Float,
    val y: Float,
    val rotation: Float,
    val scale: Float,
    val opacity: Float
)

data class DotMotionParams(
    val alphaFast: Float = 0.65f,
    val alphaSlow: Float = 0.12f,
    val blendFactor: Float = 0.35f,
    val alphaHigh: Float = 0.68f,
    val k_v: Float = 0.9f,
    val k_turn: Float = 0.40f,
    val maxForwardOffset: Float = 0.12f,
    val maxLateralOffset: Float = 0.08f,
    val accelStartThreshold: Float = 0.6f,
    val jerkThreshold: Float = 0.95f,
    val impulseMag: Float = 0.020f,
    val turnSmooth: Float = 5f,
    val predictK: Float = 0.28f,
    val leadDurationMs: Int = 200,
    val maxLeadRatio: Float = 0.10f
)

class DotMotionModule(
    private var params: DotMotionParams = DotMotionParams()
) {
    private var screenW = 1f
    private var screenH = 1f
    private var reducedMotion = false

    private var initialized = false
    private var fastForward = 0f
    private var slowForward = 0f
    private var fastLateral = 0f
    private var slowLateral = 0f
    private var prevForwardFiltered = 0f
    private var prevForwardRaw = 0f
    private var prevLateralRaw = 0f
    private var highForward = 0f
    private var highLateral = 0f

    private var vForward = 0f
    private var vLat = 0f
    private var forwardOffset = 0f
    private var lateralOffset = 0f
    private var arcXOffset = 0f
    private var impulseOffset = 0f

    private var leadTimer = 0f
    private var leadDuration = 0.20f
    private var leadAmplitude = 0f
    private var leadOffset = 0f

    private var opacityBoostTimer = 0f
    private var opacityBoostAmp = 0f

    private val sensorBuffer = ArrayDeque<DotSensorData>()
    private var lastSensor: DotSensorData? = null

    fun setParams(newParams: DotMotionParams) {
        params = newParams
    }

    fun setViewport(width: Float, height: Float) {
        screenW = max(1f, width)
        screenH = max(1f, height)
    }

    fun setReducedMotion(enabled: Boolean) {
        reducedMotion = enabled
    }

    fun enqueueSensor(sensorData: DotSensorData) {
        sensorBuffer.addLast(sensorData)
        while (sensorBuffer.size > 64) {
            sensorBuffer.removeFirst()
        }
        lastSensor = sensorData
    }

    fun sampleAligned(targetTimestampMs: Long): DotSensorData? {
        if (sensorBuffer.isEmpty()) {
            return lastSensor
        }
        while (sensorBuffer.size >= 2) {
            val first = sensorBuffer.first()
            val second = sensorBuffer.elementAt(1)
            if (second.timestampMs <= targetTimestampMs) {
                sensorBuffer.removeFirst()
            } else {
                break
            }
        }
        val a = sensorBuffer.firstOrNull() ?: return lastSensor
        val b = sensorBuffer.elementAtOrNull(1)
        if (b == null || targetTimestampMs <= a.timestampMs) {
            return a
        }
        val span = (b.timestampMs - a.timestampMs).toFloat().coerceAtLeast(1f)
        val t = ((targetTimestampMs - a.timestampMs) / span).coerceIn(0f, 1f)
        return DotSensorData(
            ax = lerp(a.ax, b.ax, t),
            ay = lerp(a.ay, b.ay, t),
            az = lerp(a.az, b.az, t),
            omegaZ = lerp(a.omegaZ, b.omegaZ, t),
            timestampMs = targetTimestampMs
        )
    }

    fun updateDotMotion(sensorData: DotSensorData, dtInput: Float): DotState {
        val dt = dtInput.coerceIn(0.001f, 0.05f)

        if (!initialized) {
            initialized = true
            val forward = -sensorData.ay
            val lateral = sensorData.ax + sensorData.omegaZ * 0.35f
            fastForward = forward
            slowForward = forward
            fastLateral = lateral
            slowLateral = lateral
            prevForwardFiltered = forward
            prevForwardRaw = forward
            prevLateralRaw = lateral
        }

        // Forward axis convention: a_forward = -ay. Positive value means acceleration.
        val aForwardRaw = -sensorData.ay
        val lateralRaw = sensorData.ax + sensorData.omegaZ * 0.35f

        // Dual-path filtering: fast branch improves responsiveness, slow branch stabilizes noise.
        val alphaFast = params.alphaFast.coerceIn(0.45f, 0.90f)
        val alphaSlow = params.alphaSlow.coerceIn(0.05f, 0.30f)
        fastForward = alphaFast * aForwardRaw + (1f - alphaFast) * fastForward
        slowForward = alphaSlow * aForwardRaw + (1f - alphaSlow) * slowForward
        fastLateral = alphaFast * lateralRaw + (1f - alphaFast) * fastLateral
        slowLateral = alphaSlow * lateralRaw + (1f - alphaSlow) * slowLateral

        val blend = params.blendFactor.coerceIn(0f, 1f)
        val aForwardFiltered = lerp(fastForward, slowForward, blend)
        val lateralFiltered = lerp(fastLateral, slowLateral, blend)

        // High-pass branch (~6-10Hz equivalent) extracts short bump/jerk transients.
        highForward = params.alphaHigh * (highForward + aForwardRaw - prevForwardRaw)
        highLateral = params.alphaHigh * (highLateral + lateralRaw - prevLateralRaw)
        prevForwardRaw = aForwardRaw
        prevLateralRaw = lateralRaw

        vForward += aForwardFiltered * dt
        vForward *= exp(-params.k_v * dt)

        val forwardStepPx = vForward * dt * screenH * 0.105f
        forwardOffset += forwardStepPx
        val maxForwardPx = params.maxForwardOffset * screenH
        forwardOffset = forwardOffset.coerceIn(-maxForwardPx, maxForwardPx)

        // Predictive lead: short anticipatory displacement on acceleration spikes.
        val forwardDelta = aForwardFiltered - prevForwardFiltered
        prevForwardFiltered = aForwardFiltered
        val maxLeadPx = params.maxLeadRatio.coerceIn(0.02f, 0.16f) * minOf(screenW, screenH)
        val leadThreshold = params.accelStartThreshold.coerceAtLeast(0.05f)
        if (abs(aForwardFiltered) > leadThreshold && abs(forwardDelta) > leadThreshold * 0.25f && leadTimer <= 0f) {
            leadDuration = (params.leadDurationMs / 1000f).coerceIn(0.12f, 0.28f)
            leadTimer = leadDuration
            leadAmplitude = (params.predictK * aForwardFiltered * screenH * 0.14f)
                .coerceIn(-maxLeadPx, maxLeadPx)
        }

        if (leadTimer > 0f) {
            val progress = (1f - (leadTimer / leadDuration)).coerceIn(0f, 1f)
            // Ease-out keeps immediate cue visible and decays it smoothly.
            val easeOut = 1f - (1f - progress) * (1f - progress)
            leadOffset = leadAmplitude * (1f - easeOut)
            leadTimer = (leadTimer - dt).coerceAtLeast(0f)
        } else {
            leadOffset *= exp(-12f * dt)
        }

        vLat += lateralFiltered * dt
        vLat *= exp(-params.k_v * 0.85f * dt)
        val maxLateralPx = params.maxLateralOffset * screenW
        val lateralTarget = (params.k_turn * vLat * screenW * 0.34f).coerceIn(-maxLateralPx, maxLateralPx)
        // turnSmooth lowered from old values so turning follows faster.
        val lateralLerp = 1f - exp(-params.turnSmooth.coerceIn(3f, 10f) * dt)
        lateralOffset = lerp(lateralOffset, lateralTarget, lateralLerp)

        // Arc coupling creates curved trajectory during combined forward and turn motion.
        arcXOffset += vForward * dt * screenW * 0.018f
        arcXOffset *= exp(-8f * dt)

        // High-pass jerk impulse with exponential decay.
        val jerkSignal = 0.72f * highForward + 0.28f * highLateral
        if (abs(jerkSignal) > params.jerkThreshold) {
            impulseOffset += sign(jerkSignal) * params.impulseMag * screenH
        }
        impulseOffset *= exp(-16f * dt)

        val motionEnergy = (
            abs(aForwardFiltered) * 0.24f +
                abs(lateralFiltered) * 0.18f +
                abs(sensorData.omegaZ) * 0.42f
            ).coerceIn(0f, 1f)

        if (motionEnergy > 0.12f) {
            opacityBoostTimer = 0.28f
            opacityBoostAmp = (0.05f + 0.07f * motionEnergy).coerceIn(0.05f, 0.12f)
        }
        val opacityBoost = if (opacityBoostTimer > 0f) {
            val t = (opacityBoostTimer / 0.28f).coerceIn(0f, 1f)
            opacityBoostTimer = (opacityBoostTimer - dt).coerceAtLeast(0f)
            opacityBoostAmp * t
        } else {
            0f
        }

        var x = lateralOffset + arcXOffset
        var y = forwardOffset + leadOffset + impulseOffset
        var rotation = ((x / max(1f, maxLateralPx)) * 10f + sensorData.omegaZ * 4.5f).coerceIn(-14f, 14f)
        var scale = (1f + motionEnergy * 0.22f + abs(impulseOffset / max(1f, maxForwardPx)) * 0.12f)
            .coerceIn(0.88f, 1.30f)
        var opacity = (0.10f + opacityBoost).coerceIn(0.08f, 0.22f)

        if (reducedMotion) {
            x *= 0.1f
            y *= 0.1f
            rotation *= 0.1f
            scale = 1f + (scale - 1f) * 0.1f
            opacity = (0.09f + opacityBoost * 0.18f).coerceIn(0.08f, 0.14f)
        }

        x = x.coerceIn(-maxLateralPx, maxLateralPx)
        y = y.coerceIn(-maxForwardPx, maxForwardPx)
        rotation = rotation.coerceIn(-20f, 20f)
        scale = scale.coerceIn(0.85f, 1.35f)
        opacity = opacity.coerceIn(0.05f, 0.30f)

        return DotState(
            x = x,
            y = y,
            rotation = rotation,
            scale = scale,
            opacity = opacity
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
