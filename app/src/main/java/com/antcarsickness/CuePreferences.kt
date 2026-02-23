package com.antcarsickness

import android.content.Context
import android.content.SharedPreferences

data class CueDisplayConfig(
    val renderMode: MotionCueView.RenderMode,
    val pattern: MotionCueView.Pattern,
    val largerDots: Boolean,
    val moreDots: Boolean,
    val colorMode: MotionCueView.ColorMode,
    val dotScale: Float,
    val minContrast: Float,
    val highContrastMode: Boolean,
    val reducedMotion: Boolean,
    val colorTransitionMs: Int,
    val quickTransitionMs: Int,
    val fineSampleHz: Int,
    val quickThresholdLight: Float,
    val quickThresholdDark: Float,
    val motionAlphaFast: Float,
    val motionAlphaSlow: Float,
    val motionDampingKv: Float,
    val motionTurnSmooth: Float,
    val motionPredictK: Float
)

object CuePreferences {
    private const val PREFS_NAME = "motion_cue_prefs"

    const val KEY_OVERLAY_ENABLED = "overlay_enabled"
    const val KEY_OVERLAY_RUNNING = "overlay_running"
    private const val KEY_RENDER_MODE = "render_mode"
    private const val KEY_PATTERN = "pattern"
    private const val KEY_LARGER_DOTS = "larger_dots"
    private const val KEY_MORE_DOTS = "more_dots"
    private const val KEY_AUTO_TONE = "auto_tone"
    private const val KEY_COLOR_MODE = "color_mode"
    private const val KEY_DOT_SCALE_PERCENT = "dot_scale_percent"
    private const val KEY_MIN_CONTRAST_X10 = "min_contrast_x10"
    private const val KEY_HIGH_CONTRAST_MODE = "high_contrast_mode"
    private const val KEY_REDUCED_MOTION = "reduced_motion"
    private const val KEY_COLOR_TRANSITION_MS = "color_transition_ms"
    private const val KEY_QUICK_TRANSITION_MS = "quick_transition_ms"
    private const val KEY_FINE_SAMPLE_HZ = "fine_sample_hz"
    private const val KEY_QUICK_THRESHOLD_LIGHT_X100 = "quick_threshold_light_x100"
    private const val KEY_QUICK_THRESHOLD_DARK_X100 = "quick_threshold_dark_x100"
    private const val KEY_MOTION_ALPHA_FAST_X100 = "motion_alpha_fast_x100"
    private const val KEY_MOTION_ALPHA_SLOW_X100 = "motion_alpha_slow_x100"
    private const val KEY_MOTION_DAMPING_X100 = "motion_damping_x100"
    private const val KEY_MOTION_TURN_SMOOTH_X100 = "motion_turn_smooth_x100"
    private const val KEY_MOTION_PREDICT_K_X100 = "motion_predict_k_x100"
    private const val KEY_INITIAL_PERMISSION_REQUESTED = "initial_permission_requested"

