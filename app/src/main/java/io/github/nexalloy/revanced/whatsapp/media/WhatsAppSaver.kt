package io.github.nexalloy.revanced.whatsapp.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import app.morphe.extension.shared.Logger
import io.github.nexalloy.R
import io.github.nexalloy.revanced.whatsapp.core.WaUi
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

internal object WhatsAppSaver {
    private const val FOLDER = "WhatsApp"
    private const val FALLBACK_MIME = "application/octet-stream"
    private const val SCAN_WAIT_MS = 3000L

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "NexAlloy-WA-Save").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private fun name(prefix: String, ext: String) = "${prefix}_${synchronized(stamp) { stamp.format(Date()) }}.$ext"

    private fun mimeOf(ext: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase(Locale.ROOT)) ?: FALLBACK_MIME

    fun saveFile(ctx: Context, file: File?, prefix: String, share: Boolean = false) {
        val app = ctx.applicationContext ?: ctx
        if (file == null || !file.isFile) return WaUi.toast(app, WaUi.t(app, R.string.wa_media_unavailable))
        val ext = file.extension.ifEmpty { "bin" }
        val mime = mimeOf(ext)
        run(app, share) { publish(app, name(prefix, ext), mime) { out -> FileInputStream(file).use { it.copyTo(out, 64 * 1024) } } to mime }
    }

    fun saveBitmap(ctx: Context, bitmap: Bitmap, prefix: String) {
        val app = ctx.applicationContext ?: ctx
        run(app, false) { publish(app, name(prefix, "png"), "image/png") { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) } to "image/png" }
    }

    private fun run(app: Context, share: Boolean, task: () -> Pair<Uri?, String>) {
        executor.execute {
            try {
                val (uri, mime) = task()
                WaUi.toast(app, WaUi.t(app, R.string.wa_media_saved))
                if (share && uri != null) WaUi.main.post { share(app, uri, mime) }
            } catch (e: Throwable) {
                Logger.printException({ "WhatsApp: save failed" }, e)
                WaUi.toast(app, WaUi.t(app, R.string.wa_media_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun share(app: Context, uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            app.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Logger.printException({ "WhatsApp: share failed" }, it) }
    }

    private fun publish(ctx: Context, name: String, mime: String, write: (OutputStream) -> Unit): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)!!.use(write)
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        dir.mkdirs()
        val dest = File(dir, name)
        dest.outputStream().use(write)
        var scanned: Uri? = null
        val lock = Object()
        MediaScannerConnection.scanFile(ctx, arrayOf(dest.path), arrayOf(mime)) { _, uri ->
            synchronized(lock) { scanned = uri; lock.notifyAll() }
        }
        synchronized(lock) { if (scanned == null) lock.wait(SCAN_WAIT_MS) }
        return scanned
    }
}
