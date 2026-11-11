package io.github.nexalloy.revanced.whatsapp.media

import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findFieldListDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.revanced.whatsapp.core.fMessageClassFingerprint
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.FieldUsingType
import java.lang.reflect.Modifier

internal const val VIEW_ONCE_ACTIVITY = "com.whatsapp.viewonce.ui.messaging.ViewOnceViewerActivity"
internal const val STATUS_FRAGMENT = "com.whatsapp.status.playback.fragment.StatusPlaybackContactFragment"
internal const val PROFILE_PHOTO_ACTIVITY = "com.whatsapp.profile.ui.ViewProfilePhoto"
internal const val MEDIA_VIEW_MENU = "com.whatsapp.mediaview.menu.MediaViewMenu"
internal const val MEDIA_COMPOSER_ACTIVITY = "com.whatsapp.mediacomposer.ui.app.MediaComposerActivity"

private fun DexKitBridge.toStringClass(prefix: String): ClassData =
    findClass { matcher { usingStrings(listOf(prefix), StringMatchType.StartsWith) } }.single()

private fun DexKitBridge.classOf(name: String): ClassData =
    findClass { matcher { className(name, StringMatchType.Equals) } }.single()

private fun ClassData.toStringInts(): List<FieldData> =
    findMethod { matcher { name = "toString"; paramCount = 0 } }.single()
        .usingFields.filter { it.usingType == FieldUsingType.Read && it.field.declaredClassName == name && it.field.typeName == "int" }
        .map { it.field }.distinctBy { it.descriptor }

val imageQualityFieldsFingerprint = findFieldListDirect { toStringClass("ProcessImageQuality(").toStringInts() }

val videoQualityFieldsFingerprint = findFieldListDirect { toStringClass("ProcessVideoQuality(").toStringInts() }

val viewOnceStateClassFingerprint = findClassDirect {
    val insert = findMethod { matcher { usingEqStrings("INSERT_VIEW_ONCE_SQL") } }.single()
    insert.invokes.filter { it.isMethod && it.paramTypeNames.isEmpty() && it.returnTypeName == "int" }
        .map { it.declaredClassName }.distinct()
        .map { classOf(it) }
        .single { Modifier.isInterface(it.modifiers) && it.methods.size == 2 }
}

val viewOnceStateSettersFingerprint = findMethodListDirect {
    val state = viewOnceStateClassFingerprint()
    val setter = state.methods.single { it.returnTypeName == "void" && it.paramTypeNames == listOf("int") }
    findClass { matcher { addInterface(state.name) } }.map { impl ->
        impl.findMethod { matcher { name = setter.name; returnType = "void"; paramTypes("int") } }.single()
    }
}

val mediaViewMenuFingerprint = findMethodDirect {
    val message = fMessageClassFingerprint().name
    findClass { matcher { className(MEDIA_VIEW_MENU, StringMatchType.Equals) } }.single().methods.filter {
        Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
            "android.view.Menu" in it.paramTypeNames && message in it.paramTypeNames
    }.single()
}

val mediaDataFieldFingerprint = findFieldDirect {
    val message = fMessageClassFingerprint().name
    val media = findClass { matcher { superClass(message); usingStrings("MessageUtil/isValidMediaUrl") } }.single()
    media.fields.single { field ->
        !Modifier.isStatic(field.modifiers) && "." in field.typeName && !field.typeName.startsWith("java.") && !field.typeName.startsWith("android.") &&
            findClass { matcher { className(field.typeName, StringMatchType.Equals) } }.singleOrNull()?.fields?.count { it.typeName == "java.io.File" } == 1
    }
}

val mediaDataGetterFingerprint = findMethodDirect {
    val message = fMessageClassFingerprint().name
    val data = mediaDataFieldFingerprint().typeName
    findClass { matcher { superClass(message); usingStrings("MessageUtil/isValidMediaUrl") } }.single()
        .interfaces.flatMap { it.methods }.single { it.paramCount == 0 && it.returnTypeName == data }
}

val mediaFileFieldFingerprint = findFieldDirect {
    classOf(mediaDataFieldFingerprint().typeName).fields.single { it.typeName == "java.io.File" }
}

val statusMenuFingerprint = findMethodDirect {
    findClass { matcher { className(STATUS_FRAGMENT, StringMatchType.Equals) } }.single().findMethod {
        matcher {
            modifiers = Modifier.PUBLIC
            returnType = "void"
            paramTypes("android.view.Menu")
        }
    }.single()
}

val statusCurrentItemFingerprint = findMethodDirect {
    findMethod { matcher { modifiers = Modifier.STATIC; paramTypes(STATUS_FRAGMENT, "java.util.List") } }.single()
}

val statusPageFingerprint = findMethodDirect {
    val item = statusCurrentItemFingerprint().returnTypeName
    val page = findClass { matcher { usingStrings("StatusPlaybackPage/onViewCreated page=") } }.single().name
    findClass { matcher { className(STATUS_FRAGMENT, StringMatchType.Equals) } }.single()
        .findMethod { matcher { paramTypes(item); returnType = page } }.single()
}
