package io.github.nexalloy.revanced.whatsapp

import io.github.nexalloy.Patch
import io.github.nexalloy.revanced.whatsapp.ads.BlockAds
import io.github.nexalloy.revanced.whatsapp.media.CopyStatusText
import io.github.nexalloy.revanced.whatsapp.media.DownloadProfilePhoto
import io.github.nexalloy.revanced.whatsapp.media.DownloadStatus
import io.github.nexalloy.revanced.whatsapp.media.MediaQuality
import io.github.nexalloy.revanced.whatsapp.media.RepostStatus
import io.github.nexalloy.revanced.whatsapp.media.ShareStatus
import io.github.nexalloy.revanced.whatsapp.media.ViewOnceUnlimited
import io.github.nexalloy.revanced.whatsapp.message.AntiDeleteStatus
import io.github.nexalloy.revanced.whatsapp.message.AntiDisappearing
import io.github.nexalloy.revanced.whatsapp.message.AntiRevoke
import io.github.nexalloy.revanced.whatsapp.message.CopyWithoutStamp
import io.github.nexalloy.revanced.whatsapp.message.HideForwardedTag
import io.github.nexalloy.revanced.whatsapp.message.RemoveForwardLimit
import io.github.nexalloy.revanced.whatsapp.message.RemoveSeeMore
import io.github.nexalloy.revanced.whatsapp.message.RevokeTimeLimit
import io.github.nexalloy.revanced.whatsapp.message.ShowEditHistory
import io.github.nexalloy.revanced.whatsapp.privacy.HideComposing
import io.github.nexalloy.revanced.whatsapp.privacy.HideDeliveryReceipts
import io.github.nexalloy.revanced.whatsapp.privacy.HideMediaPlayed
import io.github.nexalloy.revanced.whatsapp.privacy.HideReadReceipts
import io.github.nexalloy.revanced.whatsapp.privacy.HideStatusView
import io.github.nexalloy.revanced.whatsapp.privacy.MarkAsReadButton
import io.github.nexalloy.revanced.whatsapp.privacy.SendReadOnReply

val WhatsAppPatches = arrayOf<Patch>(
    HideReadReceipts,
    SendReadOnReply,
    MarkAsReadButton,
    HideDeliveryReceipts,
    HideComposing,
    HideMediaPlayed,
    HideStatusView,
    BlockAds,
    AntiRevoke,
    AntiDeleteStatus,
    AntiDisappearing,
    HideForwardedTag,
    RemoveForwardLimit,
    RevokeTimeLimit,
    RemoveSeeMore,
    CopyWithoutStamp,
    CopyStatusText,
    ShowEditHistory,
    MediaQuality,
    ViewOnceUnlimited,
    DownloadStatus,
    ShareStatus,
    RepostStatus,
    DownloadProfilePhoto,
)
