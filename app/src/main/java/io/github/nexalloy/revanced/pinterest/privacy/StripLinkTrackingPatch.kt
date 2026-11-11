package io.github.nexalloy.revanced.pinterest.privacy

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook

private val LINK = Regex("https?://\\S+")
private val PINTEREST_HOSTS = listOf("pinterest.", "pin.it")

private fun clean(text: CharSequence): String = LINK.replace(text) { match ->
    val uri = Uri.parse(match.value)
    val host = uri.host?.lowercase().orEmpty()
    if (PINTEREST_HOSTS.none { host.contains(it) } || (uri.query == null && uri.fragment == null)) match.value
    else uri.buildUpon().clearQuery().fragment(null).build().toString()
}

val StripLinkTracking = patch(
    name = "Strip link tracking",
    description = "Removes tracking parameters from Pinterest links you copy or share. The link still opens the same pin.",
) {
    val putString = Intent::class.java.getMethod("putExtra", String::class.java, String::class.java)
    val putChars = Intent::class.java.getMethod("putExtra", String::class.java, CharSequence::class.java)
    val newPlainText = ClipData::class.java.getMethod("newPlainText", CharSequence::class.java, CharSequence::class.java)

    val shareHooks = listOf(putString, putChars).map { method ->
        ScopedFrameworkHook(method, before = { param ->
            if (param.args[0] == Intent.EXTRA_TEXT) (param.args[1] as? CharSequence)?.let { param.args[1] = clean(it) }
        })
    }
    val copyHook = ScopedFrameworkHook(newPlainText, before = { param ->
        (param.args[1] as? CharSequence)?.let { param.args[1] = clean(it) }
    })

    ::shareLinkScopesFingerprint.dexMethodList.forEach { scope ->
        val member = scope.getMethodInstance(classLoader)
        shareHooks.forEach { it.installOn(member) }
    }
    ::copyLinkScopesFingerprint.dexMethodList.forEach { scope -> copyHook.installOn(scope.getMethodInstance(classLoader)) }
}
