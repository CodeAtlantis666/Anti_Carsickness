package com.antcarsickness

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class DotVisualState {
    IDLE,
    ACCELERATING,
    BRAKING,
    TURNING,
    CRUISING,
    JERK
}

enum class DotStyleSource {
    QUICK_LIGHT,
    QUICK_DARK,
    FINE
}

data class SampledColor(
    val r: Int,
    val g: Int,
    val b: Int,
    val a: Int = 255
) {
    fun toColorInt(): Int {
        return Color.argb(
            a.coerceIn(0, 255),
            r.coerceIn(0, 255),
            g.coerceIn(0, 255),
            b.coerceIn(0, 255)
        )
    }
}

data class QuickSample(
    val rgb: Int,
    val luminance: Float
)

data class DotHaloStyle(
    val color: Int,
    val sizePx: Float,
    val alpha: Float
)

data class DotStyle(
    val fill: Int,
    val stroke: Int?,
    val strokeWidthPx: Float,
    val halo: DotHaloStyle?,
    val metContrast: Boolean,
    val contrastValue: Float,
    val source: DotStyleSource = DotStyleSource.FINE
)

data class DotColorParams(
    val minContrast: Float = 4.5f,
    val highContrastMode: Boolean = false,
    val reducedMotion: Boolean = false,
    val sampleRadiusPx: Float = 24f,
    val maxStrokePx: Float = 4f,
    val minStrokePx: Float = 2f,
    val haloMinPx: Float = 4f,
    val haloMaxPx: Float = 10f,
    val haloAlpha: Float = 0.6f,
    val transitionMs: Int = 180,
    val quickLuminanceThresholdLight: Float = 0.78f,
    val quickLuminanceThresholdDark: Float = 0.22f,
    val quickThresholdFuzzyBand: Float = 0.04f,
    val quickLumaSmoothingAlpha: Float = 0.35f,
    val quickTransitionMs: Int = 70,
    val quickCooldownMs: Int = 120,
    val fineSampleHz: Int = 20,
    val darkQuickFill: Int = 0xFF111111.toInt(),
    val lightQuickFill: Int = Color.WHITE,
    val preferredColorsPerState: Map<DotVisualState, List<Int>> = defaultPalette()
) {
    companion object {
        fun defaultPalette(): Map<DotVisualState, List<Int>> {
            return mapOf(
                DotVisualState.ACCELERATING to listOf(
                    Color.parseColor("#FFD60A"),
                    Color.parseColor("#FFDE59"),
                    Color.parseColor("#00CFFF")
                ),
                DotVisualState.BRAKING to listOf(
                    Color.parseColor("#FF3B30"),
                    Color.parseColor("#FF9500"),
                    Color.parseColor("#FF6B6B")
                ),
                DotVisualState.TURNING to listOf(
                    Color.parseColor("#34C759"),
                    Color.parseColor("#5AC8FA"),
                    Color.parseColor("#AF52DE")
                ),
                DotVisualState.CRUISING to listOf(
                    Color.parseColor("#FFFFFF"),
                    Color.parseColor("#E5E5EA"),
                    Color.parseColor("#F2F2F7")
                ),
                DotVisualState.IDLE to listOf(
                    Color.parseColor("#FFFFFF"),
                    Color.parseColor("#D1D1D6")
                ),
                DotVisualState.JERK to listOf(
                    Color.parseColor("#FFCC00"),
                    Color.parseColor("#FF3B30")
                )
            )
        }
    }
}

data class DotStyleTransitionState(
    var current: DotStyle = DotStyle(
        fill = Color.WHITE,
        stroke = null,
        strokeWidthPx = 0f,
        halo = null,
        metContrast = true,
        contrastValue = 21f
    ),
    var target: DotStyle = current,
    var from: DotStyle = current,
    var elapsedMs: Float = 0f,
    var durationMs: Float = 180f
)

data class DotRenderStyle(
    val fill: Int,
    val stroke: Int?,
    val strokeWidthPx: Float,
    val haloColor: Int?,
    val haloSizePx: Float,
    val haloAlpha: Float
)

object DotColorSystem {

