package io.github.nexalloy.morphe.twitter.dm

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook

val HideReadReceipts = patch(
    name = "Hide read receipts",
    description = "Others don't see when you read their messages. You still see when they read yours, and chats you open are still marked as read on your phone.",
) {
    val markRead = classLoader.loadClass(MARK_READ_EVENT)
    val job = ::lastSeenSyncJobFingerprint.method
    ScopedFrameworkHook(::messageEventSenderFingerprint.method, before = { param ->
        if (param.args.any { markRead.isInstance(it) }) {
            param.result = true
        }
    }).installOn(job)
}
