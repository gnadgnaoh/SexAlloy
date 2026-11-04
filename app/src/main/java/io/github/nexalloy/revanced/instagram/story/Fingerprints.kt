package io.github.nexalloy.revanced.instagram.story

import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

private const val REEL_ITEM = "com.instagram.model.reels.ReelItem"
private const val MEDIA = "com.instagram.feed.media.Media"
private const val USER = "com.instagram.user.model.User"
private const val SHARE_TYPE = "com.instagram.pendingmedia.model.constants.ShareType"
private const val BIND_MEDIA = "ReelViewerItemBinder.bindMedia"

private fun DexKitBridge.pandoGetter(type: String, field: String, returns: String?): MethodData = findMethod {
    matcher {
        declaredClass(type)
        paramCount = 0
        if (returns != null) returnType = returns
        usingNumbers(field.hashCode())
    }
}.single { !Modifier.isStatic(it.modifiers) && '.' in it.returnTypeName }

val storyBindMethodsFingerprint = findMethodListDirect {
    findMethod { matcher { usingEqStrings(BIND_MEDIA) } }
}

val storyPageItemFieldFingerprint = findFieldDirect {
    storyBindMethodsFingerprint().flatMap { m ->
        m.usingFields.filter { it.usingType == FieldUsingType.Write && it.field.typeName == REEL_ITEM }.map { it.field }
    }.distinctBy { it.descriptor }.single()
}

val mediaReelMentionsGetterFingerprint = findMethodDirect { pandoGetter(MEDIA, "reel_mentions", "java.util.List") }

val userFullNameGetterFingerprint = findMethodDirect { pandoGetter(USER, "full_name", "java.lang.String") }

val userProfilePicGetterFingerprint = findMethodDirect { pandoGetter(USER, "profile_pic_url", null) }

val uploadHighQualityCheckFingerprint = findMethodDirect {
    val owner = findClass {
        matcher { addField { type = SHARE_TYPE }; addMethod { usingNumbers(9_000_000) } }
    }.single().name
    findMethod { matcher { declaredClass = owner; returnType = "boolean"; paramCount = 0 } }.single { m ->
        !Modifier.isStatic(m.modifiers) && m.invokes.any { pref ->
            Modifier.isStatic(pref.modifiers) && pref.returnTypeName == "boolean" && pref.paramCount == 1 &&
                pref.invokes.any { it.name == "getInt" && it.paramTypeNames == listOf("java.lang.String", "int") }
        }
    }
}
