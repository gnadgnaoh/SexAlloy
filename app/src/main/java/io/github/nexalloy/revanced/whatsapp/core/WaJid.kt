package io.github.nexalloy.revanced.whatsapp.core

internal object WaJid {
    private const val STATUS = "status@broadcast"

    fun isStatus(raw: String?) = raw == STATUS
    fun isGroup(raw: String?) = raw?.endsWith("@g.us") == true
    fun isNewsletter(raw: String?) = raw?.endsWith("@newsletter") == true
    fun isUser(raw: String?) = raw != null && (raw.endsWith("@s.whatsapp.net") || raw.endsWith("@lid"))
    fun isChat(raw: String?) = isUser(raw) || isGroup(raw)
}
