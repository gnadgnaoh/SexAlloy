package io.github.nexalloy.revanced.whatsapp.message

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val RemoveForwardLimit = patch(
    name = "Remove forward limit",
    description = "Forward to more than 5 chats at once. Mass forwarding may draw attention to your account.",
) {
    ::forwardLimitClassFingerprint.clazz.declaredConstructors.forEach { ctor ->
        val ints = ctor.parameterTypes.indices.filter { ctor.parameterTypes[it] == Int::class.javaPrimitiveType }
        val flags = ctor.parameterTypes.indices.filter { ctor.parameterTypes[it] == Boolean::class.javaPrimitiveType }
        val boxed = ctor.parameterTypes.indices.filter { ctor.parameterTypes[it] == Integer::class.java }
        if (ints.isEmpty()) return@forEach
        ctor.hookMethod {
            before { param ->
                ints.firstOrNull { (param.args[it] as Int) ushr 24 == 0 }?.let { param.args[it] = Int.MAX_VALUE }
                flags.forEach { param.args[it] = false }
                boxed.forEach { if (param.args[it] != null) param.args[it] = Int.MAX_VALUE }
            }
        }
    }
}