    fun sharedPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun loadDisplayConfig(context: Context): CueDisplayConfig {
        val prefs = sharedPrefs(context)
        val mode = when (prefs.getInt(KEY_RENDER_MODE, 2)) {
            0 -> MotionCueView.RenderMode.OFF
            2 -> MotionCueView.RenderMode.ON
            else -> MotionCueView.RenderMode.AUTO
        }
        val pattern = when (prefs.getInt(KEY_PATTERN, 0)) {
            1 -> MotionCueView.Pattern.DYNAMIC
            else -> MotionCueView.Pattern.REGULAR
        }
        return CueDisplayConfig(
            renderMode = mode,
            pattern = pattern,
            largerDots = prefs.getBoolean(KEY_LARGER_DOTS, true),
            moreDots = prefs.getBoolean(KEY_MORE_DOTS, false),
            colorMode = when (prefs.getInt(KEY_COLOR_MODE, -1)) {
                0 -> MotionCueView.ColorMode.PURE_DARK
                1 -> MotionCueView.ColorMode.AUTO_TONE
                2 -> MotionCueView.ColorMode.INVERT
                else -> if (prefs.getBoolean(KEY_AUTO_TONE, true)) {
                    MotionCueView.ColorMode.AUTO_TONE
                } else {
                    MotionCueView.ColorMode.PURE_DARK
                }
            },
            dotScale = (prefs.getInt(KEY_DOT_SCALE_PERCENT, 170).coerceIn(60, 180)) / 100f,
            minContrast = (prefs.getInt(KEY_MIN_CONTRAST_X10, 45).coerceIn(30, 70)) / 10f,
            highContrastMode = prefs.getBoolean(KEY_HIGH_CONTRAST_MODE, false),
            reducedMotion = prefs.getBoolean(KEY_REDUCED_MOTION, false),
            colorTransitionMs = prefs.getInt(KEY_COLOR_TRANSITION_MS, 200).coerceIn(120, 250),
            quickTransitionMs = prefs.getInt(KEY_QUICK_TRANSITION_MS, 70).coerceIn(0, 80),
            fineSampleHz = prefs.getInt(KEY_FINE_SAMPLE_HZ, 10).coerceIn(5, 50),
            quickThresholdLight = (prefs.getInt(KEY_QUICK_THRESHOLD_LIGHT_X100, 64).coerceIn(40, 95)) / 100f,
            quickThresholdDark = (prefs.getInt(KEY_QUICK_THRESHOLD_DARK_X100, 36).coerceIn(5, 60)) / 100f,
            motionAlphaFast = (prefs.getInt(KEY_MOTION_ALPHA_FAST_X100, 65).coerceIn(45, 90)) / 100f,
            motionAlphaSlow = (prefs.getInt(KEY_MOTION_ALPHA_SLOW_X100, 12).coerceIn(5, 30)) / 100f,
            motionDampingKv = (prefs.getInt(KEY_MOTION_DAMPING_X100, 90).coerceIn(40, 200)) / 100f,
            motionTurnSmooth = (prefs.getInt(KEY_MOTION_TURN_SMOOTH_X100, 500).coerceIn(200, 1200)) / 100f,
            motionPredictK = (prefs.getInt(KEY_MOTION_PREDICT_K_X100, 28).coerceIn(5, 80)) / 100f
        )
    }

