package io.github.nexalloy.revanced.pinterest.download

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.whatsapp.core.WaUi
import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val MODEL_PACKAGE = "com.pinterest."
private const val MP4 = ".mp4"
private const val URL_KEY = "url"
private const val WIDTH_KEY = "width"
private const val IMAGES_KEY = "images"
private const val ORIGINAL_KEY = "orig"
private const val MEDIUM_IMAGE_KEY = "image_medium_url"
private const val ORIGINALS = "/originals/"
private val SIZE_SEGMENT = Regex("/\\d+x\\d*(_RS)?/")
private const val FOLDER = "Pinterest"
private const val MAX_DEPTH = 10
private val FORCED = listOf("isVideo=" to false, "isStoryPin=" to false, "isImageDownloadAllowed=" to true, "isEligibleForCloseupSharesheetDownload=" to false)
private val NAME_WIDTH = Regex("_(\\d+)w(?=[_.])")
private val KEY_TIER = Regex("_T(\\d+)(?:_|$)")
private val NAME_TIER = Regex("_t(\\d+)\\.mp4$")
private const val M3U8 = ".m3u8"
private const val HLS_SEGMENT = "/hls/"
private const val HLS_FALLBACK = "/720p/"
private const val CAROUSEL_KEY = "carousel_data"
private const val SLOTS_KEY = "carousel_slots"
private const val INDEX_KEY = "index"
private const val RESTORE = "nexalloy-restore"

private object PinMedia {
    private val keys = ConcurrentHashMap<Class<*>, Map<String, Field>>()
    private val nested = ConcurrentHashMap<Class<*>, List<Field>>()

    private fun jsonName(field: Field): String? = field.declaredAnnotations.firstNotNullOfOrNull { annotation ->
        runCatching { annotation.annotationClass.java.getDeclaredMethod("value").invoke(annotation) as? String }.getOrNull()
    }

    private fun keysOf(type: Class<*>) = keys.getOrPut(type) {
        type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
            .mapNotNull { f -> jsonName(f)?.let { it to f.apply { isAccessible = true } } }.toMap()
    }

    private fun nestedOf(type: Class<*>) = nested.getOrPut(type) {
        type.declaredFields.filter { f ->
            !Modifier.isStatic(f.modifiers) && !f.type.isPrimitive &&
                (f.type.name.startsWith(MODEL_PACKAGE) || f.type == Any::class.java || List::class.java.isAssignableFrom(f.type) || Map::class.java.isAssignableFrom(f.type))
        }.onEach { it.isAccessible = true }
    }

    fun imageUrls(pin: Any): List<String> {
        val fields = keysOf(pin.javaClass)
        val sizes = (fields[IMAGES_KEY]?.get(pin) as? Map<*, *>).orEmpty().mapNotNull { (key, detail) ->
            detail ?: return@mapNotNull null
            val detailFields = keysOf(detail.javaClass)
            val link = detailFields[URL_KEY]?.get(detail) as? String ?: return@mapNotNull null
            Triple(key as? String, link, (detailFields[WIDTH_KEY]?.get(detail) as? Number)?.toInt() ?: 0)
        }
        val orig = sizes.firstOrNull { it.first == ORIGINAL_KEY }?.second
        val largest = sizes.maxByOrNull { it.third }?.second ?: fields[MEDIUM_IMAGE_KEY]?.get(pin) as? String
        val rewritten = largest?.let { SIZE_SEGMENT.replaceFirst(it, ORIGINALS) }
        return listOfNotNull(orig, rewritten, largest).distinct()
    }

    private data class Mp4(val link: String, val nameWidth: Int, val tier: Int, val fieldWidth: Int)

    private val rank = compareBy<Mp4>({ it.nameWidth }, { it.tier }, { it.fieldWidth })

