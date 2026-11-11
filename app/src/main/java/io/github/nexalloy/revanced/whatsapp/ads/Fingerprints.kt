package io.github.nexalloy.revanced.whatsapp.ads

import io.github.nexalloy.morphe.findMethodDirect
import java.lang.reflect.Modifier

val wamoEnabledFingerprint = findMethodDirect {
    findMethod { matcher { usingEqStrings("Wamo abprop is not enabled") } }
        .map { gate ->
            gate.invokes.filter {
                it.isMethod && Modifier.isStatic(it.modifiers) && it.returnTypeName == "boolean" && it.paramCount == 2
            }.map { it.descriptor }.toSet()
        }
        .reduce { a, b -> a intersect b }
        .single()
        .let { getMethodData(it)!! }
}
