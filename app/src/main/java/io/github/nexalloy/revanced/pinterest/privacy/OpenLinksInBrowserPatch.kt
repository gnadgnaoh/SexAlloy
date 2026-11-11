package io.github.nexalloy.revanced.pinterest.privacy

import android.content.Intent
import android.net.Uri
import io.github.nexalloy.patch

private val PINTEREST_HOSTS = listOf("pinterest.", "pin.it", "pinimg.com")

val OpenLinksInBrowser = patch(
    name = "Open links in browser",
    description = "Opens outside links from pins and profiles in your default browser instead of Pinterest's in-app browser. Pinterest links stay in the app.",
) {
    val navigation = classLoader.loadClass(NAVIGATION)
    val location = navigation.methods.single { it.parameterCount == 0 && it.returnType.name == SCREEN_LOCATION }
    val target = navigation.getMethod("getId")
    val screenClass = classLoader.loadClass(SCREEN_LOCATION).getMethod("getScreenClass")

    ::navigationPostFingerprint.hookMethod {
        before { param ->
            val nav = param.args[0]?.takeIf { navigation.isInstance(it) } ?: return@before
            val screen = location.invoke(nav)?.let { screenClass.invoke(it) as? Class<*> } ?: return@before
            if (screen.name != IN_APP_BROWSER) return@before
            val uri = (target.invoke(nav) as? String)?.let(Uri::parse) ?: return@before
            val host = uri.host?.lowercase() ?: return@before
            if (uri.scheme !in setOf("http", "https") || PINTEREST_HOSTS.any { host.contains(it) }) return@before
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { appContext.startActivity(intent) }.isSuccess) param.result = null
        }
    }
}
