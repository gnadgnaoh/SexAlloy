package io.github.nexalloy.revanced.pinterest.media

import de.robv.android.xposed.XC_MethodHook
import io.github.nexalloy.patch
import java.lang.reflect.Field

private const val ORIGINALS = "orig"

val OriginalImages = patch(
    name = "Original images",
    description = "Shows every pin image at its original resolution across the app. Uses noticeably more data.",
) {
    ::imageSizesFingerprint.hookMethod {
        after { param ->
            @Suppress("UNCHECKED_CAST")
            (param.result as? MutableSet<Any?>)?.add(ORIGINALS)
        }
    }

    fun upgrade(field: Field) = { param: XC_MethodHook.MethodHookParam ->
        val images = param.result as? Map<*, *>
        val original = images?.get(ORIGINALS)
        if (images != null && original != null && !images.values.all { it === original }) {
            val upgraded = LinkedHashMap<Any?, Any?>(images.size).apply { images.keys.forEach { put(it, original) } }
            field.set(param.thisObject, upgraded)
            param.result = upgraded
        }
    }

    val pinUpgrade = upgrade(::pinImagesFieldFingerprint.field.apply { isAccessible = true })
    val slotUpgrade = upgrade(::slotImagesFieldFingerprint.field.apply { isAccessible = true })
    ::pinImagesFingerprint.hookMethod { after { pinUpgrade(it) } }
    ::slotImagesFingerprint.hookMethod { after { slotUpgrade(it) } }
}
