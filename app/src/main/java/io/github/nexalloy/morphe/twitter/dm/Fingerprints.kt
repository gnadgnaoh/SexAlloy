package io.github.nexalloy.morphe.twitter.dm

import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.FieldUsingType
import java.lang.reflect.Modifier

private const val EVENT_DETAIL = "com.x.dmv2.thriftjava.MessageEventDetail"
internal const val MARK_READ_EVENT = "com.x.dmv2.thriftjava.MessageEventDetail\$MarkConversationReadEvent"
internal const val TYPING_EVENT = "com.x.dmv2.thriftjava.MessageTypingEvent"
internal const val TYPING_EVENT_DETAIL = "com.x.dmv2.thriftjava.MessageEventDetail\$MessageTypingEvent"
internal const val DELETE_ACTION = "com.x.dmv2.thriftjava.DeleteMessageAction"
internal const val DELETE_FOR_ALL = "DELETE_FOR_ALL"

val lastSeenSyncJobFingerprint = findMethodDirect {
    findMethod { matcher { usingEqStrings("XWSLastSeenSyncJob"); paramTypes("kotlin.coroutines.Continuation") } }.single()
}

val messageEventSenderFingerprint = findMethodDirect {
    lastSeenSyncJobFingerprint().invokes
        .filter { it.isMethod && Modifier.isStatic(it.modifiers) && EVENT_DETAIL in it.paramTypeNames }
        .distinctBy { it.descriptor }.single()
}

val startTtlQueryFingerprint = findMethodDirect {
    findMethod { matcher { usingStrings(listOf("SET ttl_started_at_msec = ?"), StringMatchType.Contains) } }.single()
}

val typingEmitFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name = "emit"
            addInvoke { declaredClass(TYPING_EVENT, StringMatchType.Equals); name = "<init>" }
        }
    }.single()
}

val messageDeleteHandlerFingerprint = findMethodDirect {
    findMethod { matcher { usingEqStrings("got MessageDeleteEvent ") } }.single { DELETE_ACTION in it.paramTypeNames }
}

val messageDeleteSkippedFingerprint = findFieldDirect {
    messageDeleteHandlerFingerprint().usingFields
        .filter { it.usingType == FieldUsingType.Read && it.field.typeName == it.field.declaredClassName }
        .map { it.field }.distinctBy { it.descriptor }
        .single { field ->
            findClass { matcher { className(field.declaredClassName, StringMatchType.Equals) } }.single().superClass?.name != "java.lang.Enum"
        }
}

val ownDeletePayloadFingerprint = findClassDirect {
    findClass { matcher { usingStrings(listOf("DeleteMessagesPayload(conversationId="), StringMatchType.StartsWith) } }.single()
}

private const val INSTANT = "kotlin.time.Instant"

val longPressActionsFingerprint = findMethodDirect {
    findClass { matcher { usingEqStrings("LongPress(message=", ", canEdit=") } }.single()
        .findMethod { matcher { returnType = "java.util.List"; paramCount = 0 } }.single()
}

val longPressMessageFingerprint = findFieldDirect {
    findClass { matcher { usingEqStrings("LongPress(message=", ", canEdit=") } }.single()
        .findMethod { matcher { name = "toString"; paramCount = 0 } }.single()
        .usingFields.first { it.usingType == FieldUsingType.Read }.field
}

val replyActionClassFingerprint = findClassDirect {
    findClass { matcher { usingEqStrings("Reply(message=", ", attachmentId=") } }.single()
}

val editActionClassFingerprint = findClassDirect {
    findClass { matcher { usingEqStrings("Edit(message=") } }.single()
}

val actionIconFingerprint = findMethodDirect {
    val base = replyActionClassFingerprint().superClass!!.name
    findMethod { matcher { modifiers = Modifier.STATIC; paramTypes(base) } }
        .single { it.returnTypeName != "int" && it.returnTypeName != "boolean" && it.returnTypeName != "void" }
}

val actionLabelFingerprint = findMethodDirect {
    val base = replyActionClassFingerprint().superClass!!.name
    val icon = actionIconFingerprint()
    findMethod { matcher { declaredClass(icon.className, StringMatchType.Equals); modifiers = Modifier.STATIC; paramTypes(base); returnType = "int" } }.single()
}

val actionHandlerFingerprint = findMethodDirect {
    val base = replyActionClassFingerprint().superClass!!.name
    findMethod { matcher { paramTypes(base); returnType = "void" } }.single { it.name != "<init>" }
}

val editItemClassFingerprint = findClassDirect {
    findClass { matcher { usingEqStrings("EditItem(editId=", ", previousText=") } }.single()
}

val editItemTextFingerprint = findFieldDirect {
    editItemClassFingerprint().findMethod { matcher { name = "toString"; paramCount = 0 } }.single()
        .usingFields.filter { it.usingType == FieldUsingType.Read && it.field.typeName == "java.lang.String" }
        .map { it.field }.distinctBy { it.descriptor }[1]
}

val editItemTimeFingerprint = findFieldDirect {
    editItemClassFingerprint().fields.single { it.typeName == INSTANT }
}
