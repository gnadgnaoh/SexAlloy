package io.github.nexalloy.revanced.discord.privacy

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.query.enums.StringMatchType

val sentryEnabledFingerprint = findMethodDirect {
    val options = findClass {
        matcher { className("io.sentry.android.core.SentryAndroidOptions", StringMatchType.Equals) }
    }.single().superClass!!.name
    findMethod {
        matcher { declaredClass(options, StringMatchType.Equals); name = "isEnabled"; returnType = "boolean"; paramCount = 0 }
    }.single()
}

val enableTouchLoggingFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass("com.discord.analytics.touch.TouchEventAnalyticsModule", StringMatchType.Equals)
            name = "enableTouchLogging"
        }
    }.single()
}

val appsFlyerStartFingerprint = findMethodListDirect {
    findClass { matcher { superClass("com.appsflyer.AppsFlyerLib") } }.single()
        .findMethod { matcher { name = "start"; returnType = "void" } }
}

val advertisingIdFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass("com.discord.ads.AdsModule", StringMatchType.Equals)
            name = "getGoogleAdvertisingId"
        }
    }.single()
}

val advertisingIdEmptyFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass("com.discord.ads.AdsModule", StringMatchType.Equals)
            name = "resolveWithNullId"
        }
    }.single()
}
