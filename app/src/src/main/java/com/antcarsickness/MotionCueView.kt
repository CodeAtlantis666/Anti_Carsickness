package com.antcarsickness

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class MotionCueView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    enum class RenderMode { OFF, AUTO, ON }
    enum class Pattern { REGULAR, DYNAMIC }

    data class ColorRuntimeDebug(
        val quickLuminance: Float,
        val chosenStyle: String,
        val contrastValue: Float,
        val latencyMs: Float,
        val metContrast: Boolean
    )

    private data class DotAnchor(
        val edge: Edge,
        val t: Float,
        val phase: Float,
        val lane: Lane = Lane.OUTER,
        val accelOnly: Boolean = false,
        val rollOnly: Boolean = false
    )
    private enum class Edge { LEFT, RIGHT }
    private enum class Lane { OUTER, INNER }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val dots = mutableListOf<DotAnchor>()

    private var renderMode: RenderMode = RenderMode.AUTO
    private var pattern: Pattern = Pattern.REGULAR
    private var largerDots = false
    private var moreDots = false
    private var subtleMode = false
    private var dotScale = 1f
    private var autoTone = true
    private var reducedMotion = false

    private var targetVisible = false
    private var targetX = 0f
    private var targetY = 0f
    private var targetIntensity = 0f
    private var targetLongitudinal = 0f

    private var displayVisible = 0f
    private var displayX = 0f
    private var displayY = 0f
    private var displayIntensity = 0f
    private var displayLongitudinal = 0f
    private var sideRollPhase = 0f
    private var sideRollVelocity = 0f
    private var lastRollDirection = 1f
    private var rollActiveByAccel = false
    private var rollAboveThresholdSec = 0f
    private var rollBelowThresholdSec = 0f
    private var wasAccelRolling = false
    private var hasSettleTarget = false
    private var settleTargetPhase = 0f
    private var maxRollSpeedThisTrigger = 0f

    private var lastFrameNs = 0L
    private var frameDt = 0.016f

    private var colorSampler: ((Float, Float, Float, Float) -> Int?)? = null
    private var colorDebugListener: ((ColorRuntimeDebug) -> Unit)? = null

    private var colorTransitionMs = 180
    private var sampleIntervalNs = 50_000_000L // 20Hz
    private var toneThreshold = 165f
    private var toneFuzzyBand = 15f
    private var toneCooldownMs = 120L
    private var lumaSmoothingAlpha = 0.30f
    private var quickLightThreshold = 0.64f
    private var quickDarkThreshold = 0.36f

    // 0 = dark dot (black), 1 = light dot (white)
    private var dotToneTarget = IntArray(0)
    private var dotToneCurrent = FloatArray(0)
    private var dotLumaSmooth = FloatArray(0)
    private var dotLumaReady = BooleanArray(0)
    private var dotLastSampleNs = LongArray(0)
    private var dotLastSwitchMs = LongArray(0)

    private var lastDebug = ColorRuntimeDebug(
        quickLuminance = 0f,
        chosenStyle = "unknown",
        contrastValue = 1f,
        latencyMs = 0f,
        metContrast = false
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        runCatching {
            if (lastFrameNs == 0L) {
                lastFrameNs = frameTimeNanos
            }
            val dt = ((frameTimeNanos - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNs = frameTimeNanos
            frameDt = dt

            val posTau = if (reducedMotion) 0.16f else 0.11f
            val alphaTau = 0.12f

            val posSmoothing = 1f - exp(-dt / posTau)
            val alphaSmoothing = 1f - exp(-dt / alphaTau)

            displayX += (targetX - displayX) * posSmoothing
            displayY += (targetY - displayY) * posSmoothing
            displayIntensity += (targetIntensity - displayIntensity) * posSmoothing
            displayLongitudinal += (targetLongitudinal - displayLongitudinal) * posSmoothing
            val accelAbs = abs(displayLongitudinal)
            val rollStartThreshold = 0.28f
            val rollStopThreshold = 0.12f
            val accelSign = when {
                displayLongitudinal > 0.03f -> 1f
                displayLongitudinal < -0.03f -> -1f
                else -> 0f
            }
            val directionChangedByAccel =
                accelSign != 0f && accelSign != lastRollDirection && accelAbs >= rollStartThreshold
            if (directionChangedByAccel || (hasSettleTarget && accelAbs >= rollStartThreshold)) {
                // Acceleration change has higher priority than settle animation.
                rollActiveByAccel = true
                rollAboveThresholdSec = 0f
                rollBelowThresholdSec = 0f
                hasSettleTarget = false
            } else {
                if (rollActiveByAccel) {
                    if (accelAbs < rollStopThreshold) {
                        rollBelowThresholdSec += dt
                        if (rollBelowThresholdSec >= 0.35f) {
                            rollActiveByAccel = false
                            rollBelowThresholdSec = 0f
                        }
                    } else {
                        rollBelowThresholdSec = 0f
                    }
                } else {
                    if (accelAbs >= rollStartThreshold) {
                        rollAboveThresholdSec += dt
                        if (rollAboveThresholdSec >= 0.12f) {
                            rollActiveByAccel = true
                            rollAboveThresholdSec = 0f
                            rollBelowThresholdSec = 0f
                        }
                    } else {
                        rollAboveThresholdSec = 0f
                    }
                }
            }
            val accelRolling = rollActiveByAccel
            if (accelRolling) {
                // Keep rolling continuously while acceleration is still detected.
                if (!wasAccelRolling) {
                    maxRollSpeedThisTrigger = abs(sideRollVelocity)
                }
                val targetVel = displayLongitudinal * 0.78f
                if (abs(targetVel) > 0.01f) {
                    lastRollDirection = if (targetVel >= 0f) 1f else -1f
                }
                val directionChangedNow = targetVel * sideRollVelocity < 0f && abs(targetVel) >= 0.05f
                val velTau = if (directionChangedNow) 0.055f else 0.10f
                val velSmoothing = 1f - exp(-dt / velTau)
                sideRollVelocity += (targetVel - sideRollVelocity) * velSmoothing
                sideRollPhase = wrapUnit(sideRollPhase + sideRollVelocity * dt)
                maxRollSpeedThisTrigger = max(maxRollSpeedThisTrigger, abs(sideRollVelocity))
                hasSettleTarget = false
            } else {
                // On stop: keep SAME direction and settle to the nearest main-dot overlap anchor.
                if (wasAccelRolling) {
                    settleTargetPhase = nearestMainDotOverlapPhase(sideRollPhase, lastRollDirection)
                    hasSettleTarget = true
                }
                if (hasSettleTarget) {
                    val dist = directionalPhaseDistance(sideRollPhase, settleTargetPhase, lastRollDirection)
                    if (dist <= 0.0015f) {
                        sideRollPhase = settleTargetPhase
                        sideRollVelocity = 0f
                        hasSettleTarget = false
                        maxRollSpeedThisTrigger = 0f
                    } else {
                        // Slow recovery and never exceed max speed seen during this trigger.
                        val speedCeil = if (maxRollSpeedThisTrigger > 0.001f) {
                            maxRollSpeedThisTrigger
                        } else {
                            0.06f
                        }
                        val settleSpeed = min(dist / 0.95f, speedCeil * 0.55f).coerceAtLeast(0.01f)
                        sideRollVelocity = lastRollDirection * min(settleSpeed, speedCeil)
                        sideRollPhase = wrapUnit(sideRollPhase + sideRollVelocity * dt)
                    }
                } else {
                    sideRollVelocity = 0f
                }
            }
            wasAccelRolling = accelRolling

            val alphaTarget = if (targetVisible) 1f else 0f
            displayVisible += (alphaTarget - displayVisible) * alphaSmoothing

            if (displayVisible > 0.005f) {
                invalidate()
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun setRenderMode(mode: RenderMode) {
        renderMode = mode
    }

    fun setPattern(value: Pattern) {
        pattern = value
    }

    fun setLargerDots(enabled: Boolean) {
        largerDots = enabled
    }

    fun setMoreDots(enabled: Boolean) {
        moreDots = enabled
        rebuildDots(width, height)
    }

    fun setSubtleMode(enabled: Boolean) {
        subtleMode = enabled
    }

    fun setDotScale(scale: Float) {
        dotScale = scale.coerceIn(0.6f, 1.8f)
    }

    fun setAutoTone(enabled: Boolean) {
        autoTone = enabled
    }

    fun setReducedMotion(enabled: Boolean) {
        reducedMotion = enabled
    }

    fun setMinContrast(minContrast: Float) {
        // v0.2.3 uses binary black/white contrast based on BT.601 threshold.
        // Keep this setter for config compatibility.
    }

    fun setHighContrastMode(enabled: Boolean) {
        // No-op for v0.2.3 compatibility.
    }

    fun setColorTransitionMs(ms: Int) {
        colorTransitionMs = ms.coerceIn(80, 300)
    }

    fun setQuickTransitionMs(ms: Int) {
        colorTransitionMs = ms.coerceIn(80, 300)
    }

    fun setQuickLuminanceThresholdLight(value: Float) {
        quickLightThreshold = value.coerceIn(0.40f, 0.95f)
        syncToneThresholds()
    }

    fun setQuickLuminanceThresholdDark(value: Float) {
        quickDarkThreshold = value.coerceIn(0.05f, 0.60f)
        syncToneThresholds()
    }

    fun setFineSampleHz(hz: Int) {
        val clamped = hz.coerceIn(5, 50)
        sampleIntervalNs = (1_000_000_000f / clamped).toLong()
    }

    fun setMotionAlphaFast(value: Float) {
        lumaSmoothingAlpha = value.coerceIn(0.05f, 0.90f)
    }
    fun setMotionAlphaSlow(value: Float) = Unit
    fun setMotionDampingKv(value: Float) = Unit
    fun setMotionTurnSmooth(value: Float) = Unit
    fun setMotionPredictK(value: Float) = Unit

    fun setColorSampler(sampler: ((Float, Float, Float, Float) -> Int?)?) {
        colorSampler = sampler
    }

    fun setColorDebugListener(listener: ((ColorRuntimeDebug) -> Unit)?) {
        colorDebugListener = listener
    }

    fun getLastColorDebug(): ColorRuntimeDebug = lastDebug

    fun updateMotion(state: MotionCueState) {
        targetVisible = when (renderMode) {
            RenderMode.OFF -> false
            RenderMode.ON -> true
            RenderMode.AUTO -> state.isVehicleLikely
        }

        val motionScale = if (reducedMotion) 0.2f else 1f
        val scale = if (pattern == Pattern.DYNAMIC) 1.15f else 1f

        targetX = (-state.cueX).coerceIn(-1f, 1f) * scale * motionScale
        targetY = (-state.cueY).coerceIn(-1f, 1f) * scale * motionScale
        targetIntensity = (state.intensity.coerceIn(0f, 1f) * motionScale).coerceIn(0f, 1f)
        targetLongitudinal = state.longitudinalAccel.coerceIn(-4f, 4f) / 4f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildDots(w, h)
    }

    private fun rebuildDots(w: Int, h: Int) {
        dots.clear()
        if (w <= 0 || h <= 0) return

        val sideCount = 3

        val mainTs = FloatArray(sideCount) { i -> (i + 1f) / (sideCount + 1f) }
        for (i in mainTs.indices) {
            val t = mainTs[i]
            dots += DotAnchor(Edge.LEFT, t, (i * 0.51f) % 1f)
            dots += DotAnchor(Edge.RIGHT, t, (i * 0.71f + 0.13f) % 1f)
        }
        // New side dots are placed at vertical bisectors between main dots,
        // plus bisectors between top/bottom edges and the nearest main dot.
        val accelTs = ArrayList<Float>(sideCount + 1)
        var prev = 0f
        for (t in mainTs) {
            accelTs += (prev + t) * 0.5f
            prev = t
        }
        accelTs += (prev + 1f) * 0.5f
        for (i in accelTs.indices) {
            val t = accelTs[i]
            dots += DotAnchor(Edge.LEFT, t, (i * 0.57f + 0.11f) % 1f, lane = Lane.INNER, accelOnly = true)
            dots += DotAnchor(Edge.RIGHT, t, (i * 0.67f + 0.29f) % 1f, lane = Lane.INNER, accelOnly = true)
        }
        // Roll seam filler for MAIN dots: prevents blank gap while rolling loops.
        dots += DotAnchor(Edge.LEFT, 0f, 0.19f, lane = Lane.OUTER, accelOnly = false, rollOnly = true)
        dots += DotAnchor(Edge.RIGHT, 0f, 0.31f, lane = Lane.OUTER, accelOnly = false, rollOnly = true)

        dotToneTarget = IntArray(dots.size) { 0 }
        dotToneCurrent = FloatArray(dots.size) { 0f }
        dotLumaSmooth = FloatArray(dots.size) { toneThreshold }
        dotLumaReady = BooleanArray(dots.size) { false }
        dotLastSampleNs = LongArray(dots.size) { 0L }
        dotLastSwitchMs = LongArray(dots.size) { 0L }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        runCatching {
            if (displayVisible <= 0.005f) return

            val widthF = width.toFloat()
            val heightF = height.toFloat()
            if (widthF <= 0f || heightF <= 0f) return

            val sideOuterX = widthF / 16f
            val sideInnerX = widthF * (3f / 16f)
            val marginY = dp(if (largerDots) 7f else 6f)
            val maxShift = dp(if (largerDots) 20f else 16f)
            val baseRadius = dp(if (largerDots) 5.2f else 3.8f) * dotScale
            val subtleRadiusScale = if (subtleMode) 0.72f else 1f
            val subtleAlphaScale = if (subtleMode) 0.62f else 1f
            val accelMag = abs(displayLongitudinal).coerceIn(0f, 1f)
            val phaseDist = min(sideRollPhase, 1f - sideRollPhase)
            val rollingByMomentum = abs(sideRollVelocity) >= 0.02f || phaseDist > 0.003f
            val accelLaneAlphaRaw = ((accelMag - 0.32f) / 0.26f).coerceIn(0f, 1f)
            val accelLaneAlpha = if (rollActiveByAccel) accelLaneAlphaRaw else 0f
            val rollActive = rollActiveByAccel || rollingByMomentum
            val laneSpanY = (heightF - marginY * 2f).coerceAtLeast(1f)
            val motionActivity = max(
                max(abs(displayX), abs(displayY)),
                max(displayIntensity, abs(displayLongitudinal))
            )

            val nowNs = System.nanoTime()
            val nowMs = nowNs / 1_000_000L
            val nowSec = nowNs / 1_000_000_000.0
            val dynamicWave = if (pattern == Pattern.DYNAMIC && motionActivity > 0.10f) {
                val waveGain = ((motionActivity - 0.10f) / 0.45f).coerceIn(0f, 1f)
                (sin(nowSec * 2.0 * PI) * 0.18 * waveGain).toFloat()
            } else {
                0f
            }

            val xShift = if (rollActive) 0f else (displayX + dynamicWave) * maxShift
            val yShift = if (rollActive) 0f else (displayY + dynamicWave * 0.6f) * maxShift

            val transitionTau = (colorTransitionMs / 1000f).coerceIn(0.08f, 0.30f)
            val toneLerp = 1f - exp(-frameDt / transitionTau)
            val turnStrength = abs(displayX).coerceIn(0f, 1f)
            val turnSign = when {
                displayX > 0.02f -> 1f
                displayX < -0.02f -> -1f
                else -> 0f
            }
            // Reintroduce side turning animation and blend it with current longitudinal rolling.
            // Positive turnSign drives both sides toward screen center with phase offsets.
            val turnInwardMax = dp(if (largerDots) 14f else 10f)
            val turnVerticalMax = dp(if (largerDots) 18f else 14f)
            val turnWaveAmp = dp(if (largerDots) 2.4f else 1.8f)

            for ((i, dot) in dots.withIndex()) {
                if (dot.accelOnly) {
                    if (!rollActiveByAccel || accelLaneAlpha <= 0.001f) {
                        continue
                    }
                }
                if (dot.rollOnly && !rollActive) {
                    continue
                }

                val (baseX, baseY) = when (dot.edge) {
                    Edge.LEFT -> {
                        val x = if (dot.lane == Lane.INNER) sideInnerX else sideOuterX
                        Pair(x, marginY + dot.t * (heightF - marginY * 2f))
                    }
                    Edge.RIGHT -> {
                        val x = if (dot.lane == Lane.INNER) (widthF - sideInnerX) else (widthF - sideOuterX)
                        Pair(x, marginY + dot.t * (heightF - marginY * 2f))
                    }
                }

                val dotX: Float
                val dotY: Float
                when (dot.edge) {
                    Edge.LEFT, Edge.RIGHT -> {
                        val sideDir = if (dot.edge == Edge.LEFT) 1f else -1f
                        val centerBias = ((dot.t - 0.5f) * 2f).coerceIn(-1f, 1f)
                        val laneFactor = if (dot.lane == Lane.INNER) 0.86f else 1f
                        val turnInward = turnSign * turnStrength * turnInwardMax * sideDir * laneFactor
                        val turnPhaseOffset = turnSign * turnStrength * 0.06f * sideDir * laneFactor
                        val turnWave = sin((nowSec * 3.2 + dot.phase * 2.1) * PI).toFloat() * turnWaveAmp
                        val turnVertical = (turnSign * centerBias * turnVerticalMax + turnWave) * turnStrength

                        if (rollActive) {
                            dotX = baseX + turnInward
                            val rolledT = wrapUnit(dot.t + sideRollPhase + turnPhaseOffset)
                            dotY = marginY + rolledT * laneSpanY + turnVertical * 0.32f
                        } else {
                            dotX = baseX + xShift * 0.32f + turnInward
                            dotY = baseY + yShift * 1.08f + turnVertical * 0.72f
                        }
                    }
                }

                val cx = dotX.coerceIn(0f, widthF)
                val cy = dotY.coerceIn(0f, heightF)
                val pulse = 0.78f + 0.22f * sin((nowSec * 4.2 + dot.phase * 2.0) * PI).toFloat()
                val accelRadiusScale = if (dot.accelOnly) 0.7f else 1f
                val pulseGain = ((motionActivity - 0.08f) / 0.35f).coerceIn(0f, 1f)
                val radius =
                    baseRadius * accelRadiusScale * subtleRadiusScale *
                        (1f + pulseGain * displayIntensity * 0.9f * pulse)

                if (autoTone) {
                    maybeUpdateTone(i, dot.edge, cx, cy, radius, widthF, heightF, nowNs, nowMs)
                } else {
                    dotToneTarget[i] = 0
                }

                dotToneCurrent[i] += (dotToneTarget[i].toFloat() - dotToneCurrent[i]) * toneLerp
                val tone = dotToneCurrent[i].coerceIn(0f, 1f)

                val visibleAlpha = displayVisible * if (dot.accelOnly) accelLaneAlpha else 1f
                val alpha = (255f * 0.90f * visibleAlpha * subtleAlphaScale).toInt().coerceIn(0, 255)
                if (alpha <= 0) continue

                val shade = if (tone >= 0.5f) 255 else 0
                fillPaint.color = Color.rgb(shade, shade, shade)
                fillPaint.alpha = alpha
                canvas.drawCircle(cx, cy, max(1f, radius), fillPaint)

                if (i == 0) {
                    val l = dotLumaSmooth[i] / 255f
                    val contrastBlack = DotColorSystem.contrastRatio(Color.BLACK, Color.rgb(shade, shade, shade))
                    val contrastWhite = DotColorSystem.contrastRatio(Color.WHITE, Color.rgb(shade, shade, shade))
                    val contrast = max(contrastBlack, contrastWhite)
                    lastDebug = ColorRuntimeDebug(
                        quickLuminance = l,
                        chosenStyle = if (tone >= 0.5f) "light-dot" else "dark-dot",
                        contrastValue = contrast,
                        latencyMs = 0f,
                        metContrast = contrast >= 3.0f
                    )
                    colorDebugListener?.invoke(lastDebug)
                }
            }
        }
    }

    private fun maybeUpdateTone(
        index: Int,
        edge: Edge,
        x: Float,
        y: Float,
        radius: Float,
        screenW: Float,
        screenH: Float,
        nowNs: Long,
        nowMs: Long
    ) {
        if (nowNs - dotLastSampleNs[index] < sampleIntervalNs) {
            return
        }
        dotLastSampleNs[index] = nowNs

        val sampleColor = sampleAroundDot(edge, x, y, radius, screenW, screenH) ?: return
        val luma = bt601Luma(sampleColor)

        if (!dotLumaReady[index]) {
            dotLumaReady[index] = true
            dotLumaSmooth[index] = luma
        } else {
            val a = lumaSmoothingAlpha.coerceIn(0.05f, 0.90f)
            dotLumaSmooth[index] = dotLumaSmooth[index] + (luma - dotLumaSmooth[index]) * a
        }

        val lumaFiltered = dotLumaSmooth[index]
        val upper = (toneThreshold + toneFuzzyBand).coerceIn(0f, 255f)
        val lower = (toneThreshold - toneFuzzyBand).coerceIn(0f, 255f)

        val current = dotToneTarget[index]
        val desired = when (current) {
            0 -> if (lumaFiltered < lower) 1 else 0
            1 -> if (lumaFiltered > upper) 0 else 1
            else -> if (lumaFiltered >= toneThreshold) 0 else 1
        }

        if (desired != current && (nowMs - dotLastSwitchMs[index]) >= toneCooldownMs) {
            dotToneTarget[index] = desired
            dotLastSwitchMs[index] = nowMs
        }
    }

    private fun sampleAroundDot(
        edge: Edge,
        x: Float,
        y: Float,
        radius: Float,
        screenW: Float,
        screenH: Float
    ): Int? {
        val sampler = colorSampler ?: return null
        val inward = (radius * 2f + dp(8f)).coerceAtLeast(dp(6f))
        val cx = when (edge) {
            Edge.LEFT -> x + inward
            Edge.RIGHT -> x - inward
        }.coerceIn(0f, screenW)
        val cy = y.coerceIn(0f, screenH)

        val d = (radius + 2f).coerceAtLeast(1f)
        val points = arrayOf(
            Pair(cx, cy),
            Pair(cx + d, cy),
            Pair(cx - d, cy),
            Pair(cx, cy + d),
            Pair(cx, cy - d)
        )

        var sumR = 0
        var sumG = 0
        var sumB = 0
        var count = 0
        for (p in points) {
            val sx = p.first.coerceIn(0f, screenW)
            val sy = p.second.coerceIn(0f, screenH)
            val c = runCatching { sampler(sx, sy, screenW, screenH) }.getOrNull() ?: continue
            sumR += Color.red(c)
            sumG += Color.green(c)
            sumB += Color.blue(c)
            count += 1
        }
        if (count <= 0) return null
        return Color.rgb(sumR / count, sumG / count, sumB / count)
    }

    private fun bt601Luma(color: Int): Float {
        val r = Color.red(color).toFloat()
        val g = Color.green(color).toFloat()
        val b = Color.blue(color).toFloat()
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun syncToneThresholds() {
        val light = quickLightThreshold
        val dark = quickDarkThreshold
        val hi = max(light, dark)
        val lo = if (light >= dark) dark else light
        toneThreshold = (((hi + lo) * 0.5f) * 255f).coerceIn(60f, 200f)
        toneFuzzyBand = (((hi - lo) * 0.5f) * 255f).coerceIn(6f, 40f)
    }

    private fun nearestMainDotOverlapPhase(current: Float, direction: Float): Float {
        val anchors = floatArrayOf(0f, 0.25f, 0.5f, 0.75f)
        var best = anchors[0]
        var bestDist = Float.MAX_VALUE
        for (a in anchors) {
            val d = directionalPhaseDistance(current, a, direction)
            if (d < bestDist) {
                bestDist = d
                best = a
            }
        }
        return best
    }

    private fun directionalPhaseDistance(current: Float, target: Float, direction: Float): Float {
        val c = wrapUnit(current)
        val t = wrapUnit(target)
        return if (direction >= 0f) {
            wrapUnit(t - c)
        } else {
            wrapUnit(c - t)
        }
    }

    private fun wrapUnit(value: Float): Float {
        var wrapped = value % 1f
        if (wrapped < 0f) wrapped += 1f
        return wrapped
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
