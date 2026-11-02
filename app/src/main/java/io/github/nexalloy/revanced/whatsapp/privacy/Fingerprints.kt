package io.github.nexalloy.revanced.whatsapp.privacy

import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

val deliveryReceiptEncoderFingerprint = findMethodDirect {
    findMethod {
        matcher {
            modifiers = Modifier.STATIC
            paramCount = 2
            usingEqStrings("ProtocolTreeEncoder/encodeMessageReceived sending inactive receipt with recipient attr key=")
        }
    }.single()
}

val receiptNodeBuilderFingerprint = findMethodDirect {
    val encoder = deliveryReceiptEncoderFingerprint()
    encoder.invokes.filter {
        it.isMethod && Modifier.isStatic(it.modifiers) &&
            it.declaredClassName == encoder.declaredClassName &&
            it.returnTypeName == encoder.returnTypeName &&
            it.paramTypeNames.count { p -> p == "java.lang.String" } >= 3
    }.distinctBy { it.descriptor }.single()
}

val composingSenderFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            usingEqStrings("HandleMeComposing/sendComposing; toJid=")
        }
    }.single { "int" in it.paramTypeNames }
}

val playedReceiptJobRunFingerprint = findMethodDirect {
    findClass { matcher { className(".SendPlayedReceiptJob", StringMatchType.Contains) } }.single()
        .findMethod { matcher { returnType = "void"; paramCount = 0; usingEqStrings("played") } }.single()
}
