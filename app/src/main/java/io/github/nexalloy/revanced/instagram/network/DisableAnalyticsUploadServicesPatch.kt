package io.github.nexalloy.revanced.instagram.network

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.nexalloy.patch

private const val PREFS = "nexalloy_ig_components"
private const val KEY_VERSION = "disabled_for"

private val ANALYTICS_UPLOAD_COMPONENTS = listOf(
    "com.instagram.analytics.uploadscheduler.AnalyticsUploadAlarmReceiver",
    "com.facebook.analytics2.fabric.onefabric.OneFabricUploadAlarmReceiver",
    "com.facebook.analytics2.fabric.onefabric.FFAlarmUploadJobService",
    "com.google.android.gms.analytics.AnalyticsService",
    "com.google.android.gms.analytics.AnalyticsJobService",
    "com.facebook.analytics2.logger.service.LollipopUploadSafeService",
    "com.facebook.analytics2.logger.legacy.uploader.LollipopUploadService",
    "com.facebook.analytics2.logger.legacy.uploader.Analytics2UploadService",
    "com.facebook.analytics2.logger.legacy.uploader.AlarmBasedUploadService",
    "com.facebook.analytics2.logger.legacy.uploader.HighPriUploadRetryReceiver",
)

val DisableAnalyticsUploadServices = patch(
    name = "Disable analytics upload services",
    description = "Turns off the background services that upload analytics, so Instagram is not woken up to send them. " +
        "They stay off until Instagram's data is cleared or the app is reinstalled.",
) {
    disableUploadComponents(appContext)
}

private fun disableUploadComponents(app: Application) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || Application.getProcessName() != app.packageName) return
    val pm = app.packageManager
    val version = pm.getPackageInfo(app.packageName, 0).longVersionCode
    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (prefs.getLong(KEY_VERSION, -1L) == version) return
    ANALYTICS_UPLOAD_COMPONENTS.forEach { name ->
        val component = ComponentName(app.packageName, name)
        runCatching {
            if (pm.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
    prefs.edit().putLong(KEY_VERSION, version).apply()
}
