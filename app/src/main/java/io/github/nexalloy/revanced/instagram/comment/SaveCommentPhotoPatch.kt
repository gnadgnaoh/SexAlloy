package io.github.nexalloy.revanced.instagram.comment

import android.content.Context
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.instagram.download.I18n
import io.github.nexalloy.revanced.instagram.download.MediaDownloadCore
import io.github.nexalloy.revanced.instagram.download.MediaResolver
import io.github.nexalloy.revanced.instagram.download.MediaSaver
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

private const val FUNCTION0 = "kotlin.jvm.functions.Function0"
private const val SAVE_ACTION = "SaveMedia"
private const val GIF_NODES = 120
private const val GIPHY_HOST = "giphy.com"

val SaveCommentPhoto = patch(
    name = "Save comment photo",
    description = "Adds Save to the menu you get when you hold a comment that has its own photo or GIF. " +
        "It saves the largest size Instagram sent; a GIF keeps its own format.",
) {
    dependsOn(MediaDownloadCore)
    val renderer = ::commentMenuRendererFingerprint.method
    val holderField = ::commentMediaFieldFingerprint.field.apply { isAccessible = true }
    val holderType = holderField.type
    val mediaField = holderType.declaredFields.single { it.type == MediaResolver.mediaType }.apply { isAccessible = true }
    val giphyType = classLoader.loadClass("com.instagram.api.schemas.CommentGiphyMediaInfoIntf")
    val gifHolderField = ::commentGifHolderFieldFingerprint.field.apply { isAccessible = true }
    val gifField = gifHolderField.type.declaredFields.single { giphyType.isAssignableFrom(it.type) }.apply { isAccessible = true }
    val params = renderer.parameterTypes
    val fragmentType = classLoader.loadClass("androidx.fragment.app.Fragment")
    val selectedArg = params.indices.single { i -> params[i] == holderField.declaringClass }
    val rowsArg = params.indices.single { params[it] == List::class.java }
    val fragmentArg = params.indices.single { fragmentType.isAssignableFrom(params[it]) }
    val selectedType = params[selectedArg]
    val rowGifFields = selectedType.declaredFields.filter { f ->
        !Modifier.isStatic(f.modifiers) && !f.type.isPrimitive && !f.type.isPrimitiveLike() &&
            f.type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.let { own ->
                own.count { it.type == String::class.java } >= 2 && own.all { it.type.isPrimitive || it.type == String::class.java }
            }
    }.onEach { it.isAccessible = true }

    val save = ::menuActionSingletonsFingerprint.fieldList.firstNotNullOfOrNull { f ->
        runCatching { f.isAccessible = true; f.get(null) }.getOrNull()?.takeIf { it.toString() == SAVE_ACTION }
    } ?: error("no $SAVE_ACTION menu action")
    val res = appContext.resources
    val ids = generateSequence<Class<*>>(save.javaClass) { it.superclass }.flatMap { it.declaredFields.asSequence() }
        .filter { it.type == Int::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers) }
        .map { it.isAccessible = true; it.getInt(save) }.toList()
    fun isDrawable(id: Int) = runCatching { res.getResourceTypeName(id) == "drawable" }.getOrDefault(false) ||
        runCatching { android.util.TypedValue().also { res.getValue(id, it, true) }.string?.startsWith("res/") == true }.getOrDefault(false)
    val icon = ids.firstOrNull(::isDrawable) ?: error("no Save icon")
    val label = ids.firstOrNull { it != icon && it ushr 24 == 0x7f } ?: error("no Save label")

    val function0 = classLoader.loadClass(FUNCTION0)
    val unit = runCatching {
        val type = classLoader.loadClass("kotlin.Unit")
        type.declaredFields.single { Modifier.isStatic(it.modifiers) && it.type == type }.apply { isAccessible = true }.get(null)
    }.getOrNull()
    val userType = runCatching { classLoader.loadClass("com.instagram.user.model.User") }.getOrNull()

    fun target(ctx: Context, selected: Any): Target? {
        if (!selectedType.isInstance(selected)) return null
        rowGifFields.firstNotNullOfOrNull { f -> f.get(selected)?.let(::rowGifUrl) }?.let { return Target(it, true) }
        gifHolderField.get(selected)?.let { gifField.get(it) }?.let { gif -> gifUrl(gif)?.let { return Target(it, true) } }
        val holder = holderField.get(selected) ?: return null
        val media = mediaField.get(holder) ?: return null
        val item = MediaResolver.single(ctx, media) ?: return null
        return Target(item.url, false, item)
    }

    renderer.hookMethod {
        before { param ->
            runCatching {
                val rows = param.args[rowsArg] as? List<*> ?: return@runCatching
                val selected = param.args[selectedArg] ?: return@runCatching
                val ctx = param.args[fragmentArg]?.let { f -> f.javaClass.getMethod("requireContext").invoke(f) as? Context } ?: return@runCatching
                val found = target(ctx, selected) ?: return@runCatching
                val maker = RowMaker.of(rows, function0) ?: return@runCatching
                val author = userType?.let { MediaResolver.usernameOfUser(MediaResolver.fieldOfType(selected, it)) }
                val callback = Proxy.newProxyInstance(classLoader, arrayOf(function0)) { proxy, m, args ->
                    when (m.name) {
                        "invoke" -> { saveTarget(ctx, found, author); unit }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        "toString" -> "NexAlloySaveCommentPhoto"
                        else -> null
                    }
                }
                val row = maker.make(rows, icon, label, callback) ?: return@runCatching
                param.args[rowsArg] = ArrayList<Any?>(rows.size + 1).apply { addAll(rows); add(row) }
            }
        }
    }
}

