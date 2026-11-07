package io.github.nexalloy.revanced.pinterest.media

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.io.Closeable
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

private const val ORIGINALS = "orig"
private const val ORIGINALS_SEGMENT = "originals"
private const val FALLBACK_SEGMENT = "1200x"
private const val FALLBACK_WIDTH = 1200
private const val TIER_CACHE_SIZE = 2000
private val SIZED_IMAGE = Regex("^(https?://i\\.pinimg\\.com/)(\\d+x\\d*(?:_RS)?)(/.+)$")
private val SEGMENT_WIDTH = Regex("^(\\d+)")

private object ImageLog {
    fun i(message: String) = XposedBridge.log("NexAlloy/PN-img: $message")
}

private class Attempt(
    val original: Any,
    val size: String,
    val path: String,
    val candidates: List<String>,
    var tier: Int,
    val priority: Any?,
    val callback: Any?,
)

val OriginalImages = patch(
    name = "Original images",
    description = "Shows every pin image at its original resolution across the app. Uses noticeably more data.",
) {
    ::imageSizesFingerprint.hookMethod {
        after { param ->
            @Suppress("UNCHECKED_CAST")
            (param.result as? MutableSet<Any?>)?.add(ORIGINALS)
        }
    }

    fun upgrade(field: Field) = { param: XC_MethodHook.MethodHookParam ->
        val images = param.result as? Map<*, *>
        val original = images?.get(ORIGINALS)
        if (images != null && original != null && !images.values.all { it === original }) {
            val upgraded = LinkedHashMap<Any?, Any?>(images.size).apply { images.keys.forEach { put(it, original) } }
            field.set(param.thisObject, upgraded)
            param.result = upgraded
        }
    }

    val pinUpgrade = upgrade(::pinImagesFieldFingerprint.field.apply { isAccessible = true })
    val slotUpgrade = upgrade(::slotImagesFieldFingerprint.field.apply { isAccessible = true })
    ::pinImagesFingerprint.hookMethod { after { pinUpgrade(it) } }
    ::slotImagesFingerprint.hookMethod { after { slotUpgrade(it) } }

    val load = ::glideFetcherLoadFingerprint.method.apply { isAccessible = true }
    val urlField = ::glideFetcherUrlFieldFingerprint.field.apply { isAccessible = true }
    val headersField = ::glideUrlHeadersFieldFingerprint.field.apply { isAccessible = true }
    val newUrl = ::glideUrlWithHeadersFingerprint.constructor.apply { isAccessible = true }
    val responseCode = ::imageResponseCodeFingerprint.field.apply { isAccessible = true }
    val attempts = Collections.synchronizedMap(WeakHashMap<Any, Attempt>())
    val tiers = object : LinkedHashMap<String, Int>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > TIER_CACHE_SIZE
    }

    fun point(fetcher: Any, attempt: Attempt) {
        val url = attempt.candidates.getOrNull(attempt.tier)
        urlField.set(fetcher, if (url == null) attempt.original else newUrl.newInstance(url, headersField.get(attempt.original)))
    }

    fun advance(fetcher: Any, attempt: Attempt, reason: String): Boolean {
        if (attempt.tier >= attempt.candidates.size) return false
        ImageLog.i("${attempt.size}: ${attempt.candidates[attempt.tier]} $reason")
        attempt.tier++
        if (attempt.tier >= attempt.candidates.size) {
            synchronized(tiers) { tiers[attempt.path] = attempt.candidates.size }
            ImageLog.i("${attempt.size} kept ${attempt.path}")
        }
        point(fetcher, attempt)
        load.invoke(fetcher, attempt.priority, attempt.callback)
        return true
    }

    load.hookMethod {
        before { param ->
            val fetcher = param.thisObject
            if (attempts.containsKey(fetcher)) return@before
            val glideUrl = urlField.get(fetcher) ?: return@before
            val (host, size, path) = SIZED_IMAGE.find(glideUrl.toString())?.destructured ?: return@before
            val width = SEGMENT_WIDTH.find(size)?.value?.toIntOrNull() ?: 0
            val candidates = listOfNotNull(
                host + ORIGINALS_SEGMENT + path,
                (host + FALLBACK_SEGMENT + path).takeIf { width < FALLBACK_WIDTH },
            )
            val start = synchronized(tiers) { tiers[path] } ?: 0
            if (start >= candidates.size) return@before
            val attempt = Attempt(glideUrl, size, path, candidates, start, param.args[0], param.args[1])
            attempts[fetcher] = attempt
            point(fetcher, attempt)
        }
    }

    ::glideFetcherResponseFingerprint.hookMethod {
        before { param ->
            val fetcher = param.thisObject
            val attempt = attempts[fetcher] ?: return@before
            val response = param.args[1] ?: return@before
            val code = responseCode.getInt(response)
            if (code in 200..299 || attempt.tier >= attempt.candidates.size) {
                attempts.remove(fetcher)
                if (code in 200..299 && attempt.tier < attempt.candidates.size) {
                    synchronized(tiers) { tiers[attempt.path] = attempt.tier }
                    ImageLog.i("${attempt.size} -> ${if (attempt.tier == 0) ORIGINALS_SEGMENT else FALLBACK_SEGMENT} ($code) ${attempt.path}")
                }
                return@before
            }
            (response as? Closeable)?.close()
            if (advance(fetcher, attempt, "returned $code")) param.result = null
        }
    }

    ::glideFetcherFailureFingerprint.hookMethod {
        before { param ->
            val fetcher = param.thisObject
            val attempt = attempts[fetcher] ?: return@before
            if (attempt.tier >= attempt.candidates.size) {
                attempts.remove(fetcher)
                return@before
            }
            if (advance(fetcher, attempt, "failed: ${param.args[1]}")) param.result = null
        }
    }
}
