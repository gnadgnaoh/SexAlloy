package io.github.nexalloy.revanced.whatsapp.message

import android.app.AlertDialog
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.StyleSpan
import android.widget.TextView
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.whatsapp.core.ConversationRows
import io.github.nexalloy.revanced.whatsapp.core.WaMessage
import io.github.nexalloy.revanced.whatsapp.core.WaStore
import io.github.nexalloy.revanced.whatsapp.core.WaMessageStore
import io.github.nexalloy.revanced.whatsapp.core.WaUi
import io.github.nexalloy.revanced.whatsapp.core.WhatsAppConversationRows
import io.github.nexalloy.revanced.whatsapp.core.WhatsAppMessageStore
import io.github.nexalloy.revanced.whatsapp.core.messageKeyClassFingerprint
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

private const val SECONDS_LIMIT = 100_000_000_000L

private class PendingEdit(val id: String, val editId: String?, val text: String?, val at: Long) {
    var original: String? = null
}

private fun millis(at: Long) = if (at in 1 until SECONDS_LIMIT) at * 1000 else at

private fun showHistory(ctx: Context, entries: List<Pair<String, Long>>) {
    val body = SpannableStringBuilder()
    entries.forEachIndexed { i, (text, at) ->
        if (i > 0) body.append("\n\n")
        val head = if (at > 0) {
            DateUtils.formatDateTime(ctx, millis(at), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL)
        } else {
            WaUi.t(ctx, R.string.wa_edit_original)
        }
        val start = body.length
        body.append(head)
        body.setSpan(StyleSpan(Typeface.BOLD), start, body.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        body.append('\n').append(text)
    }
    runCatching {
        AlertDialog.Builder(ctx)
            .setTitle(WaUi.t(ctx, R.string.wa_edit_history))
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}

val ShowEditHistory = patch(
    name = "Show edit history",
    description = "Tap the time of an edited message (underlined) to see the original text and every edit. Only edits made after you turn this on.",
) {
    dependsOn(WhatsAppConversationRows, WhatsAppMessageStore)
    WaStore.init(appContext)
    val handler = ::editHandlerFingerprint.method
    val getInfo = ::editInfoGetterFingerprint.method.apply { isAccessible = true }
    val editedAt = ::editedAtFieldFingerprint.field.apply { isAccessible = true }
    val keyType = ::messageKeyClassFingerprint.clazz
    val infoKey = getInfo.returnType.declaredFields
        .first { it.type == keyType && !Modifier.isStatic(it.modifiers) }.apply { isAccessible = true }
    val messageIndex = handler.parameterTypes.indexOfFirst { WaMessage.type.isAssignableFrom(it) }
    val pending = ThreadLocal<PendingEdit>()
    val resultField = handler.returnType.declaredFields
        .single { !Modifier.isStatic(it.modifiers) && WaMessage.type.isAssignableFrom(it.type) }.apply { isAccessible = true }
    fun resultMessage(result: Any): Any? = runCatching { resultField.get(result) }.getOrNull()

    handler.hookMethod {
        before { param ->
            pending.remove()
            val edited = param.args.getOrNull(messageIndex) ?: return@before
            val info = getInfo.invoke(null, edited) ?: return@before
            val key = infoKey.get(info) ?: return@before
            val id = WaMessage.idOfKey(key) ?: return@before
            val text = WaMessage.text(edited)
            pending.set(PendingEdit(id, WaMessage.id(edited), text, editedAt.getLong(info)).apply { original = WaMessage.text(WaMessageStore.message(key) ?: return@apply) })
        }
        after { param ->
            val edit = pending.get() ?: return@after
            pending.remove()
            val result = param.result
            if (param.hasThrowable() || result == null) return@after
            val stored = resultMessage(result)
            val text = edit.text ?: stored?.let(WaMessage::text)
            val original = edit.original
            val ids = listOfNotNull(edit.id, stored?.let(WaMessage::id), edit.editId).distinct()
            text ?: return@after
            for (id in ids) {
                if (original != null && original != text && WaStore.edits(id).isEmpty()) WaStore.addEdit(id, original, 0L)
                WaStore.addEdit(id, text, edit.at)
            }
        }
    }

    val ours = Collections.synchronizedMap(WeakHashMap<TextView, Boolean>())
    ConversationRows.binders.add { row, message ->
        val entries = WaMessage.id(message)?.let(WaStore::edits).orEmpty()
        val date = ConversationRows.find(row, "date") as? TextView
        if (date == null) {
            return@add
        }
        if (entries.isEmpty()) {
            if (ours.remove(date) == true) {
                date.paintFlags = date.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
                date.setOnClickListener(null)
                date.isClickable = false
            }
            return@add
        }
        ours[date] = true
        date.paintFlags = date.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        date.setOnClickListener { view -> showHistory(view.context, entries) }
    }
}
