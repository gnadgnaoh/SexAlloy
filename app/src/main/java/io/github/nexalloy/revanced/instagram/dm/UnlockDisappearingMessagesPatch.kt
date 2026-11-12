package io.github.nexalloy.revanced.instagram.dm

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook
import java.lang.reflect.Field

private const val SAVE = "SaveMedia"
private const val FORWARD = "Forward"
private const val COPY = "CopyText"
private const val TRANSLATE = "TRANSLATE_MESSAGE"
private const val STICKER = "RIFF_CUTOUT"

val UnlockDisappearingMessages = patch(
    name = "Unlock disappearing messages",
    description = "Brings back the options Instagram hides when you hold a message in vanish mode / disappearing " +
        "messages: Forward on every message, Copy and Translate on text, and Save and Create sticker on photos and videos.",
) {
    val mediaFields = listOf(::messageMediaField, ::messageVisualMediaField, ::messageRavenMediaField)
        .map { it.field.apply { isAccessible = true } }
    val vanishFields = listOf(::messageIsDisappearingField, ::messageEphemeralLifetimeField, ::messageExpirationTimestampField)
        .map { it.field.apply { isAccessible = true } }
    val messageClass = messageModelClass(mediaFields + vanishFields)

    val flags = HashMap<String, Field>()
    fun learn(action: Field, flag: Field) {
        val name = runCatching {
            action.isAccessible = true
            when (val value = action.get(null)) {
                is Enum<*> -> value.name
                else -> value?.toString()
            }
        }.getOrNull() ?: return
        flags.putIfAbsent(name, flag.apply { isAccessible = true })
    }
    ::messageActionFlagMapFingerprint.fieldList.chunked(2).forEach { if (it.size == 2) learn(it[0], it[1]) }
    ::messageMenuActionGuardsFingerprint.fieldList.chunked(2).forEach { if (it.size == 2) learn(it[1], it[0]) }
    val missing = listOf(SAVE, FORWARD, COPY, TRANSLATE, STICKER).filter { it !in flags }
    check(missing.isEmpty()) { "message menu flags not found: $missing" }
    val always = listOf(flags.getValue(FORWARD))
    val forText = listOf(flags.getValue(COPY), flags.getValue(TRANSLATE))
    val forMedia = listOf(flags.getValue(SAVE), flags.getValue(STICKER))
    val configClass = forMedia.first().declaringClass

    val menu = ::messageActionMenuFingerprint.method
    val lookup = ::messageMenuLookupFingerprint.method

    val config = ThreadLocal<Any?>()
    menu.hookMethod {
        before { param -> config.set(param.args.firstOrNull { configClass.isInstance(it) }) }
        after { config.remove() }
    }

    ScopedFrameworkHook(lookup, after = { param ->
        val target = config.get() ?: return@ScopedFrameworkHook
        val message = param.result?.takeIf { messageClass.isInstance(it) } ?: return@ScopedFrameworkHook
        if (!isDisappearing(message, vanishFields)) return@ScopedFrameworkHook
        val unlock = always + if (hasMedia(message, mediaFields)) forMedia else forText
        unlock.forEach { runCatching { it.setBoolean(target, true) } }
    }).installOn(menu)
}

private fun isDisappearing(message: Any, fields: List<Field>): Boolean = fields.any { f ->
    when (val v = runCatching { f.get(message) }.getOrNull()) {
        is Boolean -> v
        is Long -> v > 0L
        else -> false
    }
}

private fun hasMedia(message: Any, fields: List<Field>): Boolean =
    fields.any { f -> runCatching { f.get(message) }.getOrNull() != null }

private fun messageModelClass(fields: List<Field>): Class<*> {
    val owners = fields.map { it.declaringClass }.distinct()
    return owners.firstOrNull { leaf -> owners.all { it.isAssignableFrom(leaf) } }
        ?: error("message fields belong to unrelated classes: " + fields.joinToString { "${it.declaringClass.name}.${it.name}" })
}
