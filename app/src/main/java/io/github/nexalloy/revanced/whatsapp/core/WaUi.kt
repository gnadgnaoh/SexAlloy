package io.github.nexalloy.revanced.whatsapp.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import app.morphe.extension.shared.Logger
import io.github.nexalloy.BuildConfig
import io.github.nexalloy.modulePath

internal object WaUi {
    val main = Handler(Looper.getMainLooper())

    @Volatile
    private var module: Resources? = null

    private fun resources(ctx: Context): Resources = module ?: (runCatching {
        ctx.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY).resources
    }.getOrNull() ?: run {
        val info = ctx.packageManager.getPackageArchiveInfo(modulePath, 0)?.applicationInfo ?: ApplicationInfo()
        info.sourceDir = modulePath
        info.publicSourceDir = modulePath
        val loaded = ctx.packageManager.getResourcesForApplication(info)
        @Suppress("DEPRECATION")
        Resources(loaded.assets, ctx.resources.displayMetrics, ctx.resources.configuration)
    }).also { module = it }

    fun t(ctx: Context, @StringRes id: Int, vararg args: Any?): String = runCatching {
        val res = resources(ctx)
        if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
    }.onFailure { Logger.printInfo { "WhatsApp: module string unavailable: $it" } }.getOrDefault("")

    fun toast(ctx: Context, text: String) {
        if (text.isEmpty()) return
        main.post { runCatching { Toast.makeText(ctx.applicationContext ?: ctx, text, Toast.LENGTH_SHORT).show() } }
    }
}
