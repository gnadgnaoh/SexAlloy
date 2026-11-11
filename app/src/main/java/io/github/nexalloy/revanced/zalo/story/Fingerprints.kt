package io.github.nexalloy.revanced.zalo.story

import io.github.nexalloy.morphe.findMethodDirect

private const val CMD_STORY_SEEN = 1343
private const val CMD_STORY_SEEN_SUGGESTED = 1356

val storySeenRequestFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingNumbers(CMD_STORY_SEEN)
            paramTypes("int", "java.lang.String")
            returnType = "void"
        }
    }.single()
}

val storySeenSuggestedRequestFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingNumbers(CMD_STORY_SEEN_SUGGESTED)
            paramTypes("int", "int", "java.lang.String")
            returnType = "void"
        }
    }.single()
}
