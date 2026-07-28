package com.onlineimoti.calllog

import android.content.Context

internal enum class ChatApp(
    val preferenceKey: String,
    val displayName: String,
    val packageNames: List<String>,
    val brandColor: Int,
) {
    VIBER("viber", "Viber", listOf("com.viber.voip"), 0xFF665CAC.toInt()),
    WHATSAPP("whatsapp", "WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b"), 0xFF128C7E.toInt()),
    TELEGRAM("telegram", "Telegram", listOf("org.telegram.messenger"), 0xFF229ED9.toInt()),
    MESSENGER("messenger", "Messenger", listOf("com.facebook.orca"), 0xFF006AFF.toInt()),
    SIGNAL("signal", "Signal", listOf("org.thoughtcrime.securesms"), 0xFF3A76F0.toInt()),
    INSTAGRAM("instagram", "Instagram", listOf("com.instagram.android"), 0xFFC13584.toInt()),
    MESSAGES("messages", "Messages", listOf("com.google.android.apps.messaging"), 0xFF1A73E8.toInt()),
    SNAPCHAT("snapchat", "Snapchat", listOf("com.snapchat.android"), 0xFFB59B00.toInt()),
    DISCORD("discord", "Discord", listOf("com.discord"), 0xFF5865F2.toInt()),
    WECHAT("wechat", "WeChat", listOf("com.tencent.mm"), 0xFF07C160.toInt()),
    LINE("line", "LINE", listOf("jp.naver.line.android"), 0xFF06C755.toInt()),
    KAKAOTALK("kakaotalk", "KakaoTalk", listOf("com.kakao.talk"), 0xFF6A5600.toInt()),
    QQ("qq", "QQ", listOf("com.tencent.mobileqq"), 0xFF1689E5.toInt()),
}

internal object ChatAppVisibilityStore {
    private const val PREFS = "relationship_manager_chat_apps"
    private const val KEY_ENABLED = "enabled_chat_apps_v1"

    fun enabledApps(context: Context): List<ChatApp> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enabledKeys = if (prefs.contains(KEY_ENABLED)) {
            prefs.getStringSet(KEY_ENABLED, emptySet()).orEmpty()
        } else {
            ChatApp.values().mapTo(linkedSetOf()) { it.preferenceKey }
        }
        return ChatApp.values().filter { it.preferenceKey in enabledKeys }
    }

    fun setEnabled(context: Context, app: ChatApp, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = enabledApps(context).mapTo(linkedSetOf()) { it.preferenceKey }
        if (enabled) updated += app.preferenceKey else updated -= app.preferenceKey
        prefs.edit().putStringSet(KEY_ENABLED, updated).apply()
    }
}
