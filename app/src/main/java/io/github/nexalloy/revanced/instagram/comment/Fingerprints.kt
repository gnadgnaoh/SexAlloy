package io.github.nexalloy.revanced.instagram.comment

import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findFieldListDirect
import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

private const val FRAGMENT = "androidx.fragment.app.Fragment"
private const val GIPHY = "com.instagram.api.schemas.CommentGiphyMediaInfoIntf"
private const val MEDIA = "com.instagram.feed.media.Media"
private const val MEDIUM = "com.instagram.common.gallery.Medium"

private fun ClassData.isCommentMedia() = fields.any { it.typeName == MEDIA } && fields.any { it.typeName == MEDIUM }

private fun MethodData.selectedCommentParam() = paramTypes.singleOrNull { p -> p.fields.any { it.type.isCommentMedia() } }

val commentMenuRendererFingerprint = findMethodDirect {
    findMethod {
        matcher {
            modifiers = Modifier.PUBLIC
            returnType = "void"
            paramCount = 5
            addInvoke { declaredClass(FRAGMENT); name = "requireContext" }
        }
    }.single { m ->
        "java.util.List" in m.paramTypeNames && "float" in m.paramTypeNames && m.selectedCommentParam() != null
    }
}

val commentMediaFieldFingerprint = findFieldDirect {
    commentMenuRendererFingerprint().selectedCommentParam()!!.fields.single { it.type.isCommentMedia() }
}

val commentGifHolderFieldFingerprint = findFieldDirect {
    val gifTypes = findClass { matcher { addInterface(GIPHY) } }.map { it.name }.toSet()
    commentMenuRendererFingerprint().selectedCommentParam()!!.fields.single { f ->
        !Modifier.isStatic(f.modifiers) && f.type.fields.any { it.typeName in gifTypes }
    }
}

val menuActionSingletonsFingerprint = findFieldListDirect {
    val copy = findClass { matcher { addMethod { name = "toString"; usingEqStrings("CopyText") } } }.single { c ->
        c.superClass?.methods?.any { m ->
            m.isConstructor && m.paramTypeNames.size == 4 && m.paramTypeNames.drop(1) == listOf("int", "int", "boolean")
        } == true
    }
    val family = copy.superClass!!.name
    findClass { matcher { superClass(family) } }.flatMap { c ->
        c.fields.filter { Modifier.isStatic(it.modifiers) && it.typeName == c.name }
    }
}
