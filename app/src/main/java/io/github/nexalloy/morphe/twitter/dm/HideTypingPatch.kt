package io.github.nexalloy.morphe.twitter.dm

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook

val HideTyping = patch(
    name = "Hide typing indicator",
    description = "Others don't see \"typing…\" or \"recording…\" while you write or record a voice message. You still see theirs.",
) {
    val typing = classLoader.loadClass(TYPING_EVENT_DETAIL)
    ScopedFrameworkHook(::messageEventSenderFingerprint.method, before = { param ->
        if (param.args.any { typing.isInstance(it) }) {
            param.result = true
        }
    }).installOn(::typingEmitFingerprint.method)
}
