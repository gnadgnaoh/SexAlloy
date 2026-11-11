package io.github.nexalloy.revanced.zalo.selfdelete

import io.github.nexalloy.morphe.findMethodDirect

val queueSelfDeleteMessageFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingEqStrings("Queue TTL message with invalid ttl: ")
            returnType = "void"
            paramTypes(null, "boolean")
        }
    }.single()
}

val messageExpiredCheckFingerprint = findMethodDirect {
    val message = queueSelfDeleteMessageFingerprint().paramTypeNames[0]
    findMethod {
        matcher {
            usingEqStrings("Invalid TTL while iterate TTLMessagesWorker")
            returnType = "long"
            paramCount = 0
        }
    }.single().invokes
        .filter { it.isMethod && it.declaredClassName == message && it.returnTypeName == "boolean" && it.paramTypeNames.isEmpty() }
        .distinctBy { it.descriptor }
        .single()
}
