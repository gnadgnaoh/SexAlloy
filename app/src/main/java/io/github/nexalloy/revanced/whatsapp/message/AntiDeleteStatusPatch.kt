package io.github.nexalloy.revanced.whatsapp.message

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Modifier

val AntiDeleteStatus = patch(
    name = "Keep deleted status",
    description = "Status updates their owner deletes stay viewable on your phone.",
) {
    val revoke = ::statusRevokeFingerprint.method
    val kept = revoke.returnType.enumConstants?.firstOrNull { (it as Enum<*>).name == "DIDNT_STORE" } ?: error("no DIDNT_STORE")
    val keyType = revoke.parameterTypes.first { type ->
        !type.isPrimitive && type.declaredFields.count { it.type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers) } == 1
    }
    val fromMe = keyType.declaredFields
        .single { it.type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers) }.apply { isAccessible = true }
    val keyIndexes = revoke.parameterTypes.indices.filter { revoke.parameterTypes[it] == keyType }
    revoke.hookMethod {
        before { param ->
            val mine = keyIndexes.any { index -> param.args[index]?.let(fromMe::getBoolean) == true }
            if (mine) return@before
            param.result = kept
        }
    }
}