    fun videoUrls(pin: Any): List<String> {
        val mp4s = mutableListOf<Mp4>()
        val hls = linkedSetOf<String>()
        val seen = IdentityHashMap<Any, Boolean>()
        fun visit(node: Any?, depth: Int, key: String?) {
            if (node == null || depth > MAX_DEPTH || seen.put(node, true) != null) return
            when (node) {
                is List<*> -> node.forEach { visit(it, depth + 1, key) }
                is Map<*, *> -> node.forEach { (k, v) -> visit(v, depth + 1, k as? String ?: key) }
                else -> {
                    val type = node.javaClass
                    if (!type.name.startsWith(MODEL_PACKAGE)) return
                    val fields = keysOf(type)
                    val link = fields[URL_KEY]?.get(node) as? String
                    val bare = link?.substringBefore('?')
                    if (link != null && bare != null && bare.endsWith(MP4)) {
                        val name = bare.substringAfterLast('/')
                        val mp4 = Mp4(
                            link,
                            NAME_WIDTH.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                            key?.let { KEY_TIER.find(it) }?.groupValues?.get(1)?.toIntOrNull()
                                ?: NAME_TIER.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                            (fields[WIDTH_KEY]?.get(node) as? Number)?.toInt() ?: 0,
                        )
                        mp4s += mp4
                    } else if (link != null && bare != null && bare.endsWith(M3U8)) {
                        hls += link
                    }
                    nestedOf(type).forEach { f -> visit(f.get(node), depth + 1, key) }
                }
            }
        }
        visit(pin, 0, null)
        val best = mp4s.maxWithOrNull(rank)?.link
        val derived = hls.filter { HLS_SEGMENT in it }.map { it.substringBefore('?').replace(HLS_SEGMENT, HLS_FALLBACK).removeSuffix(M3U8) + MP4 }
        return (listOfNotNull(best) + derived).distinct()
    }

    fun carouselSlot(pin: Any, current: (Any) -> Int?): Any? {
        val carousel = keysOf(pin.javaClass)[CAROUSEL_KEY]?.get(pin) ?: return null
        val fields = keysOf(carousel.javaClass)
        val slots = (fields[SLOTS_KEY]?.get(carousel) as? List<*>).orEmpty()
        if (slots.size < 2) return null
        val index = current(pin) ?: (fields[INDEX_KEY]?.get(carousel) as? Number)?.toInt() ?: 0
        return slots.getOrNull(index.coerceIn(0, slots.lastIndex))
    }

    fun candidates(media: Any): List<String> = videoUrls(media).ifEmpty { imageUrls(media) }
}

private object PinterestSaver {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "NexAlloy-PN-Save").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private fun toast(ctx: Context, text: String) = main.post { runCatching { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() } }

    private fun mimeOf(link: String): Pair<String, String> {
        val ext = link.substringBefore('?').substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
        return ext to (MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/jpeg")
    }

    fun save(ctx: Context, candidates: List<String>) {
        val app = ctx.applicationContext ?: ctx
        toast(app, WaUi.t(app, R.string.pn_downloading))
        executor.execute {
            val resolver = app.contentResolver
            var error: Throwable? = null
            for (link in candidates) {
                val connection = runCatching { URL(link).openConnection() as HttpURLConnection }.getOrNull()
                    ?: continue
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) continue
                    val (ext, mime) = mimeOf(link)
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "Pinterest_${synchronized(stamp) { stamp.format(Date()) }}.$ext")
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MediaStore insert failed")
                    try {
                        connection.inputStream.use { input -> resolver.openOutputStream(uri)!!.use { input.copyTo(it, 64 * 1024) } }
                        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    } catch (e: Throwable) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                    toast(app, WaUi.t(app, R.string.pn_saved))
                    return@execute
                } catch (e: Throwable) {
                    error = e
                } finally {
                    connection.disconnect()
                }
            }
            toast(app, WaUi.t(app, R.string.pn_failed, error?.message ?: "HTTP"))
        }
    }
}

