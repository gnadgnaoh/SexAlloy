package io.github.nexalloy.revanced.whatsapp.media

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Field

private const val IMAGE_MAX_KB = 50 * 1024
private const val IMAGE_QUALITY = 95
private const val IMAGE_MAX_EDGE = 8192
private const val VIDEO_MAX_EDGE = 4096
private const val VIDEO_MAX_BITRATE = 16_000_000
private const val VIDEO_FRAME_RATE = 60

private fun raise(target: Any, field: Field, value: Int) {
    if (field.getInt(target) < value) field.setInt(target, value)
}

private fun Field.open() = apply { isAccessible = true }

val MediaQuality = patch(
    name = "Send media in HD",
    description = "Photos and videos you send keep their full resolution: photos up to 8192 px at high quality, videos up to 4K at 16 Mbps and 60 fps. Files are larger.",
) {
    val image = ::imageQualityFieldsFingerprint.fieldList.map { it.open() }
    val (maxKb, quality, imageEdge) = image
    image.first().declaringClass.declaredConstructors.forEach { ctor ->
        ctor.hookMethod {
            after { param ->
                val target = param.thisObject
                raise(target, maxKb, IMAGE_MAX_KB)
                raise(target, quality, IMAGE_QUALITY)
                raise(target, imageEdge, IMAGE_MAX_EDGE)
            }
        }
    }
    val video = ::videoQualityFieldsFingerprint.fieldList.map { it.open() }
    val videoEdge = video[1]
    val bitrate = video[2]
    val frameRate = video[3]
    video.first().declaringClass.declaredConstructors.forEach { ctor ->
        ctor.hookMethod {
            after { param ->
                val target = param.thisObject
                raise(target, videoEdge, VIDEO_MAX_EDGE)
                raise(target, bitrate, VIDEO_MAX_BITRATE)
                raise(target, frameRate, VIDEO_FRAME_RATE)
            }
        }
    }
}
