package com.tulipskun.droidime

import android.content.Context
import android.os.Build
import android.provider.Settings

object DeviceEndpoint {
    const val HOST = "mcp.aixodia.dpdns.org"
    private const val PREFS = "droid_mcp"
    private const val KEY = "device_name"
    private val validName = Regex("^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?$")

    fun normalize(value: String): String =
        value.trim().lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')

    fun isValid(value: String): Boolean = validName.matches(value)

    fun getOrCreateName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY, null)?.let(::normalize).orEmpty()
        if (isValid(saved)) return saved
        val model = normalize(Build.MODEL).take(18).trim('-').ifBlank { "android" }
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            .orEmpty().takeLast(6).lowercase().replace(Regex("[^a-z0-9]"), "")
        val generated = normalize("$model-$androidId").take(32).trim('-').ifBlank { "android-device" }
        prefs.edit().putString(KEY, generated).apply()
        return generated
    }

    fun endpoint(deviceName: String): String =
        "https://$HOST/${normalize(deviceName)}"

    fun path(deviceName: String): String =
        "/${normalize(deviceName)}"
}
