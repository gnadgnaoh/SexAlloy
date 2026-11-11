package io.github.nexalloy.revanced.pinterest.media

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.pinterest.download.mp4Rank

private const val HEVC_MP4 = "V_HEVC_MP4_"

val BestVideoQuality = patch(
    name = "Best video quality",
    description = "Plays the sharpest MP4 Pinterest offers (up to 720p) everywhere in the app instead of adaptive streams. Uses noticeably more data.",
) {
    val urlField = ::videoUrlFieldFingerprint.field.apply { isAccessible = true }
    val detailsClass = urlField.declaringClass
    val mapIndex = ::trackSelectorFingerprint.method.parameterTypes.indexOf(Map::class.java)

    ::trackSelectorFingerprint.hookMethod {
        before { param ->
            val tracks = param.args[mapIndex] as? Map<*, *> ?: return@before
            val best = tracks.entries.mapNotNull { entry ->
                val name = (entry.key as? String)?.takeIf { it.startsWith(HEVC_MP4) } ?: return@mapNotNull null
                val details = entry.value?.takeIf(detailsClass::isInstance) ?: return@mapNotNull null
                val url = urlField.get(details) as? String ?: return@mapNotNull null
                entry to mp4Rank(name, url, 0)
            }.maxByOrNull { it.second }?.first ?: return@before
            param.args[mapIndex] = linkedMapOf(best.key to best.value)
        }
    }
}
