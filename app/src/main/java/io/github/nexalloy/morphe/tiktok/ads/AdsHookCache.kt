package io.github.nexalloy.morphe.tiktok.ads

import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import app.morphe.extension.shared.Logger
import io.github.nexalloy.BuildConfig
import io.github.nexalloy.PatchExecutor
import org.luckypray.dexkit.wrap.DexMethod

/**
 * Hook site store for the TikTok ad patches.
 *
 * Why this exists: DexKit only parses the (very large) TikTok APK on the first query of a launch,
 * so a launch that resolves everything from cache never opens the native bridge at all. The shared
 * NexAlloy cache stores successful lookups only:
 *
 *  - a single fingerprint that resolves to nothing is never written (`CacheFailurePolicy.NONE`);
 *  - a list query that returns nothing is written as an empty string, which the shared
 *    `SharedPrefCache.getStringList` reads back as "no entry" (`takeIf(String::isNotBlank)`).
 *
 * Both cases re-run on every cold start, and a single one of them re-parses the whole APK, which
 * is what made the ad patches slow to start while the other TikTok patches were not. The feed
 * hooks have several lookups that legitimately resolve to nothing depending on the build (split
 * installs, region builds, reordered profile view models), so they need a store that remembers
 * "there is nothing to hook here" just as firmly as a hit.
 *
 * Entries are descriptors ([DexMethod.serialize]), keyed per hook site, and are dropped whenever
 * the host app or the module changes. A descriptor is turned back into a member by reflection, so
 * a warm launch costs a few `SharedPreferences` reads instead of a dex scan.
 */
internal class AdsHookCache(executor: PatchExecutor) {

    private companion object {
        const val PREF_NAME = "nexalloy_tiktok_ads"
        const val KEY_ID = "__id"
        const val KEY_COMPLETE = "__complete"
        const val ENTRY_PREFIX = "hook:"
        const val SEPARATOR = "|"

        /** Bump whenever the hook sites below change, to drop entries of an older layout. */
        const val SCHEMA = 1
    }

    private val prefs: SharedPreferences? = runCatching {
        executor.appContext.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
    }.onFailure { Logger.printException({ "$TAG hook cache unavailable" }, it) }.getOrNull()

    /** Debug builds always re-resolve, like the shared cache does. */
    private val usable = prefs != null && !BuildConfig.DEBUG

    /** True when the previous launch resolved every hook site of this app build. */
    val warm: Boolean

    init {
        val id = buildId(executor)
        val store = prefs.takeIf { usable }
        warm = when {
            store == null -> false
            store.getString(KEY_ID, null) != id -> {
                // Another TikTok build (or module revision): the stored descriptors no longer apply.
                store.edit().clear().putString(KEY_ID, id).apply()
                false
            }

            else -> store.getBoolean(KEY_COMPLETE, false)
        }
        Logger.printInfo { "$TAG hook cache ${if (warm) "warm" else "cold"} (id=$id)" }
    }

    /**
     * Identity of the resolved hook sites: they only describe the installed TikTok build, so the
     * install time of the host (which also covers a newly installed split) and the module revision
     * are enough. `versionName` is kept for readable logs.
     */
    private fun buildId(executor: PatchExecutor): String {
        val info = runCatching {
            executor.appContext.packageManager
                .getPackageInfo(executor.appContext.packageName, 0)
        }.getOrNull()
        return "${info?.lastUpdateTime ?: 0}-${info?.versionName ?: "?"}-${BuildConfig.COMMIT_HASH}-$SCHEMA"
    }

    /** @return the stored hook sites, an empty list for a stored "nothing to hook", null when unknown. */
    fun cached(key: String): List<DexMethod>? {
        if (!usable) return null
        val raw = prefs?.getString(ENTRY_PREFIX + key, null) ?: return null
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEPARATOR).mapNotNull { runCatching { DexMethod(it) }.getOrNull() }
    }

    fun store(key: String, methods: List<DexMethod>) {
        if (!usable) return
        prefs?.edit()
            ?.putString(ENTRY_PREFIX + key, methods.joinToString(SEPARATOR) { it.serialize() })
            ?.apply()
    }

    /**
     * Runs [lookup] once per app build and remembers its outcome, including an empty one.
     *
     * [lookup] is the only place that may touch DexKit, so it must stay inside this call.
     */
    fun resolve(key: String, lookup: () -> List<DexMethod>): List<DexMethod> {
        cached(key)?.let { hit ->
            Logger.printDebug { "$TAG $key: ${hit.size} hook site(s) from cache" }
            return hit
        }
        val found = try {
            lookup()
        } catch (err: Throwable) {
            // A lookup that throws on this build throws on every launch: remember it as "nothing".
            Logger.printException({ "$TAG $key: lookup failed" }, err)
            emptyList()
        }
        Logger.printInfo { "$TAG $key: resolved ${found.size} hook site(s)" }
        store(key, found)
        return found
    }

    /** [resolve] for a hook site that is a single method. */
    fun resolveOne(key: String, lookup: () -> DexMethod?): DexMethod? =
        resolve(key) { lookup()?.let(::listOf).orEmpty() }.firstOrNull()

    /** Marks this app build as fully resolved, so the next launch can skip DexKit entirely. */
    fun markComplete() {
        if (!usable || warm) return
        prefs?.edit()?.putBoolean(KEY_COMPLETE, true)?.apply()
    }
}