    /**
     * Weighted ring sampling around a dot.
     * This is the detailed path used by WCAG-accurate color selection.
     */
    fun sampleBackground(
        x: Float,
        y: Float,
        radiusPx: Float,
        screenW: Float,
        screenH: Float,
        sampler: (Float, Float, Float, Float) -> Int?
    ): SampledColor? {
        val points = ArrayList<Pair<Pair<Float, Float>, Float>>(24)
        points += Pair(Pair(x, y), 3f)

        for (i in 0 until 8) {
            val a = (Math.PI * 2.0 * i / 8.0).toFloat()
            val px = x + kotlin.math.cos(a) * (radiusPx * 0.55f)
            val py = y + kotlin.math.sin(a) * (radiusPx * 0.55f)
            points += Pair(Pair(px, py), 2f)
        }

        for (i in 0 until 12) {
            val a = (Math.PI * 2.0 * i / 12.0).toFloat()
            val px = x + kotlin.math.cos(a) * radiusPx
            val py = y + kotlin.math.sin(a) * radiusPx
            points += Pair(Pair(px, py), 1f)
        }

        var sumR = 0f
        var sumG = 0f
        var sumB = 0f
        var sumA = 0f
        var wSum = 0f

        for ((pos, w) in points) {
            val sx = pos.first.coerceIn(0f, screenW)
            val sy = pos.second.coerceIn(0f, screenH)
            val c = sampler(sx, sy, screenW, screenH) ?: continue
            sumR += Color.red(c) * w
            sumG += Color.green(c) * w
            sumB += Color.blue(c) * w
            sumA += Color.alpha(c) * w
            wSum += w
        }
        if (wSum <= 0f) return null

        return SampledColor(
            r = (sumR / wSum).toInt().coerceIn(0, 255),
            g = (sumG / wSum).toInt().coerceIn(0, 255),
            b = (sumB / wSum).toInt().coerceIn(0, 255),
            a = (sumA / wSum).toInt().coerceIn(0, 255)
        )
    }

    /**
     * Fast path sampling for immediate visibility.
     * Uses a 3x3 neighborhood around the dot center and a lightweight luminance estimate.
     */
    fun sampleQuickBackground(
        x: Float,
        y: Float,
        stepPx: Float,
        screenW: Float,
        screenH: Float,
        sampler: (Float, Float, Float, Float) -> Int?
    ): QuickSample? {
        val step = stepPx.coerceAtLeast(0.1f)
        var sumR = 0f
        var sumG = 0f
        var sumB = 0f
        var count = 0

        for (dy in -1..1) {
            for (dx in -1..1) {
                val sx = (x + dx * step).coerceIn(0f, screenW)
                val sy = (y + dy * step).coerceIn(0f, screenH)
                val c = sampler(sx, sy, screenW, screenH) ?: continue
                sumR += Color.red(c)
                sumG += Color.green(c)
                sumB += Color.blue(c)
                count += 1
            }
        }

        if (count <= 0) return null

        val r = (sumR / count).toInt().coerceIn(0, 255)
        val g = (sumG / count).toInt().coerceIn(0, 255)
        val b = (sumB / count).toInt().coerceIn(0, 255)
        val rgb = Color.rgb(r, g, b)
        return QuickSample(rgb = rgb, luminance = quickLuminance(rgb))
    }

