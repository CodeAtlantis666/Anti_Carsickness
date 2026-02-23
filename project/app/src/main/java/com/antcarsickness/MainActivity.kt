package com.antcarsickness

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private data class UiPalette(
        val rootStart: Int,
        val rootEnd: Int,
        val panelBackground: Int,
        val cardBackground: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val accent: Int,
        val buttonBackground: Int,
        val buttonText: Int,
        val border: Int
    )

    private enum class DemoBgScene {
        CUSTOM,
        LIGHT,
        DARK,
        STRIPES
    }

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1202
        private const val REQ_SCREEN_CAPTURE = 1203
        private const val DOT_SCALE_MIN_PERCENT = 60
        private const val DOT_SCALE_MAX_PERCENT = 180
    }

    private lateinit var statusText: TextView
    private lateinit var detailsText: TextView
    private lateinit var permissionText: TextView
    private lateinit var samplingText: TextView
    private lateinit var overlayStateText: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnGrantSampling: Button
    private lateinit var btnToggleOverlay: Button
    private lateinit var engine: VehicleMotionEngine
    private lateinit var config: CueDisplayConfig
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var uiPalette: UiPalette
    private var pendingInitialScreenCapture = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ProjectionPermissionStore.init(this)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        config = CuePreferences.loadDisplayConfig(this)
        val palette = buildUiPalette()
        uiPalette = palette

        statusText = TextView(this).apply {
            setTextColor(palette.textPrimary)
            textSize = 16f
            text = getString(R.string.status_detecting)
        }

        detailsText = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 14f
            text = getString(R.string.hint_mode_auto)
        }

        permissionText = infoTextView()
        samplingText = infoTextView()
        overlayStateText = infoTextView()

        btnGrantOverlay = Button(this).apply {
            text = getString(R.string.btn_grant_overlay)
            setOnClickListener { openOverlayPermissionSettings() }
        }
        btnGrantSampling = Button(this).apply {
            text = getString(R.string.btn_grant_sampling)
            setOnClickListener { requestScreenCapturePermissionSafely() }
        }
        btnToggleOverlay = Button(this).apply {
            setOnClickListener { toggleOverlay() }
        }

        val modeSpinner = buildSpinner(
            items = listOf(
                getString(R.string.opt_off),
                getString(R.string.opt_auto),
                getString(R.string.opt_on)
            ),
            initialSelection = when (config.renderMode) {
                MotionCueView.RenderMode.OFF -> 0
                MotionCueView.RenderMode.AUTO -> 1
                MotionCueView.RenderMode.ON -> 2
            }
        )
        modeSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            config = config.copy(
                renderMode = when (position) {
                    0 -> MotionCueView.RenderMode.OFF
                    2 -> MotionCueView.RenderMode.ON
                    else -> MotionCueView.RenderMode.AUTO
                }
            )
            persistAndApplyConfig()
        }

        val patternSpinner = buildSpinner(
            items = listOf(getString(R.string.opt_regular), getString(R.string.opt_dynamic)),
            initialSelection = if (config.pattern == MotionCueView.Pattern.DYNAMIC) 1 else 0
        )
        patternSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            config = config.copy(
                pattern = if (position == 1) MotionCueView.Pattern.DYNAMIC else MotionCueView.Pattern.REGULAR
            )
            persistAndApplyConfig()
        }

        val colorSpinner = buildSpinner(
            items = listOf(
                getString(R.string.opt_auto_tone),
                getString(R.string.opt_pure_dark),
                getString(R.string.opt_invert)
            ),
            initialSelection = when (config.colorMode) {
                MotionCueView.ColorMode.AUTO_TONE -> 0
                MotionCueView.ColorMode.PURE_DARK -> 1
                MotionCueView.ColorMode.INVERT -> 2
            }
        )
        colorSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            config = config.copy(
                colorMode = when (position) {
                    1 -> MotionCueView.ColorMode.PURE_DARK
                    2 -> MotionCueView.ColorMode.INVERT
                    else -> MotionCueView.ColorMode.AUTO_TONE
                }
            )
            persistAndApplyConfig()
            refreshOverlayUiState()
        }

        val sizeSpinner = buildSpinner(
            items = listOf(getString(R.string.opt_normal_dots), getString(R.string.opt_large_dots)),
            initialSelection = if (config.largerDots) 1 else 0
        )
        sizeSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            config = config.copy(largerDots = position == 1)
            persistAndApplyConfig()
        }

        val countSpinner = buildSpinner(
            items = listOf(getString(R.string.opt_normal_density), getString(R.string.opt_more_density)),
            initialSelection = if (config.moreDots) 1 else 0
        )
        countSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            config = config.copy(moreDots = position == 1)
            persistAndApplyConfig()
        }

        val dotScaleValue = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
            text = getString(
                R.string.dot_size_percent_value,
                (config.dotScale * 100f).roundToInt().coerceIn(DOT_SCALE_MIN_PERCENT, DOT_SCALE_MAX_PERCENT)
            )
        }

        val dotScaleSeekBar = SeekBar(this).apply {
            max = DOT_SCALE_MAX_PERCENT - DOT_SCALE_MIN_PERCENT
            progress =
                (config.dotScale * 100f).roundToInt().coerceIn(DOT_SCALE_MIN_PERCENT, DOT_SCALE_MAX_PERCENT) -
                    DOT_SCALE_MIN_PERCENT
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val percent = (progress + DOT_SCALE_MIN_PERCENT).coerceIn(
                        DOT_SCALE_MIN_PERCENT,
                        DOT_SCALE_MAX_PERCENT
                    )
                    dotScaleValue.text = getString(R.string.dot_size_percent_value, percent)
                    if (!fromUser) return
                    config = config.copy(dotScale = percent / 100f)
                    persistAndApplyConfig()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }

        var demoState = DotVisualState.CRUISING
        var demoScene = DemoBgScene.DARK
        var demoR = 18
        var demoG = 18
        var demoB = 18
        var lastMotionTriggerMs = SystemClock.elapsedRealtime()

        val minContrastValue = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
            text = getString(R.string.value_min_contrast, config.minContrast)
        }
        val minContrastSeekBar = SeekBar(this).apply {
            max = 40 // 3.0 - 7.0
            progress = ((config.minContrast * 10f).roundToInt().coerceIn(30, 70) - 30)
        }

        val transitionValue = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
            text = getString(R.string.value_transition_ms, config.colorTransitionMs)
        }
        val transitionSeekBar = SeekBar(this).apply {
            max = 130 // 120 - 250
            progress = (config.colorTransitionMs.coerceIn(120, 250) - 120)
        }

        val quickTransitionValue = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
            text = getString(R.string.value_transition_ms, config.quickTransitionMs)
        }
        val quickTransitionSeekBar = SeekBar(this).apply {
            max = 80 // 0 - 80
            progress = config.quickTransitionMs.coerceIn(0, 80)
        }

        val fineHzValue = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
            text = getString(R.string.value_hz, config.fineSampleHz)
        }
        val fineHzSeekBar = SeekBar(this).apply {
            max = 45 // 5 - 50
            progress = config.fineSampleHz.coerceIn(5, 50) - 5
        }

        val quickLightValue = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
            text = getString(R.string.value_ratio_2, config.quickThresholdLight)
        }
        val quickLightSeekBar = SeekBar(this).apply {
            max = 55 // 0.40 - 0.95
            progress = (config.quickThresholdLight * 100f).roundToInt().coerceIn(40, 95) - 40
        }

        val quickDarkValue = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
            text = getString(R.string.value_ratio_2, config.quickThresholdDark)
        }
        val quickDarkSeekBar = SeekBar(this).apply {
            max = 55 // 0.05 - 0.60
            progress = (config.quickThresholdDark * 100f).roundToInt().coerceIn(5, 60) - 5
        }

        val alphaFastValue = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
            text = getString(R.string.value_ratio_2, config.motionAlphaFast)
        }
        val alphaFastSeekBar = SeekBar(this).apply {
            max = 45 // 0.45 - 0.90
            progress = (config.motionAlphaFast * 100f).roundToInt().coerceIn(45, 90) - 45
        }

        val alphaSlowValue = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
            text = getString(R.string.value_ratio_2, config.motionAlphaSlow)
        }
        val alphaSlowSeekBar = SeekBar(this).apply {
            max = 25 // 0.05 - 0.30
            progress = (config.motionAlphaSlow * 100f).roundToInt().coerceIn(5, 30) - 5
        }

        val kvValue = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
            text = getString(R.string.value_ratio_2, config.motionDampingKv)
        }
        val kvSeekBar = SeekBar(this).apply {
            max = 160 // 0.40 - 2.00
            progress = (config.motionDampingKv * 100f).roundToInt().coerceIn(40, 200) - 40
        }

        val turnSmoothValue = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
            text = getString(R.string.value_ratio_2, config.motionTurnSmooth)
        }
        val turnSmoothSeekBar = SeekBar(this).apply {
            max = 1000 // 2.00 - 12.00
            progress = (config.motionTurnSmooth * 100f).roundToInt().coerceIn(200, 1200) - 200
        }

        val predictKValue = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
            text = getString(R.string.value_ratio_2, config.motionPredictK)
        }
        val predictKSeekBar = SeekBar(this).apply {
            max = 75 // 0.05 - 0.80
            progress = (config.motionPredictK * 100f).roundToInt().coerceIn(5, 80) - 5
        }

        val btnHighContrast = Button(this)
        val btnReducedMotion = Button(this)

        val sceneButtons = listOf(
            Pair(getString(R.string.scene_light), DemoBgScene.LIGHT),
            Pair(getString(R.string.scene_dark), DemoBgScene.DARK),
            Pair(getString(R.string.scene_stripes), DemoBgScene.STRIPES),
            Pair(getString(R.string.scene_custom), DemoBgScene.CUSTOM)
        )
        val sceneButtonViews = ArrayList<Button>()
        val sceneButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val stateButtons = listOf(
            Pair(getString(R.string.state_idle), DotVisualState.IDLE),
            Pair(getString(R.string.state_accelerating), DotVisualState.ACCELERATING),
            Pair(getString(R.string.state_braking), DotVisualState.BRAKING),
            Pair(getString(R.string.state_turning), DotVisualState.TURNING),
            Pair(getString(R.string.state_cruising), DotVisualState.CRUISING),
            Pair(getString(R.string.state_jerk), DotVisualState.JERK)
        )
        val stateButtonViews = ArrayList<Button>()
        val stateButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val bgRValue = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
            text = demoR.toString()
        }
        val bgRSeekBar = SeekBar(this).apply {
            max = 255
            progress = demoR
        }
        val bgGValue = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
            text = demoG.toString()
        }
        val bgGSeekBar = SeekBar(this).apply {
            max = 255
            progress = demoG
        }
        val bgBValue = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
            text = demoB.toString()
        }
        val bgBSeekBar = SeekBar(this).apply {
            max = 255
            progress = demoB
        }

        val bgColorHexText = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
        }
        val contrastInfoText = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
        }
        val runtimeInfoText = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
        }
        val bgSwatchView = View(this).apply {
            minimumHeight = dp(18)
            setBackgroundColor(Color.rgb(demoR, demoG, demoB))
        }
        val previewView = ColorStylePreviewView(this)

        fun setSwitchButtonState(btn: Button, labelRes: Int, enabled: Boolean) {
            val suffix = if (enabled) getString(R.string.switch_on) else getString(R.string.switch_off)
            btn.text = "${getString(labelRes)}：$suffix"
        }

        fun refreshStateButtons() {
            for (i in stateButtons.indices) {
                val btn = stateButtonViews[i]
                val selected = stateButtons[i].second == demoState
                btn.alpha = if (selected) 1f else 0.72f
            }
        }

        fun refreshSceneButtons() {
            for (i in sceneButtons.indices) {
                val btn = sceneButtonViews[i]
                val selected = sceneButtons[i].second == demoScene
                btn.alpha = if (selected) 1f else 0.72f
            }
        }

        fun resolveBgColor(): Int {
            return when (demoScene) {
                DemoBgScene.LIGHT -> Color.rgb(245, 245, 245)
                DemoBgScene.DARK -> Color.rgb(18, 18, 18)
                DemoBgScene.CUSTOM -> Color.rgb(demoR, demoG, demoB)
                DemoBgScene.STRIPES -> previewView.sampleCenterColor(
                    DemoBgScene.STRIPES,
                    Color.rgb(demoR, demoG, demoB)
                )
            }
        }

        fun updateColorDemo() {
            runCatching {
                val bgColor = resolveBgColor()
                val params = DotColorParams(
                    minContrast = config.minContrast,
                    highContrastMode = config.highContrastMode,
                    reducedMotion = config.reducedMotion,
                    transitionMs = config.colorTransitionMs,
                    quickTransitionMs = config.quickTransitionMs,
                    fineSampleHz = config.fineSampleHz,
                    quickLuminanceThresholdLight = config.quickThresholdLight,
                    quickLuminanceThresholdDark = config.quickThresholdDark
                )
                val quickL = DotColorSystem.quickLuminance(bgColor)
                val quickStyle = DotColorSystem.chooseQuickDotColor(bgColor, quickL, params)
                val fineStyle = DotColorSystem.chooseDotColor(bgColor, demoState, params)
                val minContrast = if (config.highContrastMode) 7f else config.minContrast
                val style = if (quickStyle != null && quickStyle.contrastValue >= minContrast) {
                    quickStyle
                } else {
                    fineStyle
                }
                val ratioText = getString(
                    R.string.value_contrast_info,
                    style.contrastValue,
                    if (style.metContrast) getString(R.string.switch_on) else getString(R.string.switch_off)
                )
                val hex = String.format("%02X%02X%02X", Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor))
                val latencyMs = (SystemClock.elapsedRealtime() - lastMotionTriggerMs).coerceAtLeast(0L)
                bgColorHexText.text = getString(R.string.value_color_hex, hex)
                contrastInfoText.text = ratioText
                runtimeInfoText.text = getString(
                    R.string.value_runtime_debug,
                    quickL,
                    DotColorSystem.describeStyle(style),
                    style.contrastValue,
                    latencyMs
                )
                bgSwatchView.setBackgroundColor(bgColor)
                previewView.update(demoScene, Color.rgb(demoR, demoG, demoB), style)
                setSwitchButtonState(btnHighContrast, R.string.label_high_contrast_mode, config.highContrastMode)
                setSwitchButtonState(btnReducedMotion, R.string.label_reduced_motion, config.reducedMotion)
                refreshStateButtons()
                refreshSceneButtons()
            }.onFailure {
                contrastInfoText.text = getString(R.string.value_contrast_info, 0f, getString(R.string.switch_off))
            }
        }

        minContrastSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = ((progress + 30).coerceIn(30, 70)) / 10f
                minContrastValue.text = getString(R.string.value_min_contrast, value)
                if (!fromUser) return
                config = config.copy(minContrast = value)
                persistAndApplyConfig()
                updateColorDemo()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        transitionSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress + 120).coerceIn(120, 250)
                transitionValue.text = getString(R.string.value_transition_ms, value)
                if (!fromUser) return
                config = config.copy(colorTransitionMs = value)
                persistAndApplyConfig()
                updateColorDemo()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        quickTransitionSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceIn(0, 80)
                quickTransitionValue.text = getString(R.string.value_transition_ms, value)
                if (!fromUser) return
                config = config.copy(quickTransitionMs = value)
                persistAndApplyConfig()
                updateColorDemo()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        fineHzSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress + 5).coerceIn(5, 50)
                fineHzValue.text = getString(R.string.value_hz, value)
                if (!fromUser) return
                config = config.copy(fineSampleHz = value)
                persistAndApplyConfig()
                updateColorDemo()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        quickLightSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = ((progress + 40).coerceIn(40, 95)) / 100f
                quickLightValue.text = getString(R.string.value_ratio_2, value)
                if (!fromUser) return
                config = config.copy(quickThresholdLight = value)
                persistAndApplyConfig()
                updateColorDemo()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        quickDarkSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = ((progress + 5).coerceIn(5, 60)) / 100f
                quickDarkValue.text = getString(R.string.value_ratio_2, value)
                if (!fromUser) return
                config = config.copy(quickThresholdDark = value)
                persistAndApplyConfig()
                updateColorDemo()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        alphaFastSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = ((progress + 45).coerceIn(45, 90)) / 100f
                alphaFastValue.text = getString(R.string.value_ratio_2, value)
                if (!fromUser) return
                config = config.copy(motionAlphaFast = value)
                persistAndApplyConfig()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        alphaSlowSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = ((progress + 5).coerceIn(5, 30)) / 100f
                alphaSlowValue.text = getString(R.string.value_ratio_2, value)
                if (!fromUser) return
                config = config.copy(motionAlphaSlow = value)
                persistAndApplyConfig()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        kvSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = ((progress + 40).coerceIn(40, 200)) / 100f
                kvValue.text = getString(R.string.value_ratio_2, value)
                if (!fromUser) return
                config = config.copy(motionDampingKv = value)
                persistAndApplyConfig()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        turnSmoothSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = ((progress + 200).coerceIn(200, 1200)) / 100f
                turnSmoothValue.text = getString(R.string.value_ratio_2, value)
                if (!fromUser) return
                config = config.copy(motionTurnSmooth = value)
                persistAndApplyConfig()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        predictKSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = ((progress + 5).coerceIn(5, 80)) / 100f
                predictKValue.text = getString(R.string.value_ratio_2, value)
                if (!fromUser) return
                config = config.copy(motionPredictK = value)
                persistAndApplyConfig()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        btnHighContrast.setOnClickListener {
            config = config.copy(highContrastMode = !config.highContrastMode)
            persistAndApplyConfig()
            updateColorDemo()
        }
        styleSecondaryButton(btnHighContrast)
        btnReducedMotion.setOnClickListener {
            config = config.copy(reducedMotion = !config.reducedMotion)
            persistAndApplyConfig()
            updateColorDemo()
        }
        styleSecondaryButton(btnReducedMotion)

        for ((label, scene) in sceneButtons) {
            val btn = Button(this).apply {
                text = label
                stylePillButton(this)
                setOnClickListener {
                    demoScene = scene
                    updateColorDemo()
                }
            }
            sceneButtonViews += btn
            sceneButtonsRow.addView(
                btn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

        for ((label, state) in stateButtons) {
            val btn = Button(this).apply {
                text = label
                stylePillButton(this)
                setOnClickListener {
                    demoState = state
                    lastMotionTriggerMs = SystemClock.elapsedRealtime()
                    updateColorDemo()
                }
            }
            stateButtonViews += btn
            stateButtonsRow.addView(
                btn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

        val bgChangeListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                demoR = bgRSeekBar.progress.coerceIn(0, 255)
                demoG = bgGSeekBar.progress.coerceIn(0, 255)
                demoB = bgBSeekBar.progress.coerceIn(0, 255)
                bgRValue.text = demoR.toString()
                bgGValue.text = demoG.toString()
                bgBValue.text = demoB.toString()
                demoScene = DemoBgScene.CUSTOM
                updateColorDemo()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        bgRSeekBar.setOnSeekBarChangeListener(bgChangeListener)
        bgGSeekBar.setOnSeekBarChangeListener(bgChangeListener)
        bgBSeekBar.setOnSeekBarChangeListener(bgChangeListener)

        updateColorDemo()

        val quickTileHintText = TextView(this).apply {
            setTextColor(palette.textSecondary)
            textSize = 12f
            text = getString(R.string.quick_tile_hint)
        }
        val colorDemoTitle = TextView(this).apply {
            setTextColor(palette.textPrimary)
            textSize = 14f
            text = getString(R.string.label_color_demo)
        }
        styleSecondaryButton(btnGrantOverlay)
        styleSecondaryButton(btnGrantSampling)
        stylePrimaryButton(btnToggleOverlay)

        val homePage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusText)
            addView(detailsText)
            addView(permissionText)
            addView(samplingText)
            addView(overlayStateText)
            addView(
                btnGrantOverlay,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                btnGrantSampling,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                btnToggleOverlay,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(labelWithSpinner(getString(R.string.label_display), modeSpinner))
            addView(labelWithSpinner(getString(R.string.label_style), patternSpinner))
            addView(labelWithSpinner(getString(R.string.label_color), colorSpinner))
            addView(labelWithSpinner(getString(R.string.label_size), sizeSpinner))
            addView(labelWithSpinner(getString(R.string.label_density), countSpinner))
            addView(
                btnHighContrast,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                btnReducedMotion,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(quickTileHintText)
        }

        val settingsPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                labelWithSeekBar(
                    getString(R.string.label_default_dot_size),
                    dotScaleValue,
                    dotScaleSeekBar
                )
            )
            addView(colorDemoTitle)
            addView(labelWithSeekBar(getString(R.string.label_min_contrast), minContrastValue, minContrastSeekBar))
            addView(labelWithSeekBar(getString(R.string.label_color_transition), transitionValue, transitionSeekBar))
            addView(
                labelWithSeekBar(
                    getString(R.string.label_quick_transition),
                    quickTransitionValue,
                    quickTransitionSeekBar
                )
            )
            addView(labelWithSeekBar(getString(R.string.label_fine_sample_hz), fineHzValue, fineHzSeekBar))
            addView(
                labelWithSeekBar(
                    getString(R.string.label_quick_threshold_light),
                    quickLightValue,
                    quickLightSeekBar
                )
            )
            addView(
                labelWithSeekBar(
                    getString(R.string.label_quick_threshold_dark),
                    quickDarkValue,
                    quickDarkSeekBar
                )
            )
            addView(labelWithSeekBar(getString(R.string.label_alpha_fast), alphaFastValue, alphaFastSeekBar))
            addView(labelWithSeekBar(getString(R.string.label_alpha_slow), alphaSlowValue, alphaSlowSeekBar))
            addView(labelWithSeekBar(getString(R.string.label_motion_damping), kvValue, kvSeekBar))
            addView(labelWithSeekBar(getString(R.string.label_turn_smooth), turnSmoothValue, turnSmoothSeekBar))
            addView(labelWithSeekBar(getString(R.string.label_predict_k), predictKValue, predictKSeekBar))
            addView(simpleLabel(getString(R.string.label_demo_scene)))
            addView(
                sceneButtonsRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(simpleLabel(getString(R.string.label_demo_state)))
            addView(
                stateButtonsRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(labelWithSeekBar(getString(R.string.label_bg_r), bgRValue, bgRSeekBar))
            addView(labelWithSeekBar(getString(R.string.label_bg_g), bgGValue, bgGSeekBar))
            addView(labelWithSeekBar(getString(R.string.label_bg_b), bgBValue, bgBSeekBar))
            addView(simpleLabelValue(getString(R.string.label_bg_sample), bgColorHexText))
            addView(
                bgSwatchView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(20)
                )
            )
            addView(simpleLabelValue(getString(R.string.label_contrast_value), contrastInfoText))
            addView(simpleLabelValue(getString(R.string.label_runtime_debug), runtimeInfoText))
            addView(
                previewView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(72)
                )
            )
        }

        val btnHomePage = Button(this).apply {
            text = "首页"
            stylePillButton(this)
        }
        val btnSettingsPage = Button(this).apply {
            text = "设置"
            stylePillButton(this)
        }
        val pageSwitchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                btnHomePage,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                btnSettingsPage,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        fun switchToSettings(showSettings: Boolean) {
            settingsPage.visibility = if (showSettings) View.VISIBLE else View.GONE
            homePage.visibility = if (showSettings) View.GONE else View.VISIBLE
            btnSettingsPage.alpha = if (showSettings) 1f else 0.68f
            btnHomePage.alpha = if (showSettings) 0.68f else 1f
        }
        btnHomePage.setOnClickListener { switchToSettings(false) }
        btnSettingsPage.setOnClickListener { switchToSettings(true) }
        switchToSettings(false)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14))
            background = roundedCardDrawable(palette.panelBackground, palette.border, dp(16))
            addView(pageSwitchRow)
            addView(homePage)
            addView(settingsPage)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(10), dp(10), dp(10), dp(18))
            addView(
                panel,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(palette.rootStart, palette.rootEnd)
            )
            addView(
                scroll,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.TOP
                )
            )
        }

        setContentView(root)

        engine = VehicleMotionEngine(this) { state ->
            if (isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed)) {
                return@VehicleMotionEngine
            }
            runCatching {
                statusText.text = if (state.isVehicleLikely) {
                    getString(R.string.status_in_vehicle, (state.confidence * 100f).roundToInt())
                } else {
                    getString(R.string.status_uncertain, (state.confidence * 100f).roundToInt())
                }
                detailsText.text = getString(
                    R.string.motion_details,
                    state.longitudinalAccel,
                    state.lateralAccel,
                    state.speedEstimate,
                    state.intensity
                )
            }
        }

        maybeRunInitialPermissionFlow()
        refreshOverlayUiState()
    }

    override fun onResume() {
        super.onResume()
        ProjectionPermissionStore.init(this)
        engine.start()
        if (CuePreferences.isOverlayEnabled(this) && OverlayControl.canDrawOverlays(this)) {
            OverlayControl.start(this)
        }
        if (
            config.colorMode != MotionCueView.ColorMode.PURE_DARK &&
            CuePreferences.isOverlayEnabled(this) &&
            ProjectionPermissionStore.get() == null
        ) {
            requestScreenCapturePermissionSafely()
        }
        if (pendingInitialScreenCapture && ProjectionPermissionStore.get() == null) {
            pendingInitialScreenCapture = false
            requestScreenCapturePermissionSafely()
        }
        refreshOverlayUiState()
    }

    override fun onPause() {
        engine.stop()
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SCREEN_CAPTURE) {
            return
        }
        if (resultCode == RESULT_OK && data != null) {
            ProjectionPermissionStore.set(resultCode, data)
            Toast.makeText(this, getString(R.string.toast_sampling_granted), Toast.LENGTH_SHORT).show()
            if (CuePreferences.isOverlayEnabled(this) && OverlayControl.canDrawOverlays(this)) {
                OverlayControl.start(this)
            }
        } else {
            ProjectionPermissionStore.clear()
            Toast.makeText(this, getString(R.string.toast_sampling_denied), Toast.LENGTH_SHORT).show()
        }
        refreshOverlayUiState()
    }

    private fun buildUiPalette(): UiPalette {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (isNight) {
            UiPalette(
                rootStart = Color.parseColor("#111827"),
                rootEnd = Color.parseColor("#030712"),
                panelBackground = Color.parseColor("#F01F2937"),
                cardBackground = Color.parseColor("#374151"),
                textPrimary = Color.parseColor("#F8FAFC"),
                textSecondary = Color.parseColor("#E5E7EB"),
                accent = Color.parseColor("#38BDF8"),
                buttonBackground = Color.parseColor("#2563EB"),
                buttonText = Color.parseColor("#F8FAFC"),
                border = Color.parseColor("#4B5563")
            )
        } else {
            UiPalette(
                rootStart = Color.parseColor("#EEF2FF"),
                rootEnd = Color.parseColor("#E2E8F0"),
                panelBackground = Color.parseColor("#FFFFFFFF"),
                cardBackground = Color.parseColor("#FFFFFF"),
                textPrimary = Color.parseColor("#0F172A"),
                textSecondary = Color.parseColor("#0F172A"),
                accent = Color.parseColor("#0284C7"),
                buttonBackground = Color.parseColor("#0F766E"),
                buttonText = Color.parseColor("#FFFFFF"),
                border = Color.parseColor("#94A3B8")
            )
        }
    }

    private fun roundedCardDrawable(fillColor: Int, strokeColor: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }
    }

    private fun stylePrimaryButton(button: Button) {
        button.isAllCaps = false
        button.setTextColor(uiPalette.buttonText)
        button.textSize = 14f
        button.background = roundedCardDrawable(uiPalette.buttonBackground, uiPalette.border, dp(12))
        button.setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun styleSecondaryButton(button: Button) {
        button.isAllCaps = false
        button.setTextColor(uiPalette.textPrimary)
        button.textSize = 14f
        button.background = roundedCardDrawable(uiPalette.cardBackground, uiPalette.border, dp(12))
        button.setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun stylePillButton(button: Button) {
        button.isAllCaps = false
        button.setTextColor(uiPalette.textPrimary)
        button.textSize = 13f
        button.background = roundedCardDrawable(uiPalette.cardBackground, uiPalette.border, dp(10))
        button.setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    private fun infoTextView(): TextView {
        return TextView(this).apply {
            setTextColor(uiPalette.textSecondary)
            textSize = 13f
        }
    }

    private fun buildSpinner(items: List<String>, initialSelection: Int): Spinner {
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.apply {
                    setTextColor(uiPalette.textPrimary)
                    textSize = 14f
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                (view as? TextView)?.apply {
                    setTextColor(uiPalette.textPrimary)
                    textSize = 14f
                    setBackgroundColor(uiPalette.cardBackground)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return Spinner(this).apply {
            this.adapter = adapter
            setSelection(initialSelection)
            minimumHeight = dp(42)
            background = roundedCardDrawable(uiPalette.cardBackground, uiPalette.border, dp(10))
        }
    }

    private fun labelWithSpinner(label: String, spinner: Spinner): LinearLayout {
        val tv = TextView(this).apply {
            setTextColor(uiPalette.textSecondary)
            textSize = 13f
            text = label
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedCardDrawable(uiPalette.cardBackground, uiPalette.border, dp(12))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(4)
            lp.bottomMargin = dp(4)
            layoutParams = lp
            addView(row)
        }
    }

    private fun labelWithSeekBar(label: String, valueText: TextView, seekBar: SeekBar): LinearLayout {
        val labelView = TextView(this).apply {
            setTextColor(uiPalette.textSecondary)
            textSize = 13f
            text = label
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), 0)
            addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueText)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedCardDrawable(uiPalette.cardBackground, uiPalette.border, dp(12))
            addView(row)
            addView(
                seekBar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(4)
            lp.bottomMargin = dp(4)
            layoutParams = lp
        }
    }

    private fun simpleLabelValue(label: String, valueText: TextView): LinearLayout {
        val labelView = TextView(this).apply {
            setTextColor(uiPalette.textSecondary)
            textSize = 13f
            text = label
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedCardDrawable(uiPalette.cardBackground, uiPalette.border, dp(12))
            addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueText)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(4)
            lp.bottomMargin = dp(4)
            layoutParams = lp
        }
    }

    private fun simpleLabel(label: String): TextView {
        return TextView(this).apply {
            setTextColor(uiPalette.textPrimary)
            textSize = 14f
            text = label
            setPadding(dp(2), dp(8), dp(2), dp(4))
        }
    }

    private fun persistAndApplyConfig() {
        CuePreferences.saveDisplayConfig(this, config)
    }

    private fun toggleOverlay() {
        if (!OverlayControl.canDrawOverlays(this)) {
            openOverlayPermissionSettings()
            Toast.makeText(this, getString(R.string.toast_need_overlay_permission), Toast.LENGTH_SHORT).show()
            return
        }
        val shouldEnable = !CuePreferences.isOverlayEnabled(this)
        OverlayControl.setEnabled(this, shouldEnable)
        if (shouldEnable) {
            OverlayControl.start(this)
        } else {
            OverlayControl.stop(this)
        }
        refreshOverlayUiState()
    }

    private fun refreshOverlayUiState() {
        val hasOverlayPermission = OverlayControl.canDrawOverlays(this)
        val needSamplingPermission = config.colorMode != MotionCueView.ColorMode.PURE_DARK
        val hasSamplingPermission = ProjectionPermissionStore.get() != null
        val enabled = CuePreferences.isOverlayEnabled(this)
        val running = CuePreferences.isOverlayRunning(this)

        permissionText.text = if (hasOverlayPermission) {
            getString(R.string.overlay_permission_granted)
        } else {
            getString(R.string.overlay_permission_denied)
        }
        samplingText.text = if (!needSamplingPermission) {
            getString(R.string.sampling_permission_not_required)
        } else if (hasSamplingPermission) {
            getString(R.string.sampling_permission_granted)
        } else {
            getString(R.string.sampling_permission_denied)
        }
        overlayStateText.text = if (running) {
            getString(R.string.overlay_state_running)
        } else if (enabled) {
            getString(R.string.overlay_state_starting)
        } else {
            getString(R.string.overlay_state_stopped)
        }

        btnToggleOverlay.isEnabled = hasOverlayPermission
        btnToggleOverlay.text = if (enabled) {
            getString(R.string.btn_stop_overlay)
        } else {
            getString(R.string.btn_start_overlay)
        }
    }

    private fun openOverlayPermissionSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            Toast.makeText(this, getString(R.string.toast_need_overlay_permission), Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestScreenCapturePermission() {
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
    }

    private fun requestScreenCapturePermissionSafely() {
        runCatching {
            requestScreenCapturePermission()
        }.onFailure {
            Toast.makeText(this, getString(R.string.toast_sampling_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybeRunInitialPermissionFlow() {
        if (CuePreferences.isInitialPermissionRequested(this)) {
            return
        }
        CuePreferences.markInitialPermissionRequested(this)
        requestNotificationPermissionIfNeeded()

        val needsOverlay = !OverlayControl.canDrawOverlays(this)
        val needsSampling = config.colorMode != MotionCueView.ColorMode.PURE_DARK &&
            ProjectionPermissionStore.get() == null

        if (needsOverlay) {
            pendingInitialScreenCapture = needsSampling
            openOverlayPermissionSettings()
            return
        }

        if (needsSampling) {
            requestScreenCapturePermissionSafely()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private class ColorStylePreviewView(context: android.content.Context) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stripePaintA = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stripePaintB = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        private var scene: DemoBgScene = DemoBgScene.DARK
        private var customBgColor: Int = Color.BLACK
        private var style: DotStyle = DotStyle(
            fill = Color.WHITE,
            stroke = Color.BLACK,
            strokeWidthPx = 2f,
            halo = DotHaloStyle(Color.BLACK, 6f, 0.5f),
            metContrast = true,
            contrastValue = 7f,
            source = DotStyleSource.FINE
        )

        fun update(scene: DemoBgScene, customColor: Int, dotStyle: DotStyle) {
            this.scene = scene
            this.customBgColor = customColor
            style = dotStyle
            invalidate()
        }

        fun sampleCenterColor(scene: DemoBgScene, customColor: Int): Int {
            return when (scene) {
                DemoBgScene.LIGHT -> Color.rgb(245, 245, 245)
                DemoBgScene.DARK -> Color.rgb(18, 18, 18)
                DemoBgScene.CUSTOM -> customColor
                DemoBgScene.STRIPES -> {
                    var sumR = 0
                    var sumG = 0
                    var sumB = 0
                    var count = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val c = if (((dx + dy) and 1) == 0) {
                                Color.rgb(240, 240, 240)
                            } else {
                                Color.rgb(24, 24, 24)
                            }
                            sumR += Color.red(c)
                            sumG += Color.green(c)
                            sumB += Color.blue(c)
                            count += 1
                        }
                    }
                    Color.rgb(sumR / count, sumG / count, sumB / count)
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            when (scene) {
                DemoBgScene.LIGHT -> canvas.drawColor(Color.rgb(245, 245, 245))
                DemoBgScene.DARK -> canvas.drawColor(Color.rgb(18, 18, 18))
                DemoBgScene.CUSTOM -> canvas.drawColor(customBgColor)
                DemoBgScene.STRIPES -> drawStripes(canvas)
            }
            val cx = width * 0.5f
            val cy = height * 0.5f
            val radius = minOf(width, height) * 0.20f

            val halo = style.halo
            if (halo != null && halo.sizePx > 0f && halo.alpha > 0f) {
                haloPaint.color = halo.color
                haloPaint.alpha = (halo.alpha * 255f).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, radius + halo.sizePx, haloPaint)
            }

            val strokeColor = style.stroke
            if (strokeColor != null && style.strokeWidthPx > 0f) {
                strokePaint.color = strokeColor
                strokePaint.strokeWidth = style.strokeWidthPx
                strokePaint.alpha = 255
                canvas.drawCircle(cx, cy, radius + style.strokeWidthPx * 0.25f, strokePaint)
            }

            fillPaint.color = style.fill
            fillPaint.alpha = 255
            canvas.drawCircle(cx, cy, radius, fillPaint)
        }

        private fun drawStripes(canvas: Canvas) {
            stripePaintA.color = Color.rgb(240, 240, 240)
            stripePaintB.color = Color.rgb(24, 24, 24)
            val stripeW = (width / 8f).coerceAtLeast(1f)
            var x = 0f
            var toggle = false
            while (x < width) {
                canvas.drawRect(
                    x,
                    0f,
                    (x + stripeW).coerceAtMost(width.toFloat()),
                    height.toFloat(),
                    if (toggle) stripePaintA else stripePaintB
                )
                x += stripeW
                toggle = !toggle
            }
        }
    }
}
