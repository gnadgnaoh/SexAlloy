package io.github.nexalloy.revanced.discord.core

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

private const val FAKE_STATUS = 204
private const val TEXT = "text"

internal object Requests {
    val blockers = CopyOnWriteArrayList<(String, String) -> Boolean>()

    fun path(url: String): String {
        val start = url.indexOf("://").let { if (it < 0) 0 else url.indexOf('/', it + 3).let { s -> if (s < 0) url.length else s } }
        val end = url.indexOf('?', start).let { if (it < 0) url.length else it }
        return url.substring(start, end)
    }
}

internal val DiscordRequests = patch(name = "<DiscordRequests>") {
    val send = ::sendRequestFingerprint.method
    val response = ::responseReceivedFingerprint.method
    val data = ::dataReceivedFingerprint.method
    val success = ::requestSuccessFingerprint.method
    val context: Method = send.declaringClass.getMethod("getReactApplicationContext")
    send.hookMethod {
        before { param ->
            val method = param.args[0] as? String ?: return@before
            val url = param.args[1] as? String ?: return@before
            if (Requests.blockers.none { it(method, url) }) return@before
            val ctx = context.invoke(param.thisObject)
            val id = param.args[2] as Int
            val devId = param.args[9] as? String
            response.invoke(null, ctx, id, devId, url, FAKE_STATUS, emptyMap<String, String>(), 0L)
            data.invoke(null, ctx, id, devId, "", param.args[5] as? String ?: TEXT)
            success.invoke(null, ctx, id, devId, 0L)
            param.result = null
        }
    }
}
