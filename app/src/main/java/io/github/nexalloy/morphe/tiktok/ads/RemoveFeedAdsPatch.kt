package io.github.nexalloy.morphe.tiktok.ads

import app.morphe.extension.shared.Logger
import io.github.nexalloy.IHookCallback
import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.callMethodOrNull
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import org.luckypray.dexkit.wrap.DexMethod

internal const val TAG = "[TikTok ads]"

/*
 * Hook site keys of [AdsHookCache]. Each key holds the methods to hook for one hook point,
 * including "none for this build", so no lookup is repeated on a later launch.
 */
private const val KEY_FETCH_FEED_LIST = "fetchFeedList"
private const val KEY_INSERT_ITEM_LIST = "insertItemList"
private const val KEY_COLD_START_CACHE = "coldStartCache"
private const val KEY_VIDEO_GRID_FILTERS = "videoGridFilters"
private const val KEY_PROFILE_RESULT_CALLBACKS = "profileResultCallbacks"
private const val KEY_TALENT_AD_CALLBACKS = "talentProfileAds"
private const val KEY_TALENT_AD_EVENT = "talentProfileAdEvent"

/**
 * Installs every feed hook once; [RemoveFeedAds] and [HidePromotedMusicVideos] only switch the
 * predicates on. Hook points are independent: one that no longer resolves after a TikTok update
 * is logged and skipped, the patch only fails when the For You response hook itself is gone.
 *
 * Startup cost: every lookup goes through [AdsHookCache], which also remembers the hook points
 * that resolve to nothing on this build (split installs and region builds have several). DexKit
 * parses the whole TikTok APK on its first query of a launch, so one unremembered lookup is
 * enough to pay that parse on every cold start; with all of them on file the patch installs from
 * descriptors and the DexKit bridge is never opened.
 *
 * Split installs (Play Store / APKM bundles of the Asia build): NexAlloy's DexKit scans base.apk
 * only, so code living in feature splits (Following feed, detail pager) is resolved by its kept
 * class name at runtime first.
 */
