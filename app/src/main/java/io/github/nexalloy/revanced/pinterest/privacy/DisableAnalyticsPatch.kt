package io.github.nexalloy.revanced.pinterest.privacy

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private val BLOCKED_TASKS = setOf(
    "TAG_APPSFLYER_INIT",
    "TAG_FIREBASE_ANALYTICS_INIT",
    "TAG_GOOGLE_ENGAGE_INIT",
    "TAG_CRASH_REPORTING",
    "TAG_RUM_REPORTING",
    "TAG_ADS_GMA_MANAGER_INIT",
    "TAG_ADS_OPEN_MEASUREMENT_SDK_INIT",
    "TAG_ADS_APP_INSTALL_LOG",
    "TAG_LANDING_SIGNALS_UPLOAD",
    "TAG_LOG_APP_EXIT",
    "TAG_LOG_DEVICE_PROFILE",
    "TAG_LOG_ENTRY_POINT",
    "TAG_LOG_LOCATION_PERMISSIONS",
    "TAG_LOG_REPORT_FULLY_DRAWN",
    "TAG_SCHEDULE_SUBMIT_NETWORK_METRICS",
    "TAG_TRACKING_REQUESTS",
)
private val APPS_FLYER_CALLS = setOf("start", "logEvent", "logSession", "logLocation", "logAdRevenue")
private val SKIPPED = Runnable { }

val DisableAnalytics = patch(
    name = "Disable analytics",
    description = "Stops AppsFlyer, Firebase Analytics, Google Engage, crash and performance reporting, ad measurement and Pinterest's startup tracking tasks.",
) {
    ::startupTaskConstructorsFingerprint.dexMethodList.forEach { constructor ->
        constructor.hookMethod {
            before { param ->
                val tag = param.args.firstOrNull { it is Enum<*> } as? Enum<*> ?: return@before
                if (tag.name !in BLOCKED_TASKS) return@before
                val slot = param.args.indexOfFirst { it is Runnable }
                if (slot >= 0) param.args[slot] = SKIPPED
            }
        }
    }

    ::appsFlyerImplFingerprint.clazz.declaredMethods
        .filter { it.name in APPS_FLYER_CALLS && it.returnType == Void.TYPE }
        .forEach { it.hookMethod { before { param -> param.result = null } } }

    ::engageWorkFingerprint.hookMethod {
        before { param -> param.throwable = IllegalStateException("Google Engage disabled") }
    }
}
