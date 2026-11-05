package io.github.nexalloy.revanced.pinterest.media

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.pinterest.download.mp4Rank

private const val HEVC_MP4 = "V_HEVC_MP4_"

val BestVideoQuality = patch(
    name = "Best video quality",
    description = "Plays the sharpest MP4 Pinterest offers (up to 720p) everywhere in the app instead of adaptive streams. Uses noticeably more data.",
) {
    val tracksField = ::trackListFingerprint.field.apply { isAccessible = true }
    val urlField = ::trackUrlFingerprint.field.apply { isAccessible = true }

    ::trackSelectorFingerprint.hookMethod {
        after { param ->
            val tracks = param.args[0]?.let { tracksField.get(it) } as? Map<*, *> ?: return@after
            tracks.entries.mapNotNull { (key, track) ->
                val name = (key as? String)?.takeIf { it.startsWith(HEVC_MP4) } ?: return@mapNotNull null
                val url = track?.let { urlField.get(it) as? String } ?: return@mapNotNull null
                track to mp4Rank(name, url, 0)
            }.maxByOrNull { it.second }?.let { param.result = it.first }
        }
    }
}
