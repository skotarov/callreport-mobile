package com.onlineimoti.calllog

/** Keeps phone-addressed chats separate from apps that can only receive a name search. */
internal object ChatAppOpenPolicy {
    fun usesPhone(app: ChatApp): Boolean = when (app) {
        ChatApp.VIBER,
        ChatApp.WHATSAPP,
        ChatApp.TELEGRAM,
        ChatApp.MESSAGES -> true
        else -> false
    }

    fun searchQuery(app: ChatApp, contactName: String): String? {
        if (usesPhone(app)) return null
        return contactName.trim().takeIf { it.isNotBlank() }
    }
}
