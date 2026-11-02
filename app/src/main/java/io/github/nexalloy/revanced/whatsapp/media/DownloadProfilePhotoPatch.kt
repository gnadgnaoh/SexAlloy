package io.github.nexalloy.revanced.whatsapp.media

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.Menu
import android.view.View
import android.widget.ImageView
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.whatsapp.core.WaUi

private const val PROFILE_ITEM = 0x4E41_5703

private fun Drawable.toBitmap(): Bitmap? {
    if (this is BitmapDrawable) return bitmap
    if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return null
    val bitmap = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}

val DownloadProfilePhoto = patch(
    name = "Download profile photo",
    description = "Adds \"Download\" to the menu when viewing a profile photo. Saves the photo as shown, without extra compression.",
) {
    classLoader.loadClass(PROFILE_PHOTO_ACTIVITY).getDeclaredMethod("onCreateOptionsMenu", Menu::class.java).hookMethod {
        after { param ->
            val activity = param.thisObject as Activity
            val menu = param.args[0] as? Menu ?: return@after
            if (menu.findItem(PROFILE_ITEM) != null) return@after
            menu.add(Menu.NONE, PROFILE_ITEM, Menu.CATEGORY_SECONDARY, WaUi.t(activity, R.string.wa_download)).setOnMenuItemClickListener {
                val id = activity.resources.getIdentifier("picture", "id", activity.packageName)
                val bitmap = (activity.findViewById<View>(id) as? ImageView)?.drawable?.toBitmap()
                if (bitmap == null) WaUi.toast(activity, WaUi.t(activity, R.string.wa_media_unavailable))
                else WhatsAppSaver.saveBitmap(activity, bitmap, "Profile")
                true
            }
            param.result = true
        }
    }
}
