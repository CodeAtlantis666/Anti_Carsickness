package com.antcarsickness

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

object OverlayControl {

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun start(context: Context) {
        val token = ProjectionPermissionStore.get()
        val intent = OverlayCueService.createStartIntent(context)
        if (token != null) {
            intent.putExtra(OverlayCueService.EXTRA_PROJECTION_RESULT_CODE, token.resultCode)
            intent.putExtra(OverlayCueService.EXTRA_PROJECTION_DATA, token.dataIntent)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.stopService(OverlayCueService.createStartIntent(context))
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        CuePreferences.setOverlayEnabled(context, enabled)
        requestTileStateSync(context)
    }

    fun requestTileStateSync(context: Context) {
        TileService.requestListeningState(
            context,
            ComponentName(context, MotionCueTileService::class.java)
        )
    }
}
