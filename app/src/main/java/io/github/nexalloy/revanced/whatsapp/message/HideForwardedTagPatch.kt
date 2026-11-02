package io.github.nexalloy.revanced.whatsapp.message

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook

private const val FORWARDED_FLAG = 1L

val HideForwardedTag = patch(
    name = "Hide forwarded label",
    description = "Messages you forward are sent without the \"Forwarded\" label.",
) {
    val action = ::forwardActionFingerprint.method
    val hook = ScopedFrameworkHook(::messageFlagSetterFingerprint.method, before = { param ->
        if (param.args[0] == FORWARDED_FLAG) {
            param.result = null
        }
    })
    action.declaringClass.declaredMethods.filter { !it.isSynthetic }.forEach(hook::installOn)
}
