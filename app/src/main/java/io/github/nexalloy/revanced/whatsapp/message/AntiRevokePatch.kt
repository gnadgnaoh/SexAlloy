package io.github.nexalloy.revanced.whatsapp.message

import android.widget.TextView
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.whatsapp.core.ConversationRows
import io.github.nexalloy.revanced.whatsapp.core.WaMessage
import io.github.nexalloy.revanced.whatsapp.core.WaStore
import io.github.nexalloy.revanced.whatsapp.core.WaUi
import io.github.nexalloy.revanced.whatsapp.core.WhatsAppConversationRows
import java.lang.reflect.Modifier

val AntiRevoke = patch(
    name = "Keep deleted messages",
    description = "When someone deletes a message for everyone, it stays on your phone with a small \"deleted\" label.",
) {
    dependsOn(WhatsAppConversationRows)
    WaStore.init(appContext)
    val revoke = ::revokeMessageFingerprint.method
    val noneType = ::noneAckClassFingerprint.clazz
    val none = noneType.declaredFields.first { Modifier.isStatic(it.modifiers) && it.type == noneType }.apply { isAccessible = true }.get(null)
    val ctor = revoke.returnType.declaredConstructors.first().apply { isAccessible = true }
    val messageIndex = revoke.parameterTypes.indexOfFirst { WaMessage.type.isAssignableFrom(it) }
    val targetField = ::revokeTargetFieldFingerprint.field.apply { isAccessible = true }
    fun target(message: Any): String? =
        if (targetField.declaringClass.isInstance(message)) runCatching { targetField.get(message) as? String }.getOrNull() else null

    revoke.hookMethod {
        before { param ->
            val message = param.args.getOrNull(messageIndex) ?: return@before
            if (WaMessage.fromMe(message)) return@before
            val id = target(message) ?: return@before
            WaStore.markRevoked(id)
            param.result = ctor.newInstance(*ctor.parameterTypes.map { type ->
                when (type) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> none
                }
            }.toTypedArray())
        }
    }

    ConversationRows.binders.add { row, message ->
        val id = WaMessage.id(message) ?: return@add
        if (!WaStore.isRevoked(id)) return@add
        val date = ConversationRows.find(row, "date") as? TextView ?: return@add
        val label = WaUi.t(row.context, R.string.wa_revoked_label)
        if (label.isNotEmpty() && !date.text.endsWith(label)) date.append(" · $label")
    }
}
