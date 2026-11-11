package io.github.nexalloy.revanced.zalo.selfdelete

import io.github.nexalloy.patch

val KeepSelfDeletingMessages = patch(
    name = "Keep self-deleting messages",
    description = "Self-deleting messages stay on this device. Zalo never treats them as expired here, so they are not removed from your chats. " +
        "They are still deleted on the server and on other devices.",
) {
    ::queueSelfDeleteMessageFingerprint.hookMethod {
        before { param -> param.result = null }
    }
    ::messageExpiredCheckFingerprint.hookMethod {
        before { param -> param.result = false }
    }
}
