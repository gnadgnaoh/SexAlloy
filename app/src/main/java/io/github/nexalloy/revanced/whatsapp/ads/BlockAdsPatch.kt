package io.github.nexalloy.revanced.whatsapp.ads

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val BlockAds = patch(
    name = "Block ads",
    description = "Turns off WhatsApp's ad system, so no ads appear between status updates.",
) {
    ::wamoEnabledFingerprint.method.hookMethod {
        before { param ->
            param.result = false
        }
    }
}
