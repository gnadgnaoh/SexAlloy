package io.github.nexalloy.revanced.zalo.privacy

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.fingerprint
import java.lang.reflect.Modifier

private const val SHOW_ONLINE_SETTING = "PRIVACY_SETTINGS_SHOW_ONLINE_STATUS_"
private const val ONLINE_FRIENDS_SETTING = "SETTING_SHOW_ONLINE_FRIEND_WITH_STATUS"
private const val MAIN_TAB_VIEW = "com.zing.zalo.ui.maintab.MainTabView"
private const val REQUEST_PACKET = "com.zing.zalocore.connection.socket.RequestPacket"
private const val SEEN_KEY = "seen"
private const val DATA_KEY = "data"

val seenAckSocketFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            usingEqStrings(SEEN_KEY, DATA_KEY)
            addInvoke { declaredClass(REQUEST_PACKET); name = "<init>" }
        }
    }.single { it.paramTypeNames.firstOrNull() == "java.util.List" && it.paramTypeNames.getOrNull(1) == "boolean" }
}

val seenAckRepositoryFingerprint = fingerprint {
    strings("ACK receive msg | ", " | seen=")
    returns("V")
}

val typingSocketFingerprint = findMethodDirect {
    seenAckSocketFingerprint().declaredClass!!.methods.single { m ->
        m.isMethod && m.returnTypeName == "void" && m.paramTypeNames == listOf("java.lang.String", "int", "boolean", "boolean")
    }
}

val typingRepositoryFingerprint = fingerprint {
    classMatcher { usingStrings("ACK receive msg | ") }
    parameters("Ljava/lang/String;", "I", "Z", "Z")
    returns("V")
}

val showOnlineSettingFingerprint = findMethodDirect {
    findMethod {
        matcher { usingStrings(SHOW_ONLINE_SETTING); returnType = "boolean"; paramCount = 0 }
    }.single { Modifier.isStatic(it.modifiers) }
}

val onlineStatusReadersFingerprint = findMethodListDirect {
    showOnlineSettingFingerprint().callers.filter { m ->
        (m.returnTypeName == "long" && m.paramTypeNames == listOf("java.lang.String")) ||
            ONLINE_FRIENDS_SETTING in m.usingStrings ||
            (m.paramCount == 0 && m.returnTypeName == "void" && m.invokes.any { it.declaredClassName == MAIN_TAB_VIEW })
    }.distinctBy { it.descriptor }.also { check(it.size >= 3) { "expected the online status readers, found ${it.size}" } }
}

val lastActiveRuleFingerprint = findMethodDirect {
    findMethod {
        matcher { usingStrings("ENABLE_LAST_ACTIVE_NEW_RULE"); returnType = "int"; paramCount = 0 }
    }.single { Modifier.isStatic(it.modifiers) }
}
