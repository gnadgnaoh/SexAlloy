package io.github.nexalloy.revanced.whatsapp.media

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Menu
import android.view.View
import android.widget.TextView
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.whatsapp.core.ConversationRows
import io.github.nexalloy.revanced.whatsapp.core.WaMessage
import io.github.nexalloy.revanced.whatsapp.core.WaUi
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

private const val STATUS_ACTIVITY = "com.whatsapp.status.playback.StatusPlaybackActivity"
private const val STATUS_JID = "status@broadcast"
private const val DOWNLOAD_ITEM = 0x4E41_5711
private const val SHARE_ITEM = 0x4E41_5712
private const val REPOST_ITEM = 0x4E41_5713
private const val COPY_ITEM = 0x4E41_5714
private val TEXT_VIEWS = listOf("caption", "message_text")

private class StatusSelection(val holder: Any?, val page: View?) {
    val file get() = WaMedia.file(holder)

    val text: String?
        get() = page?.let { root ->
            TEXT_VIEWS.firstNotNullOfOrNull { name ->
                (ConversationRows.find(root, name) as? TextView)?.takeIf { it.isShown }?.text?.toString()?.takeIf { it.isNotBlank() }
            }
        }
}

private class StatusAction(val id: Int, val label: Int, val run: (Context, StatusSelection) -> Unit)

private object StatusMenu {
    val actions = CopyOnWriteArrayList<StatusAction>()

    @Volatile
    var activity: WeakReference<Activity>? = null
}

private val WhatsAppStatusMenu = patch(name = "<WhatsAppStatusMenu>") {
    dependsOn(WhatsAppMedia)
    val menuMethod = ::statusMenuFingerprint.method
    val fragmentType = menuMethod.declaringClass
    val list = fragmentType.declaredFields.single { List::class.java.isAssignableFrom(it.type) }.apply { isAccessible = true }
    val current = ::statusCurrentItemFingerprint.method.apply { isAccessible = true }
    val pageOf = ::statusPageFingerprint.method.apply { isAccessible = true }
    val pageRoot = pageOf.returnType.declaredFields.single { it.type == View::class.java }.apply { isAccessible = true }
    val app = appContext

    fun select(fragment: Any): StatusSelection {
        val item = runCatching { current.invoke(null, fragment, list.get(fragment)) }.getOrNull()
        val page = item?.let { runCatching { pageOf.invoke(fragment, it) }.getOrNull() }
        val root = page?.let { runCatching { pageRoot.get(it) as? View }.getOrNull() }
        val holder = WaMedia.holderIn(item)
        return StatusSelection(holder, root)
    }

    classLoader.loadClass(STATUS_ACTIVITY).getDeclaredMethod("onResume").hookMethod {
        after { param -> StatusMenu.activity = WeakReference(param.thisObject as Activity) }
    }
    menuMethod.hookMethod {
        after { param ->
            val fragment = param.thisObject
            val menu = param.args[0] as? Menu ?: return@after
            val ctx: Context = StatusMenu.activity?.get() ?: app
            for (action in StatusMenu.actions) {
                if (menu.findItem(action.id) != null) continue
                menu.add(Menu.NONE, action.id, Menu.CATEGORY_SECONDARY, WaUi.t(ctx, action.label)).setOnMenuItemClickListener {
                    action.run(StatusMenu.activity?.get() ?: app, select(fragment))
                    true
                }
            }
        }
    }
}

val DownloadStatus = patch(
    name = "Download status",
    description = "Adds \"Download\" to the status menu. Photos and videos are saved to Download/WhatsApp.",
) {
    StatusMenu.actions += StatusAction(DOWNLOAD_ITEM, R.string.wa_download) { ctx, status ->
        WhatsAppSaver.saveFile(ctx, status.file, "Status")
    }
    dependsOn(WhatsAppStatusMenu)
}

val ShareStatus = patch(
    name = "Share status",
    description = "Adds \"Share\" to the status menu. Saves the photo or video to Download/WhatsApp, then opens Android's share sheet to send it to another app.",
) {
    StatusMenu.actions += StatusAction(SHARE_ITEM, R.string.wa_share) { ctx, status ->
        WhatsAppSaver.saveFile(ctx, status.file, "Status", share = true)
    }
    dependsOn(WhatsAppStatusMenu)
}

val RepostStatus = patch(
    name = "Repost status",
    description = "Adds \"Repost to my status\" to the status menu. Opens the status editor with the same photo or video and its caption.",
) {
    StatusMenu.actions += StatusAction(REPOST_ITEM, R.string.wa_repost) { ctx, status ->
        val file = status.file
        if (file == null || !file.isFile) return@StatusAction WaUi.toast(ctx, WaUi.t(ctx, R.string.wa_media_unavailable))
        val intent = Intent().setClassName(ctx.packageName, MEDIA_COMPOSER_ACTIVITY)
            .putStringArrayListExtra("jids", arrayListOf(STATUS_JID))
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf<Uri>(Uri.fromFile(file)))
        (status.text ?: status.holder?.takeIf { WaMessage.type.isInstance(it) }?.let(WaMessage::text))
            ?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }
    dependsOn(WhatsAppStatusMenu)
}

val CopyStatusText = patch(
    name = "Copy status text",
    description = "Adds \"Copy text\" to the status menu to copy the text or caption of a status.",
) {
    StatusMenu.actions += StatusAction(COPY_ITEM, R.string.wa_copy_text) { ctx, status ->
        val text = status.text ?: return@StatusAction WaUi.toast(ctx, WaUi.t(ctx, R.string.wa_no_text))
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("status", text))
        WaUi.toast(ctx, WaUi.t(ctx, R.string.wa_copied))
    }
    dependsOn(WhatsAppStatusMenu)
}
