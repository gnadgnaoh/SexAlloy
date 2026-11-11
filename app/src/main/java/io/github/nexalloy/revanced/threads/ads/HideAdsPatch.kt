package io.github.nexalloy.revanced.threads.ads

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.patch

val HideAds = patch(
    name = "Hide ads",
    description = "Hides sponsored content in the Threads feed, " +
        "and the ads shown among the replies when you open a post.",
) {
    ::adFetchSponsoredContentFingerprint.hookMethod(XC_MethodReplacement.DO_NOTHING)
    val params = ::permalinkAdResultFingerprint.constructor.parameterTypes
    val adsArg = params.indexOfFirst { it == List::class.java }
    val countArg = params.indexOfFirst { it == Int::class.javaPrimitiveType }
    ::permalinkAdResultFingerprint.hookMethod {
        before { param ->
            param.args[adsArg] = emptyList<Any>()
            param.args[countArg] = 0
        }
    }
}
