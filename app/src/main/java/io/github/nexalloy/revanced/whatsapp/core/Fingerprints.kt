package io.github.nexalloy.revanced.whatsapp.core

import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData

internal const val CONVERSATION_ACTIVITY = "com.whatsapp.Conversation"

private const val JOB = "org.whispersystems.jobqueue.Job"

private fun DexKitBridge.jobRun(job: String, anchor: String): MethodData =
    findClass { matcher { className(job, StringMatchType.EndsWith) } }.single()
        .findMethod { matcher { returnType = "void"; paramCount = 0; usingEqStrings(anchor) } }.single()

private fun DexKitBridge.jobRunName(): String {
    val abstracts = getClassData(JOB)!!.methods.filter { m ->
        java.lang.reflect.Modifier.isAbstract(m.modifiers) && m.paramCount == 0 && m.returnTypeName == "void"
    }.map { it.name }.toSet()
    return findMethod {
        matcher {
            usingStrings(listOf("/onRun"), StringMatchType.Contains)
            paramCount = 0
            returnType = "void"
            declaredClass { superClass = JOB }
        }
    }.filter { it.name in abstracts }.groupingBy { it.name }.eachCount().maxBy { it.value }.key
}

internal fun DexKitBridge.jobRunOf(job: String): MethodData {
    val run = jobRunName()
    return findClass { matcher { className(job, StringMatchType.Contains); superClass = JOB } }.single()
        .methods.single { it.name == run && it.paramCount == 0 && it.returnTypeName == "void" }
}

val readReceiptJobRunFingerprint = findMethodDirect { jobRunOf(".SendReadReceiptJob") }

val sendMessageJobRunFingerprint = findMethodDirect { jobRun(".SendE2EMessageJob", "SendE2EMessageJob/onRun ") }

val fMessageClassFingerprint = findClassDirect {
    findClass { matcher { usingStrings("FMessage/getSenderUserJid/key.id") } }.single()
}

val messageKeyClassFingerprint = findClassDirect {
    findClass {
        matcher { addMethod { name = "toString"; usingEqStrings("Key(id=", ", isFromMe=", ", chatJid=") } }
    }.single()
}

val messageTextFieldFingerprint = findFieldDirect {
    val message = fMessageClassFingerprint().name
    findMethod { matcher { usingEqStrings("conversation/copymessage/npe") } }.single()
        .usingFields.first { it.usingType == FieldUsingType.Read && it.field.declaredClassName == message && it.field.typeName == "java.lang.String" }
        .field
}

val conversationAdapterFingerprint = findClassDirect {
    findClass { matcher { usingStrings("ConversationCursorAdapter/getItem out-of-bounds ") } }.single()
}

val messageByKeyFingerprint = findMethodDirect {
    findMethod { matcher { usingEqStrings("CachedMessageStore/getMessage/key") } }.single()
}

val inlineReadReceiptFingerprint = findMethodDirect {
    findMethod { matcher { returnType = "boolean"; usingEqStrings("error sending read receipt", "read-receipt") } }.single()
}

val messageDataTextFingerprint = findMethodDirect {
    val message = fMessageClassFingerprint().name
    findMethod {
        matcher {
            declaredClass(message)
            returnType = "java.lang.String"
            paramCount = 0
            addInvoke("Ljava/nio/charset/Charset;->forName(Ljava/lang/String;)Ljava/nio/charset/Charset;")
        }
    }.single()
}
