package io.github.nexalloy.revanced.pinterest.ads

import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

private const val LIST = "java.util.List"

private fun DexKitBridge.listConstructor(marker: String): MethodData {
    val holder = findClass { matcher { usingEqStrings(marker) } }.single()
    return holder.findMethod { matcher { name = "<init>" } }
        .filter { LIST in it.paramTypeNames }
        .single { ctor -> ctor.invokes.none { it.className == holder.name && it.name == "<init>" } }
}

val feedListFingerprint = findMethodDirect { listConstructor(", _items count:") }

val pagedResponseListFingerprint = findMethodDirect { listConstructor("PagedResponse(bookmark=") }

val modelListFingerprint = findMethodDirect { listConstructor("ModelListWithBookmark(models=") }
