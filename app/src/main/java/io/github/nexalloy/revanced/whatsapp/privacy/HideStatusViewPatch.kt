package io.github.nexalloy.revanced.whatsapp.privacy

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.whatsapp.core.ReadReceipts
import io.github.nexalloy.revanced.whatsapp.core.WhatsAppReadReceipts

val HideStatusView = patch(
    name = "View status anonymously",
    description = "Watch other people's status without your name appearing in their viewer list.",
) {
    dependsOn(WhatsAppReadReceipts)
    ReadReceipts.hideStatus = true
}
