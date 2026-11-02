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
private val MP4_WIDTH = Regex("_(\\d+)w\\.mp4$")
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

    fun videoUrl(pin: Any): String? {
        var best: String? = null
        var bestWidth = -1
        val seen = IdentityHashMap<Any, Boolean>()
        fun visit(node: Any?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || seen.put(node, true) != null) return
            when (node) {
                is List<*> -> node.forEach { visit(it, depth + 1) }
                is Map<*, *> -> node.values.forEach { visit(it, depth + 1) }
                else -> {
                    val type = node.javaClass
                    if (!type.name.startsWith(MODEL_PACKAGE)) return
                    val fields = keysOf(type)
                    val link = fields[URL_KEY]?.get(node) as? String
                    if (link != null && link.substringBefore('?').endsWith(MP4)) {
                        val width = MP4_WIDTH.find(link.substringBefore('?'))?.groupValues?.get(1)?.toIntOrNull()
                            ?: (fields[WIDTH_KEY]?.get(node) as? Number)?.toInt() ?: 0
                        if (width > bestWidth) { best = link; bestWidth = width }
                    }
                    nestedOf(type).forEach { visit(it.get(node), depth + 1) }
                }
            }
        }
        visit(pin, 0)
        return best
    }
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
                val connection = runCatching { URL(link).openConnection() as HttpURLConnection }.getOrNull() ?: continue
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

    ::downloadClickFingerprint.dexMethodList.forEach { click ->
        click.hookMethod {
            before { param ->
                val ctx = param.args[0] as? Context ?: return@before
                val field = pinFields[param.thisObject.javaClass] ?: fieldOfType(param.thisObject.javaClass, pinType)?.also { pinFields[param.thisObject.javaClass] = it } ?: return@before
                val pin = field.get(param.thisObject) ?: return@before
                val candidates = PinMedia.videoUrl(pin)?.let(::listOf) ?: PinMedia.imageUrls(pin)
                if (candidates.isEmpty()) return@before
                PinterestSaver.save(ctx, candidates)
                param.result = null
            }
        }
    }
}
