package com.antcarsickness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class OverlayCueService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val ACTION_START = "com.antcarsickness.action.START"
        private const val ACTION_STOP = "com.antcarsickness.action.STOP"
        private const val CHANNEL_ID = "motion_cues_overlay"
        private const val NOTIFICATION_ID = 3017
        const val EXTRA_PROJECTION_RESULT_CODE = "extra_projection_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"

        fun createStartIntent(context: Context): Intent {
            return Intent(context, OverlayCueService::class.java).setAction(ACTION_START)
        }

        private fun createStopIntent(context: Context): Intent {
            return Intent(context, OverlayCueService::class.java).setAction(ACTION_STOP)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var cueView: MotionCueView? = null
    private lateinit var engine: VehicleMotionEngine
    private var screenSampler: ScreenLumaSampler? = null

    override fun onCreate() {
        super.onCreate()
        ProjectionPermissionStore.init(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = CuePreferences.sharedPrefs(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        engine = VehicleMotionEngine(this) { state ->
            runCatching { cueView?.updateMotion(state) }
        }

        val foregroundReady = runCatching {
            ensureForeground()
            true
        }.getOrDefault(false)
        if (!foregroundReady) {
            stopSelf()
            return
        }
        CuePreferences.setOverlayRunning(this, true)
        OverlayControl.requestTileStateSync(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return runCatching {
        when (intent?.action) {
            ACTION_STOP -> {
                CuePreferences.setOverlayEnabled(this, false)
                stopSelf()
                return@runCatching START_NOT_STICKY
            }
        }

        if (!OverlayControl.canDrawOverlays(this) || !CuePreferences.isOverlayEnabled(this)) {
            stopSelf()
            return@runCatching START_NOT_STICKY
        }

        val projectionCode = intent?.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Int.MIN_VALUE)
        val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }
        if (projectionCode != null && projectionCode != Int.MIN_VALUE && projectionData != null) {
            ProjectionPermissionStore.set(projectionCode, projectionData)
        }

        ensureOverlayAttached()
        applyDisplayConfig()
        engine.start()
        START_STICKY
        }.getOrElse {
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        engine.stop()
        stopScreenSampler()
        removeOverlay()
        CuePreferences.setOverlayRunning(this, false)
        OverlayControl.requestTileStateSync(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == CuePreferences.KEY_OVERLAY_ENABLED && !CuePreferences.isOverlayEnabled(this)) {
            stopSelf()
            return
        }
        applyDisplayConfig()
    }

    private fun applyDisplayConfig() {
        val view = cueView ?: return
        val config = runCatching { CuePreferences.loadDisplayConfig(this) }.getOrNull() ?: return
        runCatching { CuePreferences.applyDisplayConfig(view, config, subtleMode = true) }
        if (config.colorMode != MotionCueView.ColorMode.PURE_DARK) {
            ensureScreenSampler()
        } else {
            stopScreenSampler()
            view.setColorSampler(null)
        }
    }

    private fun ensureOverlayAttached() {
        if (cueView != null) {
            return
        }
        if (!OverlayControl.canDrawOverlays(this)) {
            return
        }

        val view = MotionCueView(this).apply {
            setSubtleMode(true)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Keep overlay opacity below touch-obscuring threshold so touches pass through reliably.
            alpha = 0.79f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val attached = runCatching {
            windowManager.addView(view, params)
            true
        }.getOrElse { false }
        if (attached) {
            cueView = view
        } else {
            cueView = null
            stopSelf()
        }
    }

    private fun ensureScreenSampler() {
        val token = ProjectionPermissionStore.get()
        val view = cueView ?: return
        if (token == null) {
            view.setColorSampler(null)
            return
        }
        if (screenSampler == null) {
            screenSampler = ScreenLumaSampler(this)
        }
        val started = runCatching { screenSampler?.start(token.resultCode, token.dataIntent) == true }
            .getOrDefault(false)
        if (started) {
            view.setColorSampler { x, y, w, h ->
                runCatching { screenSampler?.sampleColor(x, y, w, h) }.getOrNull()
            }
        } else {
            // Stored token may be stale after process recreation; mark as unavailable
            // so UI can request a fresh one when needed.
            ProjectionPermissionStore.clear()
            view.setColorSampler(null)
        }
    }

    private fun stopScreenSampler() {
        runCatching { screenSampler?.stop() }
        screenSampler = null
    }

    private fun removeOverlay() {
        val view = cueView ?: return
        runCatching {
            windowManager.removeViewImmediate(view)
        }
        cueView = null
    }

    private fun ensureForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = getString(R.string.notification_channel_desc)
            }
            manager.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            createStopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_action_stop), stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val foregroundTypes =
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(
                NOTIFICATION_ID,
                notification,
                foregroundTypes
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
