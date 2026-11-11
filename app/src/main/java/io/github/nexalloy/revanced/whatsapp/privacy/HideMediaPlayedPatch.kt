package io.github.nexalloy.revanced.whatsapp.privacy

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val HideMediaPlayed = patch(
    name = "Hide played receipts",
    description = "Listening to voice messages doesn't turn the microphone blue, and opening view once media doesn't tell the sender.",
) {
    val run = ::playedReceiptJobRunFingerprint.method
    val self = run.declaringClass.declaredFields.firstOrNull { it.name == "playedSelfFromPeer" }?.apply { isAccessible = true }
    run.hookMethod {
        before { param ->
            if (self?.getBoolean(param.thisObject) == true) return@before
            param.result = null
        }
    }
}