    // Lightweight luminance for fast path thresholding, range [0, 1].
    fun quickLuminance(rgb: Int): Float {
        val r = Color.red(rgb) / 255f
        val g = Color.green(rgb) / 255f
        val b = Color.blue(rgb) / 255f
        return (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
    }

    /**
     * WCAG relative luminance:
     * 1) sRGB -> linear RGB
     * 2) L = 0.2126*R + 0.7152*G + 0.0722*B
     */
    fun relativeLuminance(rgb: Int): Float {
        val r = srgbChannelToLinear(Color.red(rgb) / 255f)
        val g = srgbChannelToLinear(Color.green(rgb) / 255f)
        val b = srgbChannelToLinear(Color.blue(rgb) / 255f)
        return (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
    }

    fun contrastRatio(rgb1: Int, rgb2: Int): Float {
        val l1 = relativeLuminance(rgb1)
        val l2 = relativeLuminance(rgb2)
        val hi = max(l1, l2)
        val lo = min(l1, l2)
        return ((hi + 0.05f) / (lo + 0.05f)).coerceAtLeast(1f)
    }

    /**
     * Fast style decision:
     * - If background is bright -> immediately use dark fill.
     * - If background is dark -> immediately use light fill.
     * This path is intentionally simple for per-frame speed.
     */
    fun chooseQuickDotColor(
        backgroundRgb: Int,
        quickLuminance: Float,
        params: DotColorParams
    ): DotStyle? {
        val mode = resolveQuickToneMode(
            filteredLuma = quickLuminance,
            previousMode = 0,
            params = params
        )
        if (mode == 0) return null
        return chooseQuickDotColorForMode(backgroundRgb, mode, params)
    }

    /**
     * Hysteresis decision for quick tone mode.
     * mode = 1 means bright background -> dark dot.
     * mode = -1 means dark background -> light dot.
     * mode = 0 means uncertain zone, keep fine path.
     */
    fun resolveQuickToneMode(
        filteredLuma: Float,
        previousMode: Int,
        params: DotColorParams
    ): Int {
        val lightThreshold = params.quickLuminanceThresholdLight.coerceIn(0.40f, 0.95f)
        val darkThreshold = params.quickLuminanceThresholdDark.coerceIn(0.05f, 0.60f)
        val fuzzy = params.quickThresholdFuzzyBand.coerceIn(0.0f, 0.15f)

        val lightEnter = (lightThreshold + fuzzy).coerceAtMost(0.99f)
        val lightExit = (lightThreshold - fuzzy).coerceIn(0.01f, 0.99f)
        val darkEnter = (darkThreshold - fuzzy).coerceAtLeast(0.01f)
        val darkExit = (darkThreshold + fuzzy).coerceIn(0.01f, 0.99f)

        return when (previousMode) {
            1 -> if (filteredLuma < lightExit) 0 else 1
            -1 -> if (filteredLuma > darkExit) 0 else -1
            else -> when {
                filteredLuma >= lightEnter -> 1
                filteredLuma <= darkEnter -> -1
                else -> 0
            }
        }
    }

    fun chooseQuickDotColorForMode(
        backgroundRgb: Int,
        mode: Int,
        params: DotColorParams
    ): DotStyle {
        val minContrast = effectiveMinContrast(params)
        val source: DotStyleSource
        val fill: Int
        if (mode >= 1) {
            source = DotStyleSource.QUICK_LIGHT
            fill = params.darkQuickFill
        } else {
            source = DotStyleSource.QUICK_DARK
            fill = params.lightQuickFill
        }

        val fillContrast = contrastRatio(fill, backgroundRgb)
        val bestStroke = if (contrastRatio(Color.WHITE, backgroundRgb) >= contrastRatio(Color.BLACK, backgroundRgb)) {
            Color.WHITE
        } else {
            Color.BLACK
        }
        val strokeContrast = contrastRatio(bestStroke, backgroundRgb)
        val needStroke = fillContrast < minContrast && strokeContrast > fillContrast
        val stroke = if (needStroke) bestStroke else null
        val strokeWidth = if (needStroke) params.minStrokePx.coerceAtLeast(1f) else 0f

        return DotStyle(
            fill = fill,
            stroke = stroke,
            strokeWidthPx = strokeWidth,
            halo = null,
            metContrast = max(fillContrast, strokeContrast) >= minContrast,
            contrastValue = max(fillContrast, if (needStroke) strokeContrast else fillContrast),
            source = source
        )
    }

    /**
     * Detailed path:
     * 1) Try preferred fill colors for current state.
     * 2) Fallback to stroke, then halo, then combined strategy.
     * 3) In high-contrast mode, force pure black/white if needed.
     */
    fun chooseDotColor(
        backgroundRgb: Int,
        state: DotVisualState,
        params: DotColorParams
    ): DotStyle {
        val effectiveState = if (params.reducedMotion && state == DotVisualState.JERK) {
            DotVisualState.CRUISING
        } else {
            state
        }
        val minContrast = effectiveMinContrast(params)
        val candidates = params.preferredColorsPerState[effectiveState]
            ?: params.preferredColorsPerState[DotVisualState.CRUISING]
            ?: listOf(Color.WHITE)

        var bestFill = candidates.first()
        var bestRatio = 0f

        // Pseudocode described in spec:
        // Lbg = relativeLuminance(bgRgb)
        // for each candidate fill:
        //   ratio = contrastRatio(candidate, bgRgb)
        //   if ratio >= minContrast -> return fill
        for (candidate in candidates) {
            val ratio = contrastRatio(candidate, backgroundRgb)
            if (ratio > bestRatio) {
                bestRatio = ratio
                bestFill = candidate
            }
            if (ratio >= minContrast) {
                return DotStyle(
                    fill = candidate,
                    stroke = null,
                    strokeWidthPx = 0f,
                    halo = null,
                    metContrast = true,
                    contrastValue = ratio,
                    source = DotStyleSource.FINE
                )
            }
        }

        val black = Color.BLACK
        val white = Color.WHITE
        val strokeColor = if (contrastRatio(white, backgroundRgb) >= contrastRatio(black, backgroundRgb)) {
            white
        } else {
            black
        }
        val strokeRatio = contrastRatio(strokeColor, backgroundRgb)
        val strokeWidth = params.maxStrokePx.coerceIn(params.minStrokePx, params.maxStrokePx)

        if (max(bestRatio, strokeRatio) >= minContrast) {
            return DotStyle(
                fill = bestFill,
                stroke = strokeColor,
                strokeWidthPx = strokeWidth,
                halo = null,
                metContrast = true,
                contrastValue = max(bestRatio, strokeRatio),
                source = DotStyleSource.FINE
            )
        }

        // Halo fallback estimate:
        // haloMixColor = alpha * halo + (1-alpha) * background
        // then compute WCAG contrast for mixed result.
        val haloAlpha = if (params.reducedMotion) {
            params.haloAlpha.coerceIn(0.4f, 0.8f) * 0.85f
        } else {
            params.haloAlpha.coerceIn(0.4f, 0.8f)
        }
        val haloColor = strokeColor
        val haloMixColor = blendSrgb(haloColor, backgroundRgb, haloAlpha)
        val haloRatio = contrastRatio(haloMixColor, backgroundRgb)
        val haloSize = params.haloMaxPx.coerceIn(params.haloMinPx, params.haloMaxPx)

        if (max(bestRatio, haloRatio) >= minContrast) {
            return DotStyle(
                fill = bestFill,
                stroke = null,
                strokeWidthPx = 0f,
                halo = DotHaloStyle(haloColor, haloSize, haloAlpha),
                metContrast = true,
                contrastValue = max(bestRatio, haloRatio),
                source = DotStyleSource.FINE
            )
        }

        val combinedRatio = max(bestRatio, max(strokeRatio, haloRatio))
        if (combinedRatio >= minContrast) {
            return DotStyle(
                fill = bestFill,
                stroke = strokeColor,
                strokeWidthPx = strokeWidth,
                halo = DotHaloStyle(haloColor, haloSize, haloAlpha),
                metContrast = true,
                contrastValue = combinedRatio,
                source = DotStyleSource.FINE
            )
        }

        if (params.highContrastMode) {
            val forceFill = if (contrastRatio(white, backgroundRgb) >= contrastRatio(black, backgroundRgb)) {
                white
            } else {
                black
            }
            val forceStroke = if (forceFill == white) black else white
            val forceRatio = contrastRatio(forceFill, backgroundRgb)
            return DotStyle(
                fill = forceFill,
                stroke = forceStroke,
                strokeWidthPx = strokeWidth,
                halo = DotHaloStyle(forceStroke, haloSize, haloAlpha),
                metContrast = forceRatio >= minContrast,
                contrastValue = forceRatio,
                source = DotStyleSource.FINE
            )
        }

        return DotStyle(
            fill = bestFill,
            stroke = strokeColor,
            strokeWidthPx = strokeWidth,
            halo = DotHaloStyle(haloColor, haloSize, haloAlpha),
            metContrast = false,
            contrastValue = combinedRatio,
            source = DotStyleSource.FINE
        )
    }

    /**
     * Cross-fade transition for fill/stroke/halo.
     * quick path can set 0-80ms, fine path can set 120-250ms.
     */
    fun applyDotStyle(
        dotElement: DotStyleTransitionState,
        styleObj: DotStyle,
        transitionDurationMs: Int,
        dtSeconds: Float
    ): DotRenderStyle {
        val duration = transitionDurationMs.coerceIn(0, 300).toFloat()
        if (!sameStyle(dotElement.target, styleObj)) {
            dotElement.from = dotElement.current
            dotElement.target = styleObj
            dotElement.elapsedMs = 0f
            dotElement.durationMs = duration
        } else {
            dotElement.durationMs = duration
        }

        dotElement.elapsedMs = (dotElement.elapsedMs + dtSeconds * 1000f).coerceAtMost(dotElement.durationMs)
        val tRaw = if (dotElement.durationMs <= 0f) 1f else (dotElement.elapsedMs / dotElement.durationMs)
        val t = easeInOut(tRaw)

        val fill = lerpColor(dotElement.from.fill, dotElement.target.fill, t)
        val stroke = lerpOptionalColor(dotElement.from.stroke, dotElement.target.stroke, t)
        val strokeWidth = lerp(dotElement.from.strokeWidthPx, dotElement.target.strokeWidthPx, t)

        val fromHalo = dotElement.from.halo
        val toHalo = dotElement.target.halo
        val haloColor = lerpOptionalColor(fromHalo?.color, toHalo?.color, t)
        val haloSize = lerp(fromHalo?.sizePx ?: 0f, toHalo?.sizePx ?: 0f, t)
        val haloAlpha = lerp(fromHalo?.alpha ?: 0f, toHalo?.alpha ?: 0f, t)

        dotElement.current = DotStyle(
            fill = fill,
            stroke = stroke,
            strokeWidthPx = strokeWidth,
            halo = if (haloColor != null && haloSize > 0.01f && haloAlpha > 0.01f) {
                DotHaloStyle(haloColor, haloSize, haloAlpha)
            } else {
                null
            },
            metContrast = styleObj.metContrast,
            contrastValue = styleObj.contrastValue,
            source = styleObj.source
        )

        return DotRenderStyle(
            fill = fill,
            stroke = stroke,
            strokeWidthPx = strokeWidth,
            haloColor = dotElement.current.halo?.color,
            haloSizePx = dotElement.current.halo?.sizePx ?: 0f,
            haloAlpha = dotElement.current.halo?.alpha ?: 0f
        )
    }

    fun describeStyle(style: DotStyle): String {
        val source = when (style.source) {
            DotStyleSource.QUICK_LIGHT -> "quick-dark-fill"
            DotStyleSource.QUICK_DARK -> "quick-light-fill"
            DotStyleSource.FINE -> "fine-wcag"
        }
        val fill = colorToHex(style.fill)
        val stroke = style.stroke?.let { colorToHex(it) } ?: "none"
        val halo = style.halo?.let { "${colorToHex(it.color)}@${"%.2f".format(it.alpha)}" } ?: "none"
        return "$source fill=$fill stroke=$stroke halo=$halo"
    }

    private fun effectiveMinContrast(params: DotColorParams): Float {
        return if (params.highContrastMode) 7.0f else params.minContrast.coerceIn(3.0f, 7.0f)
    }

    private fun sameStyle(a: DotStyle, b: DotStyle): Boolean {
        return a.fill == b.fill &&
            a.stroke == b.stroke &&
            abs(a.strokeWidthPx - b.strokeWidthPx) < 0.001f &&
            a.halo?.color == b.halo?.color &&
            abs((a.halo?.sizePx ?: 0f) - (b.halo?.sizePx ?: 0f)) < 0.001f &&
            abs((a.halo?.alpha ?: 0f) - (b.halo?.alpha ?: 0f)) < 0.001f
    }

    private fun srgbChannelToLinear(c: Float): Float {
        return if (c <= 0.03928f) {
            c / 12.92f
        } else {
            ((c + 0.055f) / 1.055f).pow(2.4f)
        }
    }

    private fun blendSrgb(fg: Int, bg: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        val r = (Color.red(fg) * a + Color.red(bg) * (1f - a)).toInt().coerceIn(0, 255)
        val g = (Color.green(fg) * a + Color.green(bg) * (1f - a)).toInt().coerceIn(0, 255)
        val b = (Color.blue(fg) * a + Color.blue(bg) * (1f - a)).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun colorToHex(color: Int): String {
        return String.format("#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val aa = Color.alpha(a)
        val ar = Color.red(a)
        val ag = Color.green(a)
        val ab = Color.blue(a)

        val ba = Color.alpha(b)
        val br = Color.red(b)
        val bg = Color.green(b)
        val bb = Color.blue(b)

        return Color.argb(
            lerp(aa.toFloat(), ba.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(ar.toFloat(), br.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(ag.toFloat(), bg.toFloat(), t).toInt().coerceIn(0, 255),
            lerp(ab.toFloat(), bb.toFloat(), t).toInt().coerceIn(0, 255)
        )
    }

    private fun lerpOptionalColor(a: Int?, b: Int?, t: Float): Int? {
        return when {
            a == null && b == null -> null
            a == null -> lerpColor(Color.TRANSPARENT, b!!, t)
            b == null -> lerpColor(a, Color.TRANSPARENT, t).takeIf { Color.alpha(it) > 0 }
            else -> lerpColor(a, b, t)
        }
    }

    private fun easeInOut(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}