    fun saveDisplayConfig(context: Context, config: CueDisplayConfig) {
        sharedPrefs(context).edit()
            .putInt(
                KEY_RENDER_MODE,
                when (config.renderMode) {
                    MotionCueView.RenderMode.OFF -> 0
                    MotionCueView.RenderMode.AUTO -> 1
                    MotionCueView.RenderMode.ON -> 2
                }
            )
            .putInt(
                KEY_PATTERN,
                when (config.pattern) {
                    MotionCueView.Pattern.REGULAR -> 0
                    MotionCueView.Pattern.DYNAMIC -> 1
                }
            )
            .putBoolean(KEY_LARGER_DOTS, config.largerDots)
            .putBoolean(KEY_MORE_DOTS, config.moreDots)
            .putBoolean(KEY_AUTO_TONE, config.colorMode != MotionCueView.ColorMode.PURE_DARK)
            .putInt(
                KEY_COLOR_MODE,
                when (config.colorMode) {
                    MotionCueView.ColorMode.PURE_DARK -> 0
                    MotionCueView.ColorMode.AUTO_TONE -> 1
                    MotionCueView.ColorMode.INVERT -> 2
                }
            )
            .putInt(
                KEY_DOT_SCALE_PERCENT,
                (config.dotScale * 100f).toInt().coerceIn(60, 180)
            )
            .putInt(
                KEY_MIN_CONTRAST_X10,
                (config.minContrast * 10f).toInt().coerceIn(30, 70)
            )
            .putBoolean(KEY_HIGH_CONTRAST_MODE, config.highContrastMode)
            .putBoolean(KEY_REDUCED_MOTION, config.reducedMotion)
            .putInt(KEY_COLOR_TRANSITION_MS, config.colorTransitionMs.coerceIn(120, 250))
            .putInt(KEY_QUICK_TRANSITION_MS, config.quickTransitionMs.coerceIn(0, 80))
            .putInt(KEY_FINE_SAMPLE_HZ, config.fineSampleHz.coerceIn(5, 50))
            .putInt(
                KEY_QUICK_THRESHOLD_LIGHT_X100,
                (config.quickThresholdLight * 100f).toInt().coerceIn(40, 95)
            )
            .putInt(
                KEY_QUICK_THRESHOLD_DARK_X100,
                (config.quickThresholdDark * 100f).toInt().coerceIn(5, 60)
            )
            .putInt(
                KEY_MOTION_ALPHA_FAST_X100,
                (config.motionAlphaFast * 100f).toInt().coerceIn(45, 90)
            )
            .putInt(
                KEY_MOTION_ALPHA_SLOW_X100,
                (config.motionAlphaSlow * 100f).toInt().coerceIn(5, 30)
            )
            .putInt(
                KEY_MOTION_DAMPING_X100,
                (config.motionDampingKv * 100f).toInt().coerceIn(40, 200)
            )
            .putInt(
                KEY_MOTION_TURN_SMOOTH_X100,
                (config.motionTurnSmooth * 100f).toInt().coerceIn(200, 1200)
            )
            .putInt(
                KEY_MOTION_PREDICT_K_X100,
                (config.motionPredictK * 100f).toInt().coerceIn(5, 80)
            )
            .apply()
    }

    fun applyDisplayConfig(view: MotionCueView, config: CueDisplayConfig, subtleMode: Boolean) {
        view.setRenderMode(config.renderMode)
        view.setPattern(config.pattern)
        view.setLargerDots(config.largerDots)
        view.setMoreDots(config.moreDots)
        view.setColorMode(config.colorMode)
        view.setDotScale(config.dotScale)
        view.setMinContrast(config.minContrast)
        view.setHighContrastMode(config.highContrastMode)
        view.setReducedMotion(config.reducedMotion)
        view.setColorTransitionMs(config.colorTransitionMs)
        view.setQuickTransitionMs(config.quickTransitionMs)
        view.setFineSampleHz(config.fineSampleHz)
        view.setQuickLuminanceThresholdLight(config.quickThresholdLight)
        view.setQuickLuminanceThresholdDark(config.quickThresholdDark)
        view.setMotionAlphaFast(config.motionAlphaFast)
        view.setMotionAlphaSlow(config.motionAlphaSlow)
        view.setMotionDampingKv(config.motionDampingKv)
        view.setMotionTurnSmooth(config.motionTurnSmooth)
        view.setMotionPredictK(config.motionPredictK)
        view.setSubtleMode(subtleMode)
    }

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        sharedPrefs(context).edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
    }

    fun isOverlayEnabled(context: Context): Boolean {
        return sharedPrefs(context).getBoolean(KEY_OVERLAY_ENABLED, false)
    }

    fun setOverlayRunning(context: Context, running: Boolean) {
        sharedPrefs(context).edit().putBoolean(KEY_OVERLAY_RUNNING, running).apply()
    }

    fun isOverlayRunning(context: Context): Boolean {
        return sharedPrefs(context).getBoolean(KEY_OVERLAY_RUNNING, false)
    }

    fun isInitialPermissionRequested(context: Context): Boolean {
        return sharedPrefs(context).getBoolean(KEY_INITIAL_PERMISSION_REQUESTED, false)
    }

    fun markInitialPermissionRequested(context: Context) {
        sharedPrefs(context).edit().putBoolean(KEY_INITIAL_PERMISSION_REQUESTED, true).apply()
    }
}
