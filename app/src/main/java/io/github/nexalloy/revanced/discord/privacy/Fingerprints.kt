package io.github.nexalloy.revanced.discord.privacy

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.query.enums.StringMatchType

val crashReportingDisabledFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass("com.discord.crash_reporting.CrashReporting", StringMatchType.Equals)
            name = "isDisabled"
            returnType = "boolean"
        }
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
