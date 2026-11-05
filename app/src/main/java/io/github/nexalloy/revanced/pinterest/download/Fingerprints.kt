package io.github.nexalloy.revanced.pinterest.download

import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldUsingType
import java.lang.reflect.Modifier

private const val IMAGE_DOWNLOAD_KEY = "is_eligible_for_image_download"
private const val GRID_PARAMS = "GridActionsViewParams(reason="
private const val MENU_CONFIG = "OverflowMenuCreationConfig(isPinCloseup="

private fun DexKitBridge.classOf(name: String): ClassData =
    findClass { matcher { className(name, StringMatchType.Equals) } }.single()

private fun DexKitBridge.toStringClass(marker: String): ClassData =
    findClass { matcher { usingStrings(listOf(marker), StringMatchType.StartsWith) } }.single()

val pinClassFingerprint = findClassDirect {
    val adapter = findClass { matcher { usingEqStrings(IMAGE_DOWNLOAD_KEY) } }.single { '$' in it.name }
    classOf(adapter.name.substringBefore('$'))
}

val imageDownloadEligibleFingerprint = findMethodDirect {
    val pin = pinClassFingerprint()
    val field = pin.fields.single { f -> f.annotations.any { a -> a.elements.any { it.value.toString().contains(IMAGE_DOWNLOAD_KEY) } } }
    pin.findMethod { matcher { paramCount = 0; returnType = "java.lang.Boolean" } }
        .filter { m -> m.usingFields.any { it.usingType == FieldUsingType.Read && it.field.descriptor == field.descriptor } }
        .single { (it.modifiers and Modifier.STATIC) == 0 }
}

val gridParamsClassFingerprint = findClassDirect { toStringClass(GRID_PARAMS) }

val menuBuildersFingerprint = findMethodListDirect {
    val config = toStringClass(MENU_CONFIG).name
    val items = findClass { matcher { usingEqStrings("COPY_LINK", "SET_AS_INSPIRATION", "DOWNLOAD") } }.single().name
    findMethod {
        matcher {
            paramTypes(config)
            returnType = "java.util.List"
            addUsingField { declaredClass(items, StringMatchType.Equals); name = "DOWNLOAD" }
        }
    }.toList()
}

val downloadClickFingerprint = findMethodListDirect {
    val download = findMethod {
        matcher {
            returnType = "void"
            paramTypes("android.content.Context", "android.app.Activity", "kotlin.jvm.functions.Function1", "kotlin.jvm.functions.Function0")
        }
    }.single()
    val pin = pinClassFingerprint().name
    findMethod { matcher { returnType = "void"; addInvoke(download.descriptor) } }
        .filter { m -> m.paramTypeNames.firstOrNull() == "android.content.Context" && classOf(m.className).fields.any { it.typeName == pin } }
}

val carouselIndexFingerprint = findMethodDirect {
    val pin = pinClassFingerprint().name
    findMethod {
        matcher {
            paramTypes(pin)
            returnType = "int"
            declaredClass {
                fieldCount(1)
                addField { type = "java.util.LinkedHashMap" }
                addMethod { paramTypes("int", "java.lang.String"); returnType = "void" }
            }
        }
    }.single()
}
