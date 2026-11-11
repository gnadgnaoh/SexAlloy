package io.github.nexalloy.revanced.instagram.tracking

import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private const val FIRST_PARTY = "instagram.com"
private const val REDIRECT_HOST = "l.instagram.com"
private val KEEP = setOf("comment_id", "img_index", "open_comments", "story_media_id")
private val TRACKING = setOf("igsh", "igsi", "ig_rid", "fbclid", "si", "stkn", "mdxt", "cplk", "obrf", "vrfl", "rpxt")
private val URL = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
private val TEXT_EXTRAS = arrayOf(Intent.EXTRA_TEXT, Intent.EXTRA_HTML_TEXT)

val SanitizeTrackingLinks = patch(
    name = "Sanitize tracking links",
    description = "Removes tracking parameters from every link Instagram copies or shares to other apps.",
) {
    ClipboardManager::class.java.getDeclaredMethod("setPrimaryClip", ClipData::class.java).hookMethod {
        before { param ->
            val clip = param.args[0] as? ClipData ?: return@before
            cleanClip(clip)?.let { param.args[0] = it }
        }
    }

    Instrumentation::class.java.declaredMethods
        .filter { it.name == "execStartActivity" || it.name == "execStartActivities" }
        .forEach { method ->
            val single = method.parameterTypes.indexOf(Intent::class.java)
            val many = method.parameterTypes.indexOf(Array<Intent>::class.java)
            if (single < 0 && many < 0) return@forEach
            method.hookMethod {
                before { param ->
                    if (single >= 0) (param.args[single] as? Intent)?.let(::cleanIntent)
                    if (many >= 0) (param.args[many] as? Array<*>)?.forEach { (it as? Intent)?.let(::cleanIntent) }
                }
            }
        }
}

private fun cleanIntent(intent: Intent) {
    when (intent.action) {
        Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> cleanShare(intent)
        Intent.ACTION_CHOOSER -> {
            @Suppress("DEPRECATION")
            val target = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            if (cleanShare(target)) intent.putExtra(Intent.EXTRA_INTENT, target)
        }
    }
}

private fun cleanShare(intent: Intent): Boolean {
    var changed = false
    for (key in TEXT_EXTRAS) {
        val text = intent.getCharSequenceExtra(key)?.toString() ?: continue
        val clean = sanitizeText(text)
        if (clean != text) {
            intent.putExtra(key, clean)
            changed = true
        }
    }
    intent.clipData?.let { clip -> cleanClip(clip)?.let { intent.clipData = it; changed = true } }
    return changed
}

private fun cleanClip(clip: ClipData): ClipData? {
    var changed = false
    val items = (0 until clip.itemCount).map { i ->
        val item = clip.getItemAt(i)
        val text = item.text?.toString()
        val html = item.htmlText
        val cleanText = text?.let(::sanitizeText)
        val cleanHtml = html?.let(::sanitizeText)
        if (cleanText == text && cleanHtml == html) item
        else {
            changed = true
            if (html != null) ClipData.Item(cleanText ?: "", cleanHtml) else ClipData.Item(cleanText, item.intent, item.uri)
        }
    }
    if (!changed) return null
    return ClipData(clip.description, items.first()).apply { items.drop(1).forEach(::addItem) }
}

internal fun sanitizeText(text: String): String {
    if (!text.contains("://")) return text
    return URL.replace(text) { cleanUrl(it.value) }
}

private fun cleanUrl(url: String): String {
    val uri = Uri.parse(url)
    val host = uri.host?.lowercase() ?: return url
    if (host == REDIRECT_HOST) {
        val target = runCatching { uri.getQueryParameter("u") }.getOrNull()
        if (target != null && target.startsWith("http", ignoreCase = true)) return cleanUrl(target)
    }
    val queryStart = url.indexOf('?')
    if (queryStart < 0) return url
    val fragmentStart = url.indexOf('#', queryStart).let { if (it < 0) url.length else it }
    val firstParty = host == FIRST_PARTY || host.endsWith(".$FIRST_PARTY")
    val query = url.substring(queryStart + 1, fragmentStart)
    val kept = query.split('&').filter { part ->
        if (part.isEmpty()) return@filter false
        val name = part.substringBefore('=').lowercase()
        if (firstParty) name in KEEP else name !in TRACKING && !name.startsWith("utm_")
    }
    if (kept.size == query.split('&').size) return url
    val base = url.substring(0, queryStart)
    val fragment = url.substring(fragmentStart)
    return if (kept.isEmpty()) base + fragment else base + "?" + kept.joinToString("&") + fragment
}
