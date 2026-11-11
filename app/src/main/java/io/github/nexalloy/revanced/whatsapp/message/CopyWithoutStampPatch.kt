package io.github.nexalloy.revanced.whatsapp.message

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val CopyWithoutStamp = patch(
    name = "Copy messages without name and date",
    description = "Copying several messages copies only their text, without \"[time] Name:\" in front.",
) {
    ::copyMessagesFingerprint.method.hookMethod {
        before { param ->
            val last = param.args.lastIndex
            val messages = param.args[last] as? Collection<*> ?: return@before
            if (messages.size <= 1) return@before
            param.args[last] = object : ArrayList<Any?>(messages) {
                override val size: Int get() = 1
            }
        }
    }
}
