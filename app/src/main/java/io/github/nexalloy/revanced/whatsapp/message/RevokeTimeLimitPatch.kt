package io.github.nexalloy.revanced.whatsapp.message

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook

private const val EXTRA_MS = 12L * 60 * 60 * 1000

val RevokeTimeLimit = patch(
    name = "Delete for everyone up to 3 days",
    description = "\"Delete for everyone\" stays available for 3 days after sending. WhatsApp's server still decides.",
) {
    ScopedFrameworkHook(::serverTimeFingerprint.method, after = { param ->
        (param.result as? Long)?.let { param.result = it - EXTRA_MS }
    }).installOn(::deleteDialogFingerprint.method)
}
