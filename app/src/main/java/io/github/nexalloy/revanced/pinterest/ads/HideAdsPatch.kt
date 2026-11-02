package io.github.nexalloy.revanced.pinterest.ads

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

private const val PROMOTED = "is_promoted"
private const val VALUE = "value"

private object Promoted {
    private val none = Any()
    private val fields = ConcurrentHashMap<Class<*>, Any>()

    private fun find(type: Class<*>): Any = type.declaredFields.firstOrNull { field ->
        field.type == java.lang.Boolean::class.java && !Modifier.isStatic(field.modifiers) && field.declaredAnnotations.any { annotation ->
            runCatching { annotation.annotationClass.java.getDeclaredMethod(VALUE).invoke(annotation) == PROMOTED }.getOrDefault(false)
        }
    }?.apply { isAccessible = true } ?: none

    fun of(item: Any?): Boolean {
        item ?: return false
        val field = fields.getOrPut(item.javaClass) { find(item.javaClass) } as? Field ?: return false
        return field.get(item) == true
    }
}

val HideAds = patch(
    name = "Hide ads",
    description = "Removes promoted pins from the home feed, search, related pins, boards and profiles.",
) {
    for (fingerprint in listOf(::feedListFingerprint, ::pagedResponseListFingerprint, ::modelListFingerprint)) {
        val ctor = fingerprint.constructor
        val index = ctor.parameterTypes.indexOfFirst { it == List::class.java }
        val source = fingerprint.name
        ctor.hookMethod {
            before { param ->
                val items = param.args[index] as? List<*> ?: return@before
                if (items.none(Promoted::of)) return@before
                val kept = items.filterNot(Promoted::of)
                if (kept.isEmpty()) return@before
                param.args[index] = ArrayList(kept)
            }
        }
    }
}
