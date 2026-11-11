package io.github.nexalloy.revanced.instagram.network

import android.app.Application
import android.net.Uri
import android.os.Build
import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook
import io.github.nexalloy.revanced.shared.tigon.TigonRequestHook
import io.github.nexalloy.revanced.shared.tigon.addTigonRequestRule
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Modifier

val BlockNetwork = patch(
    name = "Block ads and analytics",
    description = "Blocks ads and analytics network requests for Feed, Reels, Stories, and Explore, and stops analytics events from piling up on the device.",
) {
    addTigonRequestRule("BlockNetwork", ::isAdOrAnalyticsRequest)
    dependsOn(TigonRequestHook)
    blockAnalyticsRoutes()
    blockFalcoLogging()
}

private fun isAdOrAnalyticsRequest(host: String, path: String): Boolean =
    path.startsWith("/api/v1/ads/") ||
        path.contains("/async_ads/") ||
        path.contains("/feed/injected_reels_media/") ||
        path.contains("/profile_ads/get_profile_ads/") ||
        path.contains("/clips_viewer_feed_sa_multi_ads_watch_and_browse") ||
        path.contains("/async_ads_event") ||
        host.contains("graph.instagram.com") ||
        path.contains("/logging_client_events") ||
        path.contains("/pigeon_nest")

private const val FALCO_LOGGER = "com.facebook.falco.fabric.FFSingletonJNILogger"
private val FALCO_LOG_METHODS = setOf(
    "log", "logBatched", "logBatchedCompressed", "logBatchedMixed",
    "logWithCollectionControlDecision", "logWithPlatformSampling",
)
private const val FALCO_STORE = "app_analytics/flogger/FFLogger.sql"
private val FALCO_STORE_SIDECARS = listOf("", "-wal", "-shm", "-journal")
private const val FALCO_STORE_LIMIT = 5L * 1024 * 1024

private const val REFUSED = "https://127.0.0.1:9"
private const val GRAPH_LOGGING_ENDPOINT = "https://graph.facebook.com/logging_client_events"
private const val REPORT_HOST = "https://b-www.facebook.com/"

private fun refused(url: String): String {
    val scheme = url.indexOf("://")
    val path = url.indexOf('/', if (scheme < 0) 0 else scheme + 3)
    return REFUSED + if (path < 0) "/" else url.substring(path)
}

private fun PatchExecutor.blockAnalyticsRoutes() {
    val missing = mutableListOf<String>()
    fun <T> layer(name: String, find: () -> T): T? =
        runCatching(find).onFailure { missing += name; Logger.printInfo { "Instagram analytics: $name not found: ${it.message}" } }.getOrNull()

    val builder = layer("event address builder") { ::analyticsEndpointBuilderFingerprint.method }
    val pings = layer("Lacrima pings") { ::lacrimaPingSendersFingerprint.dexMethodList.map { it.toMethod() } }
    val reportBuilder = layer("Lacrima report builder") { ::reportAddressBuilderFingerprint.method }
    val reportReaders = layer("Lacrima report readers") { ::reportAddressReadersFingerprint.dexMethodList.map { it.toMethod() } }
    val mqtt = layer("MQTT settings") { ::mqttSettingsConstructorFingerprint.constructor }
    val stream = layer("Falco event stream") {
        ::eventStreamSwitchFieldFingerprint.field.apply { isAccessible = true } to
            ::eventSettingsConstructorsFingerprint.dexMethodList.map { it.toConstructor() }
    }

    builder?.hookMethod { after { param -> (param.result as? String)?.let { param.result = refused(it) } } }

    pings?.forEach { it.hookMethod(XC_MethodReplacement.DO_NOTHING) }

    reportBuilder?.hookMethod {
        after { param -> (param.result as? Uri)?.let { param.result = Uri.parse(refused(it.toString())) } }
    }
    if (!reportReaders.isNullOrEmpty()) {
        val parse = Uri::class.java.getDeclaredMethod("parse", String::class.java)
        val swap = ScopedFrameworkHook(parse, before = { param ->
            (param.args[0] as? String)?.takeIf { it.startsWith(REPORT_HOST) }?.let { param.args[0] = refused(it) }
        })
        reportReaders.forEach { swap.installOn(it) }
    }

    mqtt?.hookMethod {
        after { param ->
            val json = param.args[0] as? JSONObject ?: return@after
            val endpoint = json.optString("analytics_endpoint", GRAPH_LOGGING_ENDPOINT)
            var type: Class<*>? = param.thisObject.javaClass
            while (type != null && type != Any::class.java) {
                type.declaredFields.filter { it.type == String::class.java && !Modifier.isStatic(it.modifiers) }.forEach { f ->
                    f.isAccessible = true
                    val value = f.get(param.thisObject) as? String ?: return@forEach
                    if (value == endpoint || value == GRAPH_LOGGING_ENDPOINT) f.set(param.thisObject, refused(value))
                }
                type = type.superclass
            }
        }
    }

    stream?.let { (switch, constructors) ->
        constructors.forEach { c -> c.hookMethod { after { param -> switch.setBoolean(param.thisObject, false) } } }
    }

    check(missing.size < 6) { "no analytics route found" }
    if (missing.isNotEmpty()) Logger.printInfo { "Instagram analytics: routes not present: ${missing.joinToString()}" }
}

private fun PatchExecutor.blockFalcoLogging() {
    val methods = classLoader.loadClass(FALCO_LOGGER).declaredMethods
        .filter { it.name in FALCO_LOG_METHODS && Modifier.isNative(it.modifiers) }
    check(methods.map { it.name }.toSet() == FALCO_LOG_METHODS) {
        "Falco log methods changed: ${methods.map { it.name }}"
    }
    methods.forEach { it.hookMethod(XC_MethodReplacement.DO_NOTHING) }
    trimFalcoStore(appContext)
}

private fun trimFalcoStore(app: Application) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || Application.getProcessName() != app.packageName) return
    val store = File(app.applicationInfo.dataDir, FALCO_STORE)
    if (store.length() <= FALCO_STORE_LIMIT) return
    FALCO_STORE_SIDECARS.forEach { File(store.path + it).delete() }
}
