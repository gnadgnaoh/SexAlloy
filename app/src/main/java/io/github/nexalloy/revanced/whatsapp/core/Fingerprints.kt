package io.github.nexalloy.revanced.whatsapp.core

import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData

internal const val CONVERSATION_ACTIVITY = "com.whatsapp.Conversation"

private fun DexKitBridge.jobRun(job: String, anchor: String): MethodData =
    findClass { matcher { className(job, StringMatchType.EndsWith) } }.single()
        .findMethod { matcher { returnType = "void"; paramCount = 0; usingEqStrings(anchor) } }.single()

val readReceiptJobRunFingerprint = findMethodDirect { jobRun(".SendReadReceiptJob", "receipt") }

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
