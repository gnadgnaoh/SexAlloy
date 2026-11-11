package io.github.nexalloy.revanced.whatsapp.privacy

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.whatsapp.core.ReadReceipts
import io.github.nexalloy.revanced.whatsapp.core.WhatsAppReadReceipts

val HideReadReceipts = patch(
    name = "Hide read receipts",
    description = "Read private and group chats without sending blue ticks. The other person keeps seeing grey ticks.",
) {
    dependsOn(WhatsAppReadReceipts)
    ReadReceipts.hideChats = true
}