private class Target(val url: String, val gif: Boolean, val item: io.github.nexalloy.revanced.instagram.download.MediaItem? = null)

private fun saveTarget(ctx: Context, target: Target, author: String?) {
    val item = target.item
    if (!target.gif && item != null) {
        MediaSaver.saveAll(
            ctx, listOf(MediaSaver.Job(item, author, "comment", null)),
            I18n.t(ctx, if (item.isVideo) R.string.ig_toast_downloading_video else R.string.ig_toast_downloading_photo),
            I18n.t(ctx, if (item.isVideo) R.string.ig_toast_video_saved else R.string.ig_toast_photo_saved),
        )
        return
    }
    MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_downloading_photo))
    MediaSaver.executor.submit {
        val name = "${author ?: "unknown"}_comment_${System.currentTimeMillis()}"
        runCatching { MediaSaver.saveAnimated(ctx, target.url, name, author) }
            .onSuccess { MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_photo_saved)) }
            .onFailure { MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_download_failed, it.message ?: it.javaClass.simpleName)) }
    }
}

private fun gifUrl(root: Any): String? {
    val urls = LinkedHashSet<String>()
    val seen = java.util.IdentityHashMap<Any, Boolean>()
    val queue = ArrayDeque<Pair<Any, Int>>().apply { add(root to 0) }
    var visited = 0
    fun offer(value: Any?, depth: Int) {
        when {
            value == null -> Unit
            value is String -> if (value.startsWith("http://") || value.startsWith("https://")) urls.add(value)
            value is Collection<*> -> if (depth < 4) value.forEach { offer(it, depth + 1) }
            value is Map<*, *> -> if (depth < 4) value.values.forEach { offer(it, depth + 1) }
            depth < 4 && !value.javaClass.isPrimitiveLike() -> queue.add(value to depth)
        }
    }
    while (queue.isNotEmpty() && visited++ < GIF_NODES) {
        val (obj, depth) = queue.removeFirst()
        if (seen.put(obj, true) != null) continue
        for (getter in modelGetters(obj.javaClass)) offer(runCatching { getter.invoke(obj) }.getOrNull(), depth + 1)
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                offer(runCatching { f.isAccessible = true; f.get(obj) }.getOrNull(), depth + 1)
            }
            c = c.superclass
        }
    }
    val usable = urls.filter { MediaSaver.isCdnMediaUrl(it) || isGiphyUrl(it) }.ifEmpty { urls.toList() }
    fun pick(ext: String) = usable.firstOrNull { it.substringBefore('?').lowercase().endsWith(ext) }
    val chosen = pick(".gif") ?: pick(".webp") ?: pick(".mp4") ?: usable.firstOrNull()
    return chosen
}

private val stringFieldCache = HashMap<Class<*>, List<java.lang.reflect.Field>>()

