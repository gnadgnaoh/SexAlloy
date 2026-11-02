package io.github.nexalloy.revanced.whatsapp.media

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.whatsapp.core.WhatsAppMessages
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

internal object WaMedia {
    private lateinit var getter: Method
    private lateinit var fileField: Field
    private val holders = ConcurrentHashMap<Class<*>, List<Field>>()

    val ready get() = ::fileField.isInitialized

    fun bind(getter: Method, file: Field) {
        this.getter = getter.apply { isAccessible = true }
        fileField = file.apply { isAccessible = true }
    }

    fun isMedia(value: Any?) = value != null && getter.declaringClass.isInstance(value)

    fun file(holder: Any?): File? = runCatching {
        if (!isMedia(holder)) return null
        getter.invoke(holder)?.let { fileField.get(it) as? File }
    }.getOrNull()

    private fun fieldsOf(type: Class<*>): List<Field> = holders.getOrPut(type) {
        generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
            .onEach { it.isAccessible = true }
            .toList()
    }

    fun holderIn(owner: Any?): Any? {
        if (owner == null) return null
        if (isMedia(owner)) return owner
        return fieldsOf(owner.javaClass).firstNotNullOfOrNull { field -> runCatching { field.get(owner) }.getOrNull()?.takeIf(::isMedia) }
    }
}

internal val WhatsAppMedia = patch(name = "<WhatsAppMedia>") {
    dependsOn(WhatsAppMessages)
    if (WaMedia.ready) return@patch
    val getter = ::mediaDataGetterFingerprint.method
    WaMedia.bind(getter, ::mediaFileFieldFingerprint.field)
}
