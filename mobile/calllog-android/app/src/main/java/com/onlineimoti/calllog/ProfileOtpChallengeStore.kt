package com.onlineimoti.calllog

import android.content.Context

/** Keeps an active OTP challenge so reopening the modal does not send another message. */
internal object ProfileOtpChallengeStore {
    private const val PREFS_NAME = "profile_otp_pending_challenges"

    fun load(
        context: Context,
        identifier: String,
        channel: String,
        nowMs: Long = System.currentTimeMillis(),
    ): CompanyAccountApi.OtpChallenge? {
        val normalizedChannel = normalizeChannel(channel)
        val normalizedIdentifier = identifier.trim()
        if (normalizedChannel.isBlank() || normalizedIdentifier.isBlank()) return null

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = prefix(normalizedChannel)
        val storedIdentifier = prefs.getString("${prefix}identifier", "").orEmpty()
        val challengeId = prefs.getString("${prefix}challenge_id", "").orEmpty()
        val destinationHint = prefs.getString("${prefix}destination_hint", "").orEmpty()
        val expiresAtMs = prefs.getLong("${prefix}expires_at_ms", 0L)

        if (storedIdentifier != normalizedIdentifier || challengeId.isBlank() || expiresAtMs <= nowMs) {
            clear(context, normalizedChannel)
            return null
        }
        return CompanyAccountApi.OtpChallenge(
            id = challengeId,
            channel = normalizedChannel,
            destinationHint = destinationHint,
            expiresAtMs = expiresAtMs,
        )
    }

    fun save(
        context: Context,
        identifier: String,
        channel: String,
        challenge: CompanyAccountApi.OtpChallenge,
    ) {
        val normalizedChannel = normalizeChannel(channel)
        val normalizedIdentifier = identifier.trim()
        if (
            normalizedChannel.isBlank() ||
            normalizedIdentifier.isBlank() ||
            challenge.id.isBlank() ||
            challenge.expiresAtMs <= System.currentTimeMillis()
        ) return

        val prefix = prefix(normalizedChannel)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("${prefix}identifier", normalizedIdentifier)
            .putString("${prefix}challenge_id", challenge.id)
            .putString("${prefix}destination_hint", challenge.destinationHint)
            .putLong("${prefix}expires_at_ms", challenge.expiresAtMs)
            .apply()
    }

    fun clear(context: Context, channel: String) {
        val normalizedChannel = normalizeChannel(channel)
        if (normalizedChannel.isBlank()) return
        val prefix = prefix(normalizedChannel)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("${prefix}identifier")
            .remove("${prefix}challenge_id")
            .remove("${prefix}destination_hint")
            .remove("${prefix}expires_at_ms")
            .apply()
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun normalizeChannel(channel: String): String =
        channel.trim().lowercase().takeIf { it == "sms" || it == "email" }.orEmpty()

    private fun prefix(channel: String): String = "${channel}_"
}
