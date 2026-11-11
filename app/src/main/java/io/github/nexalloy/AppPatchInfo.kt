package io.github.nexalloy

import io.github.nexalloy.hoodles.morphe.alltrails.AllTrailsPatches
import io.github.nexalloy.hoodles.morphe.protonvpn.ProtonVpnPatches
import io.github.nexalloy.v4n1x.morphe.soundcloud.SoundCloudPatches
import io.github.nexalloy.morphe.google.GoogleDiscoverPatches
import io.github.nexalloy.morphe.music.YTMusicPatches
import io.github.nexalloy.morphe.reddit.RedditPatches
import io.github.nexalloy.morphe.youtube.YouTubePatches
import io.github.nexalloy.revanced.googlephotos.GooglePhotosPatches
import io.github.nexalloy.revanced.instagram.InstagramPatches
import io.github.nexalloy.revanced.threads.ThreadsPatches
import io.github.nexalloy.revanced.facebook.FacebookPatches
import io.github.nexalloy.revanced.messenger.MessengerPatches
import io.github.nexalloy.revanced.photomath.PhotomathPatches
import io.github.nexalloy.revanced.strava.StravaPatches
import io.github.nexalloy.revanced.zalo.ZaloPatches 
import io.github.nexalloy.revanced.whatsapp.WhatsAppPatches
import io.github.nexalloy.revanced.discord.DiscordPatches
import io.github.nexalloy.revanced.pinterest.PinterestPatches
import io.github.nexalloy.mrxsin.gmail.GmailPatches
import io.github.nexalloy.morphe.twitter.TwitterPatches
import io.github.nexalloy.morphe.tiktok.TikTokPatches

enum class DexSource { APK_PATH, APK_WITH_SPLITS, CLASS_LOADER }

class AppPatchInfo(
    val appName: String,
    val packageName: String,
    val patches: Array<Patch>,
    val dexSource: DexSource = DexSource.APK_PATH,
)

// Explicit types: PatchExecutor reads dexSourceByPackage, and patch bodies (e.g. `fieldList`)
// depend on PatchExecutor, so inferring these would form a cycle.
val appPatchConfigurations: List<AppPatchInfo> = listOf(
    AppPatchInfo("Proton VPN", "ch.protonvpn.android", ProtonVpnPatches),
    AppPatchInfo("Zalo", "com.zing.zalo", ZaloPatches),
    AppPatchInfo("WhatsApp", "com.whatsapp", WhatsAppPatches),
    AppPatchInfo("Discord", "com.discord", DiscordPatches),
    AppPatchInfo("Pinterest", "com.pinterest", PinterestPatches),
    AppPatchInfo("YouTube", "com.google.android.youtube", YouTubePatches),
    AppPatchInfo("YT Music", "com.google.android.apps.youtube.music", YTMusicPatches),
    AppPatchInfo("Reddit", "com.reddit.frontpage", RedditPatches),
    AppPatchInfo("Google Photos", "com.google.android.apps.photos", GooglePhotosPatches),
    AppPatchInfo("Photomath", "com.microblink.photomath", PhotomathPatches),
    AppPatchInfo("Instagram", "com.instagram.android", InstagramPatches),
    AppPatchInfo("Threads", "com.instagram.barcelona", ThreadsPatches),
    AppPatchInfo("Strava", "com.strava", StravaPatches),
    AppPatchInfo("AllTrails", "com.alltrails.alltrails", AllTrailsPatches),
    AppPatchInfo("SoundCloud", "com.soundcloud.android", SoundCloudPatches),
    AppPatchInfo("Facebook", "com.facebook.katana", FacebookPatches, DexSource.CLASS_LOADER),
    AppPatchInfo("Messenger", "com.facebook.orca", MessengerPatches),
    AppPatchInfo("Google (Discover)", "com.google.android.googlequicksearchbox", GoogleDiscoverPatches),
    AppPatchInfo("Twitter/X", "com.twitter.android", TwitterPatches),
    AppPatchInfo("Gmail", "com.google.android.gm", GmailPatches),
    AppPatchInfo("TikTok", "com.zhiliaoapp.musically", TikTokPatches, DexSource.APK_WITH_SPLITS),
    AppPatchInfo("TikTok (Asia)", "com.ss.android.ugc.trill", TikTokPatches, DexSource.APK_WITH_SPLITS),
)

val patchesByPackage: Map<String, Array<Patch>> = appPatchConfigurations.associate { it.packageName to it.patches }
val dexSourceByPackage: Map<String, DexSource> = appPatchConfigurations.associate { it.packageName to it.dexSource }
