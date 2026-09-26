package com.vaani.android.utils

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.vaani.android.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A stable per-install device identifier plus hardware/OS/app details, sent to the backend on
 * every WebSocket connect so the admin panel can show which physical device/counter is making
 * requests -- essential once a deployment has more than one counter, or when tracking down which
 * device is misbehaving.
 *
 * Uses a self-generated UUID (persisted in SharedPreferences) rather than ANDROID_ID: it's
 * equally unique per install, and doesn't carry ANDROID_ID's platform-version-dependent
 * scoping/reset quirks (e.g. resetting on a signing-key change on some OEM builds).
 */
@Singleton
class DeviceInfo @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val deviceId: String by lazy {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { newId ->
            prefs.edit { putString(KEY_DEVICE_ID, newId) }
        }
    }

    val deviceModel: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"
    val osVersion: String get() = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val appVersion: String get() = BuildConfig.VERSION_NAME

    companion object {
        private const val PREFS_NAME = "vaani_device"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
