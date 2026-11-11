package io.github.nexalloy.revanced.whatsapp.privacy

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val HideComposing = patch(
    name = "Hide typing and recording",
    description = "Others don't see \"typing…\" or \"recording audio…\" while you write or record.",
) {
    ::composingSenderFingerprint.method.hookMethod {
        before { param ->
            param.result = null
        }
    }
}
