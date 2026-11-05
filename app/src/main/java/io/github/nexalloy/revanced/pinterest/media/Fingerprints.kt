package io.github.nexalloy.revanced.pinterest.media

import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.revanced.pinterest.download.pinClassFingerprint
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

private const val MODEL_PACKAGE = "com.pinterest.api.model"
private const val IMAGES_KEY = "images"
private const val MAP = "java.util.Map"

private fun ClassData.jsonField(key: String) =
    fields.single { f -> f.annotations.any { a -> a.elements.any { (it.value.value as? String) == key } } }

private fun jsonKey(key: String) = FieldMatcher().addAnnotation {
    addElement { name = "value"; stringValue(key, StringMatchType.Equals) }
}

private fun ClassMatcher.jsonKeys(vararg keys: String) = apply { keys.forEach { addField(jsonKey(it)) } }

private fun ClassData.mapGetter(key: String): MethodData {
    val field = jsonField(key)
    return methods.single { m ->
        !Modifier.isStatic(m.modifiers) && m.paramCount == 0 && m.returnTypeName == MAP &&
            m.instructions.size <= 3 && m.usingFields.any { it.field.descriptor == field.descriptor }
    }
}

val imageSizesFingerprint = findMethodDirect {
    val spec = findClass {
        matcher { addMethod { name = "<init>"; usingEqStrings("236x", "290x", "345x", "474x", "564x", "736x", "1200x") } }
    }.single().name
    findMethod { matcher { declaredClass(spec, StringMatchType.Equals); returnType = "java.util.HashSet"; paramCount = 0 } }.single()
}

private fun DexKitBridge.slotClass() = findClass {
    searchPackages(MODEL_PACKAGE)
    matcher(ClassMatcher().jsonKeys(IMAGES_KEY, "item_id", "ad_destination_url", "android_deep_link"))
}.single()

val pinImagesFingerprint = findMethodDirect { pinClassFingerprint().mapGetter(IMAGES_KEY) }

val pinImagesFieldFingerprint = findFieldDirect { pinClassFingerprint().jsonField(IMAGES_KEY) }

val slotImagesFingerprint = findMethodDirect { slotClass().mapGetter(IMAGES_KEY) }

val slotImagesFieldFingerprint = findFieldDirect { slotClass().jsonField(IMAGES_KEY) }

private fun DexKitBridge.toStringClass(marker: String) =
    findClass { matcher { addMethod { name = "toString"; usingStrings(marker) } } }.single()

val trackSelectorFingerprint = findMethodDirect {
    val input = toStringClass("TrackSelectorInput(mediaUid=").name
    val track = toStringClass("VideoTrack(trackKey=").name
    findMethod {
        matcher { paramTypes(input); returnType = track; usingEqStrings("V_HEVC_MP4_T1_V2") }
    }.single()
}

val trackUrlFingerprint = findFieldDirect {
    val track = toStringClass("VideoTrack(trackKey=")
    val code = track.findMethod { matcher { name = "toString" } }.single().instructions
    val label = code.indexOfFirst { it.string == ", url=" }
    code.drop(label).first { it.fieldRef?.typeName == "java.lang.String" }.fieldRef!!
}

val trackListFingerprint = findFieldDirect {
    toStringClass("TrackSelectorInput(mediaUid=").fields.single { it.typeName == "java.util.LinkedHashMap" }
}
