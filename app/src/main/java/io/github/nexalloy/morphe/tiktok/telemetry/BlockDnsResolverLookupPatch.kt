package io.github.nexalloy.morphe.tiktok.telemetry

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook
import java.net.InetAddress

private val LOOPBACK = byteArrayOf(127, 0, 0, 1)

val BlockDnsResolverLookup = patch(
    name = "Block DNS resolver lookup",
    description = "Stops TikTok from looking up whoami.akamai.net to learn which DNS server your phone uses. " +
        "The lookup answers 127.0.0.1 on the phone, so nothing is sent. Video loading is not affected.",
) {
    val getByName = InetAddress::class.java.getMethod("getByName", String::class.java)
    val hook = ScopedFrameworkHook(getByName, before = { param ->
        if (param.args[0] == RESOLVER_LOOKUP_HOST) param.result = InetAddress.getByAddress(RESOLVER_LOOKUP_HOST, LOOPBACK)
    })
    ::resolverLookupFingerprints.dexMethodList.forEach { hook.installOn(it.getMethodInstance(classLoader)) }
}
