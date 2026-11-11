package io.github.nexalloy.revanced.whatsapp.core

import io.github.nexalloy.patch
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object WaMessage {
    lateinit var type: Class<*>
    private lateinit var key: Field
    private lateinit var keyId: Field
    private lateinit var keyFromMe: Field
    private lateinit var keyChat: Field
    private lateinit var textField: Field
    private lateinit var dataText: Method

    val ready get() = ::type.isInitialized

    fun bind(message: Class<*>, keyType: Class<*>, text: Field, data: Method) {
        dataText = data.apply { isAccessible = true }
        val own = message.declaredFields.filter { it.type == keyType && !Modifier.isStatic(it.modifiers) }
        key = (own.firstOrNull { Modifier.isFinal(it.modifiers) } ?: own.first()).apply { isAccessible = true }
        val fields = keyType.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        keyId = fields.first { it.type == String::class.java }.apply { isAccessible = true }
        keyFromMe = fields.first { it.type == Boolean::class.javaPrimitiveType }.apply { isAccessible = true }
        keyChat = fields.first { it.type != String::class.java && !it.type.isPrimitive }.apply { isAccessible = true }
        textField = text.apply { isAccessible = true }
        type = message
    }

    fun keyOf(message: Any): Any? = runCatching { key.get(message) }.getOrNull()
    fun id(message: Any): String? = keyOf(message)?.let { keyId.get(it) as? String }
    fun fromMe(message: Any): Boolean = keyOf(message)?.let { keyFromMe.getBoolean(it) } == true
    fun chat(message: Any): String? = keyOf(message)?.let { keyChat.get(it) }?.let(::rawJid)
    fun text(message: Any): String? = runCatching {
        (textField.get(message) as? String)?.takeIf { it.isNotEmpty() } ?: dataText.invoke(message) as? String
    }.getOrNull()
    fun idOfKey(key: Any): String? = runCatching { keyId.get(key) as? String }.getOrNull()

    fun rawJid(jid: Any?): String? = jid?.let {
        runCatching { it.javaClass.getMethod("getRawString").invoke(it) as? String }.getOrNull()
    }
}

internal val WhatsAppMessages = patch(name = "<WhatsAppMessages>") {
    if (!WaMessage.ready) WaMessage.bind(::fMessageClassFingerprint.clazz, ::messageKeyClassFingerprint.clazz, ::messageTextFieldFingerprint.field, ::messageDataTextFingerprint.method)
}
