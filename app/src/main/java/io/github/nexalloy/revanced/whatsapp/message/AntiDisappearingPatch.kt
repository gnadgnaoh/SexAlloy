package io.github.nexalloy.revanced.whatsapp.message

import android.content.ContentValues
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private const val KEEP_UNTIL = 4_102_444_800_000L

val AntiDisappearing = patch(
    name = "Keep disappearing messages",
    description = "Messages in chats with disappearing messages on stay on your phone. Only messages that arrive after you turn this on.",
) {
    ::ephemeralValuesFingerprint.method.hookMethod {
        after { param ->
            val values = param.result as? ContentValues ?: return@after
            if (values.containsKey("expire_timestamp")) {
                values.put("expire_timestamp", KEEP_UNTIL)
            }
        }
    }
}
