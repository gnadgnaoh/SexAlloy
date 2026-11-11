package io.github.nexalloy.revanced.pinterest

import io.github.nexalloy.Patch
import io.github.nexalloy.revanced.pinterest.ads.HideAds
import io.github.nexalloy.revanced.pinterest.download.EnableImageDownload
import io.github.nexalloy.revanced.pinterest.download.EnableVideoDownload
import io.github.nexalloy.revanced.pinterest.media.BestVideoQuality
import io.github.nexalloy.revanced.pinterest.media.OriginalImages
import io.github.nexalloy.revanced.pinterest.privacy.DisableAnalytics
import io.github.nexalloy.revanced.pinterest.privacy.OpenLinksInBrowser
import io.github.nexalloy.revanced.pinterest.privacy.StripLinkTracking

val PinterestPatches = arrayOf<Patch>(
    HideAds,
    EnableImageDownload,
    EnableVideoDownload,
    OriginalImages,
    BestVideoQuality,
    DisableAnalytics,
    OpenLinksInBrowser,
    StripLinkTracking,
)
