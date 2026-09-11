package io.github.nexalloy.morphe.tiktok

import io.github.nexalloy.Patch
import io.github.nexalloy.morphe.tiktok.ads.HidePromotedMusicVideos
import io.github.nexalloy.morphe.tiktok.ads.RemoveFeedAds

const val TIKTOK_PACKAGE_NAME = "com.zhiliaoapp.musically"
const val TIKTOK_ASIA_PACKAGE_NAME = "com.ss.android.ugc.trill"

val TikTokPatches = arrayOf<Patch>(
    RemoveFeedAds,
    HidePromotedMusicVideos,
)
