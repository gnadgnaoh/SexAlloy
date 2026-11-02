package io.github.nexalloy.revanced.whatsapp.core

import android.app.Activity
import app.morphe.extension.shared.Logger
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.Executors

internal object ReadReceipts {
    private const val MAX_HELD = 1000

    @Volatile
    var hideChats = false

    @Volatile
    var hideStatus = false

    @Volatile
    var currentChat: String? = null

    private val bypass = ThreadLocal<Boolean>()
    private val held = LinkedHashMap<String, ArrayDeque<() -> Any?>>()
    private val aliases = HashMap<String, MutableSet<String>>()
    private var count = 0
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "NexAlloy-WA-Read").apply { isDaemon = true } }

    private lateinit var jobRun: Method
    private lateinit var jobJid: Field
    private lateinit var inline: Method

    fun bind(jobRun: Method, inline: Method) {
        this.jobRun = jobRun.apply { isAccessible = true }
        jobJid = jobRun.declaringClass.getDeclaredField("jid").apply { isAccessible = true }
        this.inline = inline.apply { isAccessible = true }
    }

    private fun decide(chat: String?, send: () -> Any?): Boolean {
        if (bypass.get() == true) return false
        chat ?: return false
        if (WaJid.isStatus(chat)) return hideStatus
        if (!hideChats || !WaJid.isChat(chat)) return false
        hold(chat, send)
        return true
    }

    fun interceptJob(job: Any): Boolean {
        val chat = jobJid.get(job) as? String
        return decide(chat) { jobRun.invoke(job) }
    }

    fun interceptInline(manager: Any, message: Any): Boolean {
        val chat = WaMessage.chat(message)
        return decide(chat) { inline.invoke(manager, message) }
    }

    private fun hold(chat: String, send: () -> Any?) = synchronized(held) {
        held.getOrPut(chat) { ArrayDeque() }.addLast(send)
        currentChat?.takeIf { it != chat }?.let { open ->
            aliases.getOrPut(open) { HashSet() }.add(chat)
            aliases.getOrPut(chat) { HashSet() }.add(open)
        }
        if (++count > MAX_HELD) {
            val first = held.entries.iterator().next()
            first.value.removeFirstOrNull()
            if (first.value.isEmpty()) held.remove(first.key)
            count--
        }
    }

    private fun take(chat: String): List<() -> Any?> = synchronized(held) {
        val keys = LinkedHashSet<String>().apply { add(chat); aliases[chat]?.let(::addAll) }
        val sends = ArrayList<() -> Any?>()
        for (key in keys) held.remove(key)?.let { sends.addAll(it) }
        count -= sends.size
        sends
    }

    fun flush(chat: String?): Int {
        if (chat == null || !::jobRun.isInitialized) return 0
        val sends = take(chat)
        if (sends.isEmpty()) return 0
        executor.execute {
            for (send in sends) {
                bypass.set(true)
                runCatching { send() }
                    .onFailure { Logger.printException({ "WhatsApp: send held read receipt" }, it) }
                bypass.remove()
            }
        }
        return sends.size
    }
}

internal val WhatsAppReadReceipts = patch(name = "<WhatsAppReadReceipts>") {
    dependsOn(WhatsAppMessages)
    val run = ::readReceiptJobRunFingerprint.method
    val inline = ::inlineReadReceiptFingerprint.method
    ReadReceipts.bind(run, inline)
    run.hookMethod {
        before { param -> if (ReadReceipts.interceptJob(param.thisObject)) param.result = null }
    }
    inline.hookMethod {
        before { param ->
            val message = param.args[0] ?: return@before
            if (ReadReceipts.interceptInline(param.thisObject, message)) param.result = false
        }
    }
    val conversation = classLoader.loadClass(CONVERSATION_ACTIVITY)
    conversation.getDeclaredMethod("onResume").hookMethod {
        after { param -> ReadReceipts.currentChat = (param.thisObject as Activity).intent?.getStringExtra("jid") }
    }
    conversation.getDeclaredMethod("onPause").hookMethod {
        after { ReadReceipts.currentChat = null }
    }
}
