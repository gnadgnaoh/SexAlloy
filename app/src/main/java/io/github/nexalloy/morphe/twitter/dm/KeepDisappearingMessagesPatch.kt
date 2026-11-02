package io.github.nexalloy.morphe.twitter.dm

import io.github.nexalloy.patch

private const val NOT_STARTED = 0L

val KeepDisappearingMessages = patch(
    name = "Keep disappearing messages",
    description = "Disappearing messages stay on your phone. Their timer never starts here, so they are never deleted on this device.",
) {
    ::startTtlQueryFingerprint.hookMethod {
        before { param ->
            param.result = NOT_STARTED
        }
    }
}