internal val TikTokFeedFilterHooks = patch(name = "<TikTokFeedFilterHooks>") {
    AwemeAdFilter.init(classLoader)

    val cache = AdsHookCache(this)
    val installed = mutableListOf<String>()
    val skipped = mutableListOf<String>()

    fun optional(label: String, block: () -> Unit) {
        runCatching(block)
            .onSuccess { installed += label }
            .onFailure { skipped += "$label (${it.javaClass.simpleName}: ${it.message})" }
    }

    /**
     * Cheap guard for the two talent-ad hook points: without the model class nothing can read its
     * ad list, and both lookups behind it are dex-wide scans. Only evaluated while resolving.
     */
    val talentAdModelPresent by lazy(LazyThreadSafetyMode.NONE) {
        (TALENT_AD_RESULT_CLASS.findClassOrNull(classLoader) != null).also {
            if (!it) Logger.printInfo { "$TAG ProfileTalentShareAdResult absent on this build" }
        }
    }

    // region For You

    val feedApis = cache.resolve(KEY_FETCH_FEED_LIST) { ::feedApiFetchFeedListFingerprints.dexMethodList }
    check(feedApis.isNotEmpty()) { "IFeedApi.fetchFeedList implementation not found" }
    feedApis.forEach {
        it.hookMethod {
            after { param -> filterFeedItemList(param.result, "fetchFeedList") }
        }
    }
    installed += "fetchFeedList x${feedApis.size}"

    optional("insertItemList") {
        val filterPayload: IHookCallback = { param ->
            filterSingleListField(param.args.firstOrNull(), "insertItemList")
        }
        val methods = cache.resolve(KEY_INSERT_ITEM_LIST) {
            val method = runCatching { FeedInsertItemListFingerprint.dexMethod }.getOrNull()
                ?: runCatching { FeedInsertItemListByStructureFingerprint.dexMethod }.getOrNull()
            method?.let(::listOf).orEmpty()
        }
        check(methods.isNotEmpty()) { "BaseListFragmentPanel.insertItemList not found" }
        methods.forEach { it.hookMethod { before(filterPayload) } }
    }

    optional("coldStartCache") {
        val methods = cache.resolve(KEY_COLD_START_CACHE) {
            runCatching { ColdStartFeedCacheFingerprint.dexMethod }.getOrNull()?.let(::listOf).orEmpty()
        }
        check(methods.isNotEmpty()) { "cold start FeedItemList getter not found" }
        methods.forEach {
            it.hookMethod {
                after { param -> filterFeedItemList(param.result, "coldStartCache") }
            }
        }
    }

    // endregion

    // region Profile

    optional("videoGrids") {
        val filters = cache.resolve(KEY_VIDEO_GRID_FILTERS) { ::videoGridAdListFilterFingerprints.dexMethodList }
        if (filters.isNotEmpty()) {
            filters.forEach {
                it.hookMethod {
                    after { param ->
                        val result = param.result as? List<*> ?: return@after
                        val kept = AwemeAdFilter.filteredCopyOrNull(result) ?: return@after
                        logRemoved("videoGrid", result.size - kept.size)
                        param.result = kept
                    }
                }
            }
        } else {
            val callbacks = cache.resolve(KEY_PROFILE_RESULT_CALLBACKS) { ::profileResultCallbackFingerprints.dexMethodList }
            check(callbacks.isNotEmpty()) { "no grid list filter and no result callback found" }
            callbacks.forEach {
                it.hookMethod {
                    before { param ->
                        AwemeAdFilter.filteredCopyOrNull(param.args[0] as? List<*>)?.let { kept -> param.args[0] = kept }
                    }
                }
            }
            Logger.printInfo { "$TAG videoGrids: using ${callbacks.size} result-callback fallbacks" }
        }
    }

    optional("talentProfileAds") {
        val callbacks = cache.resolve(KEY_TALENT_AD_CALLBACKS) {
            if (talentAdModelPresent) ::talentProfileAdsCallbackFingerprints.dexMethodList else emptyList()
        }
        check(callbacks.isNotEmpty()) { "ProfileTalentShareAdResult reader not found" }
        callbacks.forEach {
            it.hookMethod {
                before { param -> filterTalentAdResult(param.args.firstOrNull()) }
            }
        }
    }

    optional("talentProfileAdEvent") {
        val filterEvent: IHookCallback = { param ->
            filterSingleListField(param.args.firstOrNull(), "talentProfileAdEvent")
        }
        val methods = cache.resolve(KEY_TALENT_AD_EVENT) {
            if (!talentAdModelPresent) {
                emptyList()
            } else {
                val byName = DETAIL_FRAGMENT_CLASS.findClassOrNull(classLoader)?.declaredMethods
                    ?.filter { it.name == "onTalentProfileAdEvent" && it.parameterCount == 1 }
                    .orEmpty()
                if (byName.isNotEmpty()) byName.map { DexMethod(it) }
                else ::talentProfileAdEventSubscriberFingerprints.dexMethodList
            }
        }
        check(methods.isNotEmpty()) { "onTalentProfileAdEvent not found" }
        methods.forEach { it.hookMethod { before(filterEvent) } }
    }

    // endregion

    optional("followingFeed") { hookFollowingFeed() }

    cache.markComplete()
    Logger.printInfo { "$TAG installed=[${installed.joinToString()}] skipped=[${skipped.joinToString("; ")}]" }
}

val RemoveFeedAds = patch(
    name = "Remove feed ads",
    description = "Removes sponsored videos from the For You, Following and profile feeds, " +
            "including ads inserted after the feed was loaded and the cold-start cache.",
) {
    AwemeAdFilter.hideAds = true
    dependsOn(TikTokFeedFilterHooks)
}

