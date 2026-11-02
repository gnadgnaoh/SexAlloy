package io.github.nexalloy.revanced.discord.privacy

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.discord.core.DiscordRequests
import io.github.nexalloy.revanced.discord.core.Requests

private const val SENTRY_HOST = "sentry.io"
private val TRACKING_PATHS = listOf("/science", "/metrics")

val DisableTracking = patch(
    name = "Disable tracking",
    description = "Stops analytics, performance metrics, crash reports (Sentry), touch logging, AppsFlyer install tracking and reading your advertising ID.",
) {
    Requests.blockers += { _, url ->
        val path = Requests.path(url)
        url.contains(SENTRY_HOST) || TRACKING_PATHS.any { path.endsWith(it) || path.contains("$it/") }
    }
    dependsOn(DiscordRequests)
    ::crashReportingDisabledFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))
    ::enableTouchLoggingFingerprint.hookMethod(XC_MethodReplacement.DO_NOTHING)
    ::appsFlyerStartFingerprint.dexMethodList.forEach { it.hookMethod(XC_MethodReplacement.DO_NOTHING) }
    val empty = ::advertisingIdEmptyFingerprint.method.apply { isAccessible = true }
    ::advertisingIdFingerprint.hookMethod {
        before { param ->
            empty.invoke(param.thisObject, param.args[0])
            param.result = null
        }
    }
}
