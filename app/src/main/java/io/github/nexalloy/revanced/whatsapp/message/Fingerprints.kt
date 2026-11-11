package io.github.nexalloy.revanced.whatsapp.message

import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.revanced.whatsapp.core.fMessageClassFingerprint
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

val revokeMessageFingerprint = findMethodDirect {
    findMethod { matcher { usingEqStrings("msgstore/revoking/has-placeholder ") } }.single()
}

val revokeTargetFieldFingerprint = findFieldDirect {
    val message = fMessageClassFingerprint().name
    revokeMessageFingerprint().usingFields.first { use ->
        use.usingType == FieldUsingType.Read && use.field.typeName == "java.lang.String" &&
            findClass { matcher { className(use.field.declaredClassName, StringMatchType.Equals) } }.singleOrNull()?.superClass?.name == message
    }.field
}

val noneAckClassFingerprint = findClassDirect {
    val result = revokeMessageFingerprint().returnType!!
    val ack = result.methods.first { it.isConstructor }.paramTypes.first { "." in it.name }
    findClass { matcher { addInterface(ack.name); addMethod { name = "toString"; usingEqStrings("None") } } }.single()
}

val statusRevokeFingerprint = findMethodDirect {
    val result = findClass {
        matcher { superClass = "java.lang.Enum"; addMethod { name = "<clinit>"; usingEqStrings("DIDNT_STORE") } }
    }.single().name
    findMethod {
        matcher { returnType = result; usingStrings(listOf("RevokeStatusManager/"), StringMatchType.StartsWith) }
    }.single()
}

val ephemeralValuesFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "android.content.ContentValues"
            usingEqStrings("expire_timestamp", "ephemeral_initiated_by_me", "ephemeral_trigger")
        }
    }.single()
}

val forwardActionFingerprint = findMethodDirect {
    findMethod { matcher { usingEqStrings("UserActionsMessageForwarding/userActionForwardMessage") } }.single()
}

val messageFlagSetterFingerprint = findMethodDirect {
    val message = fMessageClassFingerprint().name
    findMethod { matcher { usingStrings(listOf("chatInfo/incrementUnseenImportantMessageCount"), StringMatchType.StartsWith) } }.single()
        .invokes.filter { it.declaredClassName == message && it.paramTypeNames == listOf("long") && it.returnTypeName == "void" }
        .distinctBy { it.descriptor }.single()
}

val forwardLimitClassFingerprint = findClassDirect {
    findClass { matcher { usingStrings("MultiSelectionLimitInfo") } }.single()
}

val deleteDialogFingerprint = findMethodDirect {
    findMethod { matcher { usingEqStrings("dialog/delete no messages"); usingNumbers(216000000L) } }.single()
}

val serverTimeFingerprint = findMethodDirect {
    val clock = findClass { matcher { usingStrings("app/time server update processed") } }.single()
    clock.findMethod { matcher { modifiers = Modifier.STATIC; returnType = "long"; paramTypes(clock.name) } }.single()
}

val seeMoreLimitFingerprint = findMethodDirect {
    findClass {
        matcher {
            addMethod { name = "<clinit>"; usingNumbers(16, 16384, 512, 64) }
            addMethod { name = "<init>"; paramTypes("int", "int") }
        }
    }.single().findMethod { matcher { name = "<init>"; paramTypes("int", "int") } }.single()
}

val copyMessagesFingerprint = findMethodDirect {
    findMethod { matcher { usingEqStrings("conversation/copymessage/npe") } }.single()
}



private fun DexKitBridge.editInfoInsert(): MethodData =
    findMethod { matcher { usingEqStrings("MessageEditInfoStore/insertEditInfo/missing information in the FMessage") } }.single()

val editHandlerFingerprint = findMethodDirect {
    findMethod {
        matcher {
            modifiers = Modifier.STATIC
            usingEqStrings("EditedMessageManager/handleEditedMessage/storeMessageAddOnExtraInfo failed!")
        }
    }.single()
}

val editInfoGetterFingerprint = findMethodDirect {
    val message = fMessageClassFingerprint().name
    editInfoInsert().invokes.filter {
        it.isMethod && Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(message) &&
            it.declaredClassName != message && it.returnTypeName != "void"
    }.distinctBy { it.descriptor }.single()
}

val editedAtFieldFingerprint = findFieldDirect {
    val info = editInfoGetterFingerprint().returnTypeName
    editInfoInsert().usingFields.first {
        it.usingType == FieldUsingType.Read && it.field.declaredClassName == info && it.field.typeName == "long"
    }.field
}
