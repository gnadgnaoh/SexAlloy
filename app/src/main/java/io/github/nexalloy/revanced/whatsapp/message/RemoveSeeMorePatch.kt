package io.github.nexalloy.revanced.whatsapp.message

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val RemoveSeeMore = patch(
    name = "Remove \"Read more\"",
    description = "Long messages show in full instead of being cut with \"Read more\".",
) {
    ::seeMoreLimitFingerprint.member.hookMethod {
        before { param ->
            param.args[1] = Int.MAX_VALUE
        }
    }
}
