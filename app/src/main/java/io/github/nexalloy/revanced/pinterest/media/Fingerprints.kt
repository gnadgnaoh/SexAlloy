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

private fun DexKitBridge.videoDetailsClass() = findClass {
    searchPackages(MODEL_PACKAGE)
    matcher(ClassMatcher().jsonKeys("url", "manifest", "bitrate", "transcoded_width"))
}.single()

val trackSelectorFingerprint = findMethodDirect {
    val tracks = toStringClass("VideoTracks(tracks=").name
    findMethod { matcher { returnType = tracks; usingEqStrings("V_HEVC_MP4_T1_V2") } }
        .single { MAP in it.paramTypeNames }
}

val videoUrlFieldFingerprint = findFieldDirect { videoDetailsClass().jsonField("url") }

private fun DexKitBridge.glideFetcher() = findMethod {
    matcher { usingStrings(listOf("OkHttp failed to obtain result"), StringMatchType.Contains) }
}.single().declaredClass!!

private fun DexKitBridge.glideUrlClass() = glideFetcher().methods.single { it.isConstructor }.paramTypeNames
    .mapNotNull { getClassData(it) }
    .single { c -> c.methods.any { it.isConstructor && it.paramTypeNames == listOf("java.lang.String") } }

val glideFetcherLoadFingerprint = findMethodDirect {
    glideFetcher().methods.single { m ->
        m.isMethod && m.returnTypeName == "void" && m.paramCount == 2 && m.paramTypeNames[1].startsWith("com.bumptech.glide.load.data.")
    }
}

val glideFetcherResponseFingerprint = findMethodDirect { glideFetcher().methods.single { it.name == "onResponse" && it.paramCount == 2 } }

val glideFetcherFailureFingerprint = findMethodDirect { glideFetcher().methods.single { it.name == "onFailure" && it.paramCount == 2 } }

val glideFetcherUrlFieldFingerprint = findFieldDirect {
    val url = glideUrlClass().name
    glideFetcher().fields.single { it.typeName == url }
}

val glideUrlWithHeadersFingerprint = findMethodDirect {
    glideUrlClass().methods.single { it.isConstructor && it.paramCount == 2 && it.paramTypeNames[0] == "java.lang.String" }
}

val glideUrlHeadersFieldFingerprint = findFieldDirect {
    val headers = glideUrlWithHeadersFingerprint().paramTypeNames[1]
    glideUrlClass().fields.single { it.typeName == headers }
}

val imageResponseCodeFingerprint = findFieldDirect {
    val response = getClassData(glideFetcherResponseFingerprint().paramTypeNames[1])!!
    response.findMethod { matcher { name = "toString" } }.single().instructions.let { code ->
        code.drop(code.indexOfFirst { it.string == ", code=" }).first { it.fieldRef != null }.fieldRef!!
    }
}
