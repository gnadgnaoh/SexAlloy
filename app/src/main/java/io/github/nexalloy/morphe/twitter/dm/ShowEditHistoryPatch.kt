package io.github.nexalloy.morphe.twitter.dm

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.StyleSpan
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.whatsapp.core.WaUi
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier

private const val MARKER = "nexalloy-edit-history"
private const val MAIN_ACTIVITY = "com.x.android.main.MainActivity"
private const val TITLE = "edit_history_title"
private const val TEXT_GETTER = "getTextContent"

private fun millis(instant: Any?): Long =
    instant?.let { runCatching { java.time.Instant.parse(it.toString()).toEpochMilli() }.getOrNull() } ?: 0L

private fun showHistory(ctx: Context, title: String, versions: List<Pair<String, Long>>) {
    val body = SpannableStringBuilder()
    versions.forEachIndexed { i, (text, at) ->
        if (i > 0) body.append("\n\n")
        val head = if (at > 0) {
            DateUtils.formatDateTime(ctx, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL)
        } else {
            WaUi.t(ctx, R.string.wa_edit_original)
        }
        val start = body.length
        body.append(head)
        body.setSpan(StyleSpan(Typeface.BOLD), start, body.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        body.append('\n').append(text)
    }
    runCatching {
        AlertDialog.Builder(ctx).setTitle(title).setMessage(body).setPositiveButton(android.R.string.ok, null).show()
    }
}

val ShowEditHistory = patch(
    name = "Show edit history",
    description = "Long-press an edited message and tap \"Edit history\" to see the original text and every edit.",
) {
    val messageField = ::longPressMessageFingerprint.field.apply { isAccessible = true }
    val messageType = messageField.type
    val replyType = ::replyActionClassFingerprint.clazz
    val replyCtor = replyType.declaredConstructors.single { it.parameterTypes.size == 2 }.apply { isAccessible = true }
    val replyMessageType = replyCtor.parameterTypes[0]
    val markerType = replyCtor.parameterTypes[1]
    val marker = markerType.getDeclaredConstructor(String::class.java).apply { isAccessible = true }.newInstance(MARKER)
    val replyMarker = replyType.declaredFields.single { it.type == markerType && !Modifier.isStatic(it.modifiers) }.apply { isAccessible = true }
    val replyMessage = replyType.declaredFields.single { it.type == replyMessageType && !Modifier.isStatic(it.modifiers) }.apply { isAccessible = true }
    val editCtor = ::editActionClassFingerprint.clazz.declaredConstructors.single { it.parameterTypes.size == 1 }.apply { isAccessible = true }
    val metaType = ::editItemClassFingerprint.clazz
    val metaText = ::editItemTextFingerprint.field.apply { isAccessible = true }
    val metaTime = ::editItemTimeFingerprint.field.apply { isAccessible = true }
    val textGetter = messageType.getMethod(TEXT_GETTER)
    val listGetters = messageType.methods.filter { it.parameterCount == 0 && it.returnType == List::class.java }
    val icon = ::actionIconFingerprint.method
    val app = appContext
    val titleId by lazy { app.resources.getIdentifier(TITLE, "string", app.packageName) }
    var historyGetter: Method? = null
    var activity: WeakReference<Activity>? = null

    fun history(message: Any): List<*>? {
        historyGetter?.let { return (it.invoke(message) as? List<*>)?.takeIf { list -> list.isNotEmpty() } }
        for (getter in listGetters) {
            val list = runCatching { getter.invoke(message) as? List<*> }.getOrNull() ?: continue
            if (list.isNotEmpty() && metaType.isInstance(list[0])) {
                historyGetter = getter
                return list
            }
        }
        return null
    }

    fun isMarker(action: Any?) = action != null && replyType.isInstance(action) && replyMarker.get(action) == marker

    classLoader.loadClass(MAIN_ACTIVITY).getDeclaredMethod("onCreate", Bundle::class.java).hookMethod {
        after { param -> activity = WeakReference(param.thisObject as Activity) }
    }

    ::longPressActionsFingerprint.hookMethod {
        after { param ->
            val list = param.result as? List<*> ?: return@after
            val message = messageField.get(param.thisObject) ?: return@after
            if (!replyMessageType.isInstance(message) || history(message) == null) return@after
            val item = replyCtor.newInstance(message, marker)
            param.result = ArrayList<Any?>(list).apply { add(minOf(1, size), item) }
        }
    }

    ::actionLabelFingerprint.hookMethod {
        before { param -> if (isMarker(param.args[0]) && titleId != 0) param.result = titleId }
    }

    ::actionIconFingerprint.hookMethod {
        before { param ->
            if (!isMarker(param.args[0])) return@before
            param.result = icon.invoke(null, editCtor.newInstance(replyMessage.get(param.args[0])))
        }
    }

    ::actionHandlerFingerprint.hookMethod {
        before { param ->
            val action = param.args[0]
            if (!isMarker(action)) return@before
            param.result = null
            val message = replyMessage.get(action) ?: return@before
            val entries = history(message) ?: return@before
            val current = textGetter.invoke(message) as? String ?: ""
            val texts = entries.map { metaText.get(it) as? String ?: "" }
            val times = entries.map { millis(metaTime.get(it)) }
            val versions = buildList {
                add(texts[0] to 0L)
                for (i in entries.indices) add((texts.getOrNull(i + 1) ?: current) to times[i])
            }
            val ctx = activity?.get() ?: return@before
            val title = if (titleId != 0) ctx.getString(titleId) else WaUi.t(ctx, R.string.wa_edit_history)
            showHistory(ctx, title, versions)
        }
    }
}