val HidePromotedMusicVideos = patch(
    name = "Hide promoted-music videos",
    description = "Also hides videos flagged by TikTok as using a paid promoted sound. " +
            "May hide some organic creator videos.",
    use = false,
) {
    AwemeAdFilter.hidePromotedMusic = true
    dependsOn(TikTokFeedFilterHooks)
}

// region helpers

private fun filterFeedItemList(feedItemList: Any?, source: String) {
    if (feedItemList == null || !AwemeAdFilter.enabled) return
    logRemoved(source, AwemeAdFilter.filterListField(feedItemList, "items"))
}

/**
 * Payload / event objects with exactly one List field. That list can be a singletonList or still
 * owned by the sender, so the object gets its own filtered copy.
 */
private fun filterSingleListField(owner: Any?, source: String) {
    if (owner == null || !AwemeAdFilter.enabled) return
    val field = AwemeAdFilter.singleListField(owner) ?: return
    val list = runCatching { field.get(owner) as? List<*> }.getOrNull() ?: return
    val kept = AwemeAdFilter.filteredCopyOrNull(list, AwemeAdFilter::awemeOf) ?: return
    runCatching { field.set(owner, kept) }.onSuccess { logRemoved(source, list.size - kept.size) }
}

/** ProfileTalentShareAdResult.profileAds: List<ProfileAdData(previousItemId, aweme)>. */
private fun filterTalentAdResult(result: Any?) {
    if (result == null || !AwemeAdFilter.enabled || result.javaClass.name != TALENT_AD_RESULT_CLASS) return
    logRemoved("talentProfileAds", AwemeAdFilter.filterListField(result, "profileAds", AwemeAdFilter::awemeOf))
}

private fun logRemoved(source: String, removed: Int) {
    if (removed > 0) Logger.printDebug { "$TAG $source: removed $removed item(s)" }
}

/**
 * FollowFeedList is a Gson model whose accessors keep their names in every build; in 46.9.1 no
 * class outside FollowFeedList reads its list field directly. The field name itself is not used:
 * it was `mItems` in the builds Morphe targeted and is `items` in 46.9.1.
 *
 * The post-processor walks getItems() by index ~40 times before handing the list to the adapter,
 * so an instance is only cleaned on its first read (API / cache layer) and never afterwards.
 *
 * Reflection only: no DexKit lookup, so this hook point costs nothing at startup.
 */
private fun PatchExecutor.hookFollowingFeed() {
    val followFeedList = classLoader.loadClass(FOLLOW_FEED_LIST_CLASS)

    followFeedList.getMethod("getItems").hookMethod {
        after { param ->
            val owner = param.thisObject ?: return@after
            val list = param.result as? List<*> ?: return@after
            if (!AwemeAdFilter.markFirstSeen(owner)) return@after
            val kept = AwemeAdFilter.filteredCopyOrNull(list, AwemeAdFilter::awemeOf) ?: return@after
            val removed = list.size - kept.size
            @Suppress("UNCHECKED_CAST")
            val edited = runCatching { (list as MutableList<Any?>).run { clear(); addAll(kept) } }.isSuccess
            if (!edited) {
                owner.callMethodOrNull("setItems", kept)
                param.result = kept
            }
            logRemoved("followingFeed", removed)
        }
    }

    // Lists assembled by TikTok itself (load-more merge, in-memory cache restore).
    followFeedList.getMethod("setItems", List::class.java).hookMethod {
        before { param ->
            AwemeAdFilter.filteredCopyOrNull(param.args[0] as? List<*>, AwemeAdFilter::awemeOf)
                ?.let { param.args[0] = it }
        }
    }

    // Reads the field directly and builds a new list on every call.
    runCatching { followFeedList.getMethod("getAwemeList") }.getOrNull()?.hookMethod {
        after { param ->
            AwemeAdFilter.filteredCopyOrNull(param.result as? List<*>)?.let { param.result = it }
        }
    }
}

// endregion
