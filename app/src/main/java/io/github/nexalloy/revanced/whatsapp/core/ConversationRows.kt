package io.github.nexalloy.revanced.whatsapp.core

import android.view.View
import android.view.ViewGroup
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.util.concurrent.CopyOnWriteArrayList

internal object ConversationRows {
    val binders = CopyOnWriteArrayList<(View, Any) -> Unit>()

    private val ids = HashMap<String, Int>()

    fun id(view: View, name: String): Int = synchronized(ids) {
        ids.getOrPut(name) { view.resources.getIdentifier(name, "id", view.context.packageName) }
    }

    fun find(row: View, name: String): View? = id(row, name).takeIf { it != 0 }?.let { row.findViewById(it) }
}

internal val WhatsAppConversationRows = patch(name = "<WhatsAppConversationRows>") {
    dependsOn(WhatsAppMessages)
    val adapter = ::conversationAdapterFingerprint.clazz
    val getItem = adapter.getMethod("getItem", Int::class.javaPrimitiveType)
    adapter.getDeclaredMethod("getView", Int::class.javaPrimitiveType, View::class.java, ViewGroup::class.java).hookMethod {
        after { param ->
            if (ConversationRows.binders.isEmpty()) return@after
            val row = param.result as? View ?: return@after
            val item = runCatching { getItem.invoke(param.thisObject, param.args[0]) }.getOrNull() ?: return@after
            if (!WaMessage.type.isInstance(item)) return@after
            for (bind in ConversationRows.binders) runCatching { bind(row, item) }
        }
    }
}