private fun rowGifUrl(holder: Any): String? {
    val fields = synchronized(stringFieldCache) {
        stringFieldCache.getOrPut(holder.javaClass) {
            holder.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                .onEach { it.isAccessible = true }
        }
    }
    val urls = fields.mapNotNull { it.get(holder) as? String }.filter { url ->
        val path = url.substringBefore('?').lowercase()
        isGiphyUrl(url) || url.startsWith("http") && (path.endsWith(".gif") || path.endsWith(".webp"))
    }
    fun pick(ext: String) = urls.firstOrNull { it.substringBefore('?').lowercase().endsWith(ext) }
    return pick(".gif") ?: pick(".webp") ?: pick(".mp4") ?: urls.firstOrNull()
}

private fun isGiphyUrl(url: String): Boolean =
    url.startsWith("http") && runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("").let { host ->
        host == GIPHY_HOST || host.endsWith(".$GIPHY_HOST")
    }

private val getterCache = HashMap<Class<*>, List<Method>>()

private fun modelGetters(cls: Class<*>): List<Method> = synchronized(getterCache) {
    getterCache.getOrPut(cls) {
        cls.interfaces.flatMap { it.methods.asList() }.filter { m ->
            m.parameterCount == 0 && !Modifier.isStatic(m.modifiers) && m.returnType != Void.TYPE &&
                (m.returnType == String::class.java || m.returnType.isInterface && m.returnType.name.startsWith("com.instagram."))
        }.distinctBy { it.name + it.returnType.name }
    }
}

private fun Class<*>.isPrimitiveLike() = isPrimitive || name.startsWith("java.") || name.startsWith("kotlin.")

private class RowMaker(private val base: Class<*>, private val types: Array<Class<*>>, private val fields: List<java.lang.reflect.Field>) {
    fun make(rows: List<*>, icon: Int, label: Int, callback: Any): Any? = runCatching {
        val own = rows.filterNotNull().filter { base.isInstance(it) }
        val styles = own.map { fields[0].get(it) }
        val style = styles.firstOrNull { it != null && plainStyle(it) }
            ?: styles.firstNotNullOfOrNull { it?.let(::plainCopy) }
            ?: styles.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val template = own.firstOrNull { fields[0].get(it) == style } ?: own.first()
        val row = allocate(template.javaClass)
        fields[0].set(row, style)
        fields[1].set(row, types[1].getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(icon))
        fields[2].set(row, types[2].getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(label))
        fields[3].set(row, callback)
        row
    }.onFailure { app.morphe.extension.shared.Logger.printException({ "Instagram: comment Save row" }, it) }.getOrNull()

    companion object {
        private val cache = HashMap<Class<*>, RowMaker?>()

        private fun plainStyle(style: Any): Boolean {
            var c: Class<*>? = style.javaClass
            val colors = ArrayList<java.lang.reflect.Field>()
            while (c != null && c != Any::class.java) {
                colors += c.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type == Integer::class.java }
                c = c.superclass
            }
            if (colors.isEmpty()) return false
            return colors.all { f -> runCatching { f.isAccessible = true; f.get(style) == null }.getOrDefault(false) }
        }

        private fun plainCopy(style: Any): Any? = runCatching {
            val copy = allocate(style.javaClass)
            var c: Class<*>? = style.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    f.isAccessible = true
                    f.set(copy, if (f.type == Integer::class.java) null else f.get(style))
                }
                c = c.superclass
            }
            copy.takeIf(::plainStyle)
        }.getOrNull()

        private val unsafe: Any by lazy {
            Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)!!
        }

        private fun allocate(type: Class<*>): Any = runCatching { type.getDeclaredConstructor().apply { isAccessible = true }.newInstance() }
            .getOrElse { unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)!! }

        fun of(rows: List<*>, function0: Class<*>): RowMaker? {
            val sample = rows.firstOrNull { it != null } ?: return null
            return synchronized(cache) {
                cache.getOrPut(sample.javaClass) {
                    generateSequence<Class<*>>(sample.javaClass) { it.superclass }.firstNotNullOfOrNull { c ->
                        val ctor = c.declaredConstructors.firstOrNull { k ->
                            k.parameterTypes.size == 4 && k.parameterTypes[3] == function0
                        } ?: return@firstNotNullOfOrNull null
                        val types = ctor.parameterTypes
                        val own = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
                        val fields = types.map { t -> own.singleOrNull { it.type == t } ?: return@firstNotNullOfOrNull null }
                        fields.forEach { it.isAccessible = true }
                        RowMaker(c, types, fields)
                    }
                }
            }
        }
    }
}
