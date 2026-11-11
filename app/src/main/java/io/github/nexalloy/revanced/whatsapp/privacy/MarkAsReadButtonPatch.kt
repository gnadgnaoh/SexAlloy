package io.github.nexalloy.revanced.whatsapp.privacy

import android.app.Activity
import android.view.Menu
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.whatsapp.core.CONVERSATION_ACTIVITY
import io.github.nexalloy.revanced.whatsapp.core.ReadReceipts
import io.github.nexalloy.revanced.whatsapp.core.WaUi
import io.github.nexalloy.revanced.whatsapp.core.WhatsAppReadReceipts

private const val ITEM_ID = 0x4E41_5701

val MarkAsReadButton = patch(
    name = "Mark as read button",
    description = "Adds \"Send read receipts\" to the chat menu, so you choose when the other person sees blue ticks. Needs \"Hide read receipts\".",
) {
    dependsOn(WhatsAppReadReceipts)
    val conversation = classLoader.loadClass(CONVERSATION_ACTIVITY)
    conversation.getDeclaredMethod("onCreateOptionsMenu", Menu::class.java).hookMethod {
        after { param ->
            val activity = param.thisObject as Activity
            val menu = param.args[0] as? Menu ?: return@after
            if (menu.findItem(ITEM_ID) != null) return@after
            menu.add(Menu.NONE, ITEM_ID, Menu.CATEGORY_SECONDARY, WaUi.t(activity, R.string.wa_send_read)).setOnMenuItemClickListener {
                val chat = activity.intent?.getStringExtra("jid")
                val sent = ReadReceipts.flush(chat)
                WaUi.toast(activity, if (sent > 0) WaUi.t(activity, R.string.wa_read_sent) else WaUi.t(activity, R.string.wa_read_nothing))
                true
            }
        }
    }
}
