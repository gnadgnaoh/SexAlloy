package io.github.nexalloy.revanced.discord.core

import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.query.enums.StringMatchType

private const val NETWORKING_MODULE = "com.facebook.react.modules.network.NetworkingModule"

val sendRequestFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass(NETWORKING_MODULE, StringMatchType.Equals)
            name = "sendRequestInternalReal"
        }
    }.single()
}

private const val REACT_CONTEXT = "com.facebook.react.bridge.ReactApplicationContext"
private const val STRING = "java.lang.String"

private fun org.luckypray.dexkit.DexKitBridge.networkEvent(event: String, vararg params: String) =
    findMethod { matcher { usingEqStrings(event); paramTypes(REACT_CONTEXT, *params) } }
        .single { java.lang.reflect.Modifier.isStatic(it.modifiers) }

val responseReceivedFingerprint = findMethodDirect {
    networkEvent("didReceiveNetworkResponse", "int", STRING, STRING, "int", "java.util.Map", "long")
}

val dataReceivedFingerprint = findMethodDirect {
    networkEvent("didReceiveNetworkData", "int", STRING, STRING, STRING)
}

val requestSuccessFingerprint = findMethodDirect {
    networkEvent("didCompleteNetworkResponse", "int", STRING, "long")
}
