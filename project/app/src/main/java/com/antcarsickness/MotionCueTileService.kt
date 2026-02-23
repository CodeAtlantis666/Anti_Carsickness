package com.antcarsickness

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class MotionCueTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (!OverlayControl.canDrawOverlays(this)) {
            openMainActivity()
            return
        }

        val shouldEnable = !CuePreferences.isOverlayEnabled(this)
        val config = CuePreferences.loadDisplayConfig(this)
        val requiresSampling = config.colorMode != MotionCueView.ColorMode.PURE_DARK
        if (shouldEnable && requiresSampling && ProjectionPermissionStore.get() == null) {
            OverlayControl.setEnabled(this, true)
            openMainActivity()
            refreshTile()
            return
        }
        OverlayControl.setEnabled(this, shouldEnable)
        if (shouldEnable) {
            OverlayControl.start(this)
        } else {
            OverlayControl.stop(this)
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.qs_tile_label)

        val overlayPermissionGranted = OverlayControl.canDrawOverlays(this)
        val enabled = CuePreferences.isOverlayEnabled(this)

        tile.state = when {
            !overlayPermissionGranted -> Tile.STATE_UNAVAILABLE
            enabled -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.contentDescription = if (tile.state == Tile.STATE_ACTIVE) {
            getString(R.string.overlay_running)
        } else {
            getString(R.string.overlay_stopped)
        }
        tile.updateTile()
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                43,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
