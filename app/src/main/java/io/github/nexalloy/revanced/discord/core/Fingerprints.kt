package io.github.nexalloy.revanced.discord.core

import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.query.enums.StringMatchType

private const val NETWORKING_MODULE = "com.facebook.react.modules.network.NetworkingModule"
private const val NETWORK_EVENTS = "com.facebook.react.modules.network.NetworkEventUtil"

val sendRequestFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass(NETWORKING_MODULE, StringMatchType.Equals)
            name = "sendRequestInternalReal"
        }
    }.single()
}

val responseReceivedFingerprint = findMethodDirect {
    findMethod { matcher { declaredClass(NETWORK_EVENTS, StringMatchType.Equals); name = "onResponseReceived" } }.single()
}

val dataReceivedFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass(NETWORK_EVENTS, StringMatchType.Equals)
            name = "onDataReceived"
            paramTypes(null, "int", "java.lang.String", "java.lang.String", "java.lang.String")
        }
    }.single()
}

val requestSuccessFingerprint = findMethodDirect {
    findMethod { matcher { declaredClass(NETWORK_EVENTS, StringMatchType.Equals); name = "onRequestSuccess" } }.single()
}
