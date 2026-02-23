package com.antcarsickness

import android.content.Context
import android.content.Intent
import android.os.Parcel
import android.util.Base64

data class ProjectionPermissionToken(
    val resultCode: Int,
    val dataIntent: Intent
)

object ProjectionPermissionStore {
    private const val PREFS_NAME = "projection_permission_store"
    private const val KEY_RESULT_CODE = "projection_result_code"
    private const val KEY_DATA_URI = "projection_data_uri"
    private const val KEY_DATA_PARCEL_BASE64 = "projection_data_parcel_base64"

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
            val parcelB64 = intentToBase64(data)
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_RESULT_CODE, resultCode)
                .putString(KEY_DATA_URI, uri)
                .putString(KEY_DATA_PARCEL_BASE64, parcelB64)
                .apply()
        }
    }

    private fun loadFromPrefs() {
        val ctx = appContext ?: return
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val resultCode = prefs.getInt(KEY_RESULT_CODE, Int.MIN_VALUE)
            val dataParcelB64 = prefs.getString(KEY_DATA_PARCEL_BASE64, null)
            val dataUri = prefs.getString(KEY_DATA_URI, null)
            if (resultCode != Int.MIN_VALUE) {
                val parsed = when {
                    !dataParcelB64.isNullOrEmpty() -> base64ToIntent(dataParcelB64)
                    !dataUri.isNullOrEmpty() -> Intent.parseUri(dataUri, Intent.URI_INTENT_SCHEME)
                    else -> null
                }
                if (parsed != null) {
                    token = ProjectionPermissionToken(resultCode, parsed)
                }
            }
        }
    }

    private fun intentToBase64(intent: Intent): String {
        val parcel = Parcel.obtain()
        return try {
            intent.writeToParcel(parcel, 0)
            Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
        } finally {
            parcel.recycle()
        }
    }

    private fun base64ToIntent(encoded: String): Intent? {
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                Intent.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
        }.getOrNull()
    }
}
