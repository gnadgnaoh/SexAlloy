package io.github.nexalloy.morphe.tiktok.telemetry

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Modifier

val BlockOrbuLogUpload = patch(
    name = "Block Orbu log upload",
    description = "Stops the Orbu SDK from uploading its activity logs, which it sends thousands of times a day. " +
        "Videos and recommendations are not affected.",
) {
    val unit = classLoader.loadClass("kotlin.Unit")
    val done = unit.declaredFields.single { Modifier.isStatic(it.modifiers) && it.type == unit }
        .apply { isAccessible = true }.get(null)
    ::orbuLogSendFingerprint.method.hookMethod {
        before { param -> param.result = done }
    }
}
