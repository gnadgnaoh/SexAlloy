package io.github.nexalloy.revanced.whatsapp.media

import android.app.Activity
import android.content.Context
import android.view.Menu
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.whatsapp.core.WaMessage
import io.github.nexalloy.revanced.whatsapp.core.WaMessageStore
import io.github.nexalloy.revanced.whatsapp.core.WaUi
import io.github.nexalloy.revanced.whatsapp.core.WhatsAppMessageStore
import io.github.nexalloy.revanced.whatsapp.core.messageKeyClassFingerprint

private const val STATE_OPENED = 1
private const val STATE_UNOPENED = 0
private const val MEDIA_ITEM = 0x4E41_5721
private const val VIEWER_ITEM = 0x4E41_5722

val ViewOnceUnlimited = patch(
    name = "View once unlimited",
    description = "View once photos, videos and voice messages from others can be opened again, and their viewer menu gets \"Download\".",
) {
    dependsOn(WhatsAppMedia, WhatsAppMessageStore)
    val setters = ::viewOnceStateSettersFingerprint.dexMethodList.map { it.toMethod() }
    setters.forEach { setter ->
        setter.hookMethod {
            before { param ->
                if (param.args[0] == STATE_OPENED && !WaMessage.fromMe(param.thisObject)) {
                    param.args[0] = STATE_UNOPENED
                }
            }
        }
    }
    val viewOnceType = ::viewOnceStateClassFingerprint.clazz
    val mediaMenu = ::mediaViewMenuFingerprint.method
    val menuIndex = mediaMenu.parameterTypes.indexOfFirst { it == Menu::class.java }
    val menuMessageIndex = mediaMenu.parameterTypes.indexOfFirst { WaMessage.type.isAssignableFrom(it) }
    val contextIndex = mediaMenu.parameterTypes.indexOfFirst { it == Context::class.java }
    mediaMenu.hookMethod {
        after { param ->
            val message = param.args[menuMessageIndex] ?: return@after
            if (!viewOnceType.isInstance(message)) return@after
            val menu = param.args[menuIndex] as? Menu ?: return@after
            val ctx = param.args[contextIndex] as? Context ?: return@after
            if (menu.findItem(MEDIA_ITEM) != null) return@after
            menu.add(Menu.NONE, MEDIA_ITEM, Menu.CATEGORY_SECONDARY, WaUi.t(ctx, R.string.wa_download)).setOnMenuItemClickListener {
                WhatsAppSaver.saveFile(ctx, WaMedia.file(message), "ViewOnce")
                true
            }
        }
    }

    val viewer = classLoader.loadClass(VIEW_ONCE_ACTIVITY)
    val keyType = ::messageKeyClassFingerprint.clazz
    val keyField = viewer.declaredFields.single { it.type == keyType }.apply { isAccessible = true }
    fun addDownload(activity: Activity, menu: Menu) {
        if (menu.findItem(VIEWER_ITEM) != null) return
        menu.add(Menu.NONE, VIEWER_ITEM, Menu.CATEGORY_SECONDARY, WaUi.t(activity, R.string.wa_download)).setOnMenuItemClickListener {
            val key = keyField.get(activity)
            val message = WaMessageStore.message(key)
            WhatsAppSaver.saveFile(activity, WaMedia.file(message), "ViewOnce")
            true
        }
    }
    for (name in listOf("onCreateOptionsMenu", "onPrepareOptionsMenu")) {
        viewer.getDeclaredMethod(name, Menu::class.java).hookMethod {
            after { param ->
                val menu = param.args[0] as? Menu ?: return@after
                addDownload(param.thisObject as Activity, menu)
                param.result = true
            }
        }
    }
}
