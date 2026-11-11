package io.github.nexalloy.revanced.threads.ads

import io.github.nexalloy.morphe.findMethodDirect

val adFetchSponsoredContentFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            usingStrings("onFetchSponsoredContent")
        }
    }.single()
}

val permalinkAdResultFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass = "com.instagram.barcelona.sponsored.permalink.ThreadsPermalinkAdFetcher"
            returnType = "java.lang.Object"
            usingStrings("text_feed/threads_permalink_ads/")
        }
    }.single().invokes.single { m ->
        m.isConstructor && m.paramCount == 2 &&
            m.paramTypeNames.count { it == "java.util.List" } == 1 && m.paramTypeNames.count { it == "int" } == 1
    }
}