val EnableVideoDownload = patch(
    name = "Download videos and original images",
    description = "Shows \"Download image\" on video pins too. Videos and full-size original images are saved to Download/Pinterest.",
) {
    dependsOn(EnableImageDownload)
    val gridType = ::gridParamsClassFingerprint.clazz
    val probed = ConcurrentHashMap<String, Field>()

    fun labelValue(target: Any, label: String): String? = runCatching { target.toString() }.getOrNull()
        ?.let { text -> text.indexOf(", $label").takeIf { it >= 0 }?.let { text.substring(it + label.length + 2) } }
        ?.takeWhile { it != ',' && it != ')' }?.takeIf { it.isNotEmpty() }

    fun flag(target: Any, label: String): Field? {
        val key = target.javaClass.name + label
        probed[key]?.let { return it }
        val before = labelValue(target, label) ?: return null
        return target.javaClass.declaredFields.firstOrNull { f ->
            if (f.type != Boolean::class.javaPrimitiveType || Modifier.isStatic(f.modifiers)) return@firstOrNull false
            f.isAccessible = true
            val old = f.getBoolean(target)
            f.setBoolean(target, !old)
            val after = labelValue(target, label)
            f.setBoolean(target, old)
            after != null && before != after
        }?.also { probed[key] = it }
    }

    val pinType = ::pinClassFingerprint.clazz
    val gridFields = ConcurrentHashMap<Class<*>, Field>()
    val pinFields = ConcurrentHashMap<Class<*>, Field>()

    fun fieldOfType(owner: Class<*>, type: Class<*>): Field? {
        var c: Class<*>? = owner
        while (c != null && c != Any::class.java) {
            c.declaredFields.firstOrNull { it.type == type && !Modifier.isStatic(it.modifiers) }?.let { return it.apply { isAccessible = true } }
            c = c.superclass
        }
        return null
    }

    ::menuBuildersFingerprint.dexMethodList.forEach { builder ->
        builder.hookMethod {
            before { param ->
                val config = param.args[0] ?: return@before
                val grid = (gridFields[param.thisObject.javaClass] ?: fieldOfType(param.thisObject.javaClass, gridType)?.also { gridFields[param.thisObject.javaClass] = it })?.get(param.thisObject)
                val restore = FORCED.flatMap { (label, value) ->
                    listOfNotNull(config, grid).mapNotNull { target ->
                        flag(target, label)?.let { f -> Triple(target, f, f.getBoolean(target)).also { f.setBoolean(target, value) } }
                    }
                }
                param.setObjectExtra(RESTORE, restore)
            }
            after { param ->
                @Suppress("UNCHECKED_CAST")
                (param.getObjectExtra(RESTORE) as? List<Triple<Any, Field, Boolean>>)?.forEach { (target, flag, old) -> flag.setBoolean(target, old) }
            }
        }
    }

    val indexOf = ::carouselIndexFingerprint.method.apply { isAccessible = true }
    var store: java.lang.ref.WeakReference<Any>? = null
    indexOf.declaringClass.declaredConstructors.forEach { c ->
        c.hookMethod { after { param -> store = java.lang.ref.WeakReference(param.thisObject) } }
    }
    indexOf.declaringClass.declaredMethods.filter { it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, String::class.java)) }
        .forEach { m -> m.hookMethod { before { param -> if (store?.get() == null) store = java.lang.ref.WeakReference(param.thisObject) } } }
    fun currentIndex(pin: Any): Int? = store?.get()?.let { runCatching { indexOf.invoke(it, pin) as? Int }.getOrNull() }

    ::downloadClickFingerprint.dexMethodList.forEach { click ->
        click.hookMethod {
            before { param ->
                val ctx = param.args[0] as? Context ?: return@before
                val field = pinFields[param.thisObject.javaClass] ?: fieldOfType(param.thisObject.javaClass, pinType)?.also { pinFields[param.thisObject.javaClass] = it }
                    ?: return@before
                val pin = field.get(param.thisObject) ?: return@before
                val slot = runCatching { PinMedia.carouselSlot(pin, ::currentIndex) }.getOrNull()
                val candidates = slot?.let { runCatching { PinMedia.candidates(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }
                    ?: PinMedia.candidates(pin)
                if (candidates.isEmpty()) return@before
                PinterestSaver.save(ctx, candidates)
                param.result = null
            }
        }
    }
}
