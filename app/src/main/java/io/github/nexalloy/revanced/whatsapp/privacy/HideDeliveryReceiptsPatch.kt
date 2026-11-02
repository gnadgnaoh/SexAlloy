package io.github.nexalloy.revanced.whatsapp.privacy

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private const val INACTIVE = "inactive"

val HideDeliveryReceipts = patch(
    name = "Hide delivery receipts",
    description = "Senders see one grey tick until you read their message. WhatsApp still confirms delivery to its server, so messages are not resent. " +
        "Unusual receipts may draw attention to your account.",
) {
    val encoder = ::deliveryReceiptEncoderFingerprint.method
    val builder = ::receiptNodeBuilderFingerprint.method
    val params = builder.parameterTypes
    val typeIndex = (0..params.size - 3).first { i ->
        params[i] == String::class.java && params[i + 1] == String::class.java && params[i + 2] == String::class.java
    } + 1
    val encoding = ThreadLocal<Boolean>()
    encoder.hookMethod {
        before { encoding.set(true) }
        after { encoding.remove() }
    }
    builder.hookMethod {
        before { param ->
            if (encoding.get() == true && param.args[typeIndex] == null) {
                param.args[typeIndex] = INACTIVE
            }
        }
    }
}
