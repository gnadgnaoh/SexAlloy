package io.github.nexalloy.revanced.pinterest.download

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.patch

val EnableImageDownload = patch(
    name = "Enable image download",
    description = "Shows Pinterest's own \"Download image\" on every pin, including pins whose download is turned off.",
) {
    ::imageDownloadEligibleFingerprint.hookMethod(XC_MethodReplacement.returnConstant(java.lang.Boolean.TRUE))
}
