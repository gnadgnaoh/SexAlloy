package io.github.nexalloy.revanced.whatsapp.privacy

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.whatsapp.core.ReadReceipts
import io.github.nexalloy.revanced.whatsapp.core.WaJid
import io.github.nexalloy.revanced.whatsapp.core.WhatsAppReadReceipts
import io.github.nexalloy.revanced.whatsapp.core.sendMessageJobRunFingerprint

val SendReadOnReply = patch(
    name = "Send read receipts on reply",
    description = "When you reply in a chat, the messages you already read there get their blue ticks. Needs \"Hide read receipts\".",
    use = false,
) {
    dependsOn(WhatsAppReadReceipts)
    val run = ::sendMessageJobRunFingerprint.method
    val jid = run.declaringClass.getDeclaredField("jid").apply { isAccessible = true }
    run.hookMethod {
        before { param ->
            val chat = jid.get(param.thisObject) as? String ?: return@before
            if (WaJid.isChat(chat)) ReadReceipts.flush(chat)
        }
    }
}
