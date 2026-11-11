package io.github.nexalloy.revanced.zalo.ads

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.fingerprint
import io.github.nexalloy.revanced.zalo.ZaloFeedKeys
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

private const val ZINSTANT_ADS_VIEW = "com.zing.zalo.social.presentation.timeline.components.ads.FeedItemZInstantAds"

private fun org.luckypray.dexkit.DexKitBridge.zinstantAdsView() =
    getClassData(ZINSTANT_ADS_VIEW) ?: error("No $ZINSTANT_ADS_VIEW in this build")

val feedAdsBindFingerprint = findMethodDirect {
    val candidates = zinstantAdsView().methods.filter { m ->
        m.isMethod && !Modifier.isStatic(m.modifiers) && m.returnTypeName == "void" &&
            !m.name.startsWith("on") && !m.name.startsWith("set")
    }
    val widest = candidates.maxOf { it.paramCount }
    candidates.single { it.paramCount == widest }
}

val feedAdsLayoutHeightFingerprint = findMethodDirect {
    zinstantAdsView().methods.single { it.name == "getZInstantLayoutHeight" && it.returnTypeName == "int" }
}

val feedItemPreCheckFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingStrings(listOf(ZaloFeedKeys.PRE_CHECK), StringMatchType.Equals)
            returnType = "boolean"
            paramTypes(listOf("org.json.JSONObject"))
        }
    }.filter { candidate ->
        candidate.invokes.any { it.className == "org.json.JSONArray" && it.name == "get" }
    }.single()
}

val storyAdsBindFingerprint = fingerprint {
    strings("click_story_ad_cta", "click_name_story_ad", "send_message_story_ad")
    returns("V")
}

val outstreamAdsLayoutFingerprint = fingerprint {
    strings("outstream_ads_close", "outstream_ads_skip", "skip_ads_second")
    returns("V")
}

val adsTemplateLayoutFingerprint = fingerprint {
    strings("cta_ad_show")
    returns("V")
}

val adsNativeLayoutFingerprint = fingerprint {
    name("getStartTimeShow")
    returns("J")
    parameters()
    classMatcher { className(".AdsNativeLayout", StringMatchType.EndsWith) }
}

val advertisingItemFingerprints = findMethodListDirect {
    findMethod {
        matcher {
            name = "getAdvertisingContent"
            returnType = "com.zing.zalo.shortvideo.domain.entity.content.Content"
            paramCount = 0
        }
    }
}

internal const val STORY_ADS_ENABLE_KEY = "social@story@story_ads@enable"
internal const val COMMUNITY_ADS_ENABLE_KEY = "community.community_ads.enable"

val remoteConfigIntGetterFingerprint = findMethodDirect {
    fun configGettersCalledBy(key: String): Set<String> =
        findMethod { matcher { usingEqStrings(key) } }
            .map { site ->
                site.invokes.filter { it.isStaticStringIntToInt() }.map { it.descriptor }.toSet()
            }
            .reduce { acc, next -> acc intersect next }

    val descriptor = (configGettersCalledBy(STORY_ADS_ENABLE_KEY) intersect
        configGettersCalledBy(COMMUNITY_ADS_ENABLE_KEY)).single()
    getMethodData(descriptor)!!
}

private fun MethodData.isStaticStringIntToInt() =
    isMethod && Modifier.isStatic(modifiers) &&
        paramTypeNames == listOf("java.lang.String", "int") && returnTypeName == "int"

internal const val ZINSTANT_AD_ITEM_VIEW_CLASS = "com.zing.zalo.ui.widget.ZinstantAdItemView"
private const val MESSAGES_VIEW_CLASS = "com.zing.zalo.ui.maintab.msg.MessagesView"

val zinstantAdItemBindFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass = ZINSTANT_AD_ITEM_VIEW_CLASS
            returnType = "void"
            paramCount = 3
        }
    }.single { it.paramTypeNames.drop(1) == listOf(MESSAGES_VIEW_CLASS, "boolean") }
}
