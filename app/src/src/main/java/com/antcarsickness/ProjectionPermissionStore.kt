package com.antcarsickness

import android.content.Context
import android.content.Intent

data class ProjectionPermissionToken(
    val resultCode: Int,
    val dataIntent: Intent
)

object ProjectionPermissionStore {
    private const val PREFS_NAME = "projection_permission_store"
    private const val KEY_RESULT_CODE = "projection_result_code"
    private const val KEY_DATA_URI = "projection_data_uri"

    @Volatile
    private var token: ProjectionPermissionToken? = null
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (token == null) {
            loadFromPrefs()
        }
    }

    fun set(resultCode: Int, data: Intent) {
        val copied = Intent(data)
        token = ProjectionPermissionToken(resultCode, copied)
        saveToPrefs(resultCode, copied)
    }

    fun get(): ProjectionPermissionToken? {
        if (token == null) {
            loadFromPrefs()
        }
        return token
    }

    fun clear() {
        token = null
        val ctx = appContext ?: return
        runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_RESULT_CODE)
                .remove(KEY_DATA_URI)
                .apply()
        }
    }

    private fun saveToPrefs(resultCode: Int, data: Intent) {
        val ctx = appContext ?: return
        runCatching {
            val uri = data.toUri(Intent.URI_INTENT_SCHEME)
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_RESULT_CODE, resultCode)
                .putString(KEY_DATA_URI, uri)
                .apply()
        }
    }

    private fun loadFromPrefs() {
        val ctx = appContext ?: return
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val resultCode = prefs.getInt(KEY_RESULT_CODE, Int.MIN_VALUE)
            val dataUri = prefs.getString(KEY_DATA_URI, null)
            if (resultCode != Int.MIN_VALUE && !dataUri.isNullOrEmpty()) {
                val parsed = Intent.parseUri(dataUri, Intent.URI_INTENT_SCHEME)
                token = ProjectionPermissionToken(resultCode, parsed)
            }
        }
    }
}
