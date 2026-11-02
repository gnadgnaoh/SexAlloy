package io.github.nexalloy.revanced.pinterest

import io.github.nexalloy.Patch
import io.github.nexalloy.revanced.pinterest.ads.HideAds
import io.github.nexalloy.revanced.pinterest.download.EnableImageDownload
import io.github.nexalloy.revanced.pinterest.download.EnableVideoDownload

val PinterestPatches = arrayOf<Patch>(
    HideAds,
    EnableImageDownload,
    EnableVideoDownload,
)
