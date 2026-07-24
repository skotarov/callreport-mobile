package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject

internal object LocalCallNotesStore {
    fun latestNoteForPhone(context: Context, phoneNumber: String): String {
        val phoneKey = PhoneNormalizer.key(phoneNumber)
        if (phoneKey.isBlank() || !LocalNotesWorkspace.canUseConfiguredFolder(context)) return ""
        val line = LocalNotesIo.readLastNonBlankLine(
            context,
            LocalNotesIo.callLogRef(context, phoneKey, createDirs = false),
        )
        return if (line.isBlank()) {
            ""
        } else {
            runCatching { JSONObject(line).optString("note") }.getOrDefault("").trim()
        }
    }

    fun noteForCall(
        context: Context,
        phoneNumber: String,
        callAt: Long,
        direction: String = "",
    ): String {
        val phoneKey = PhoneNormalizer.key(phoneNumber)
        if (phoneKey.isBlank() || callAt <= 0L ||
            !LocalNotesWorkspace.canUseConfiguredFolder(context)
        ) return ""
        val ref = LocalNotesIo.callLogRef(context, phoneKey, createDirs = false) ?: return ""
        if (!LocalNotesIo.exists(ref)) return ""
        return runCatching {
            LocalNotesIo.readLines(context, ref).asReversed().firstNotNullOfOrNull { line ->
                val json = runCatching { JSONObject(line) }.getOrNull()
                    ?: return@firstNotNullOfOrNull null
                if (!LocalNotesIo.sameCall(json, callAt, direction)) {
                    return@firstNotNullOfOrNull null
                }
                json.optString("note").trim().takeIf { it.isNotBlank() }
            }.orEmpty()
        }.getOrDefault("")
    }

    fun companyIdForCall(
        context: Context,
        phoneNumber: String,
        callAt: Long,
        direction: String = "",
    ): String {
        val phoneKey = PhoneNormalizer.key(phoneNumber)
        if (phoneKey.isBlank() || callAt <= 0L ||
            !LocalNotesWorkspace.canUseConfiguredFolder(context)
        ) return ""
        val ref = LocalNotesIo.callLogRef(context, phoneKey, createDirs = false) ?: return ""
        if (!LocalNotesIo.exists(ref)) return ""
        return runCatching {
            LocalNotesIo.readLines(context, ref).asReversed().firstNotNullOfOrNull { line ->
                val json = runCatching { JSONObject(line) }.getOrNull()
                    ?: return@firstNotNullOfOrNull null
                if (!LocalNotesIo.sameCall(json, callAt, direction)) {
                    return@firstNotNullOfOrNull null
                }
                json.optString("company_id").trim().takeIf { it.isNotBlank() }
            }.orEmpty()
        }.getOrDefault("")
    }

    fun clientNoteIdForCall(
        phoneNumber: String,
        callAt: Long,
        direction: String = "",
    ): String {
        val phoneKey = PhoneNormalizer.key(phoneNumber)
        if (phoneKey.isBlank()) return ""
        val safeCallAt = if (callAt > 0L) callAt else 0L
        return "$phoneKey-$safeCallAt-${direction.ifBlank { "call" }}"
    }

    fun allCallNotes(context: Context, phoneNumber: String): List<ContactCallNote> {
        val phoneKey = PhoneNormalizer.key(phoneNumber)
        if (phoneKey.isBlank() || !LocalNotesWorkspace.canUseConfiguredFolder(context)) {
            return emptyList()
        }
        val ref = LocalNotesIo.callLogRef(context, phoneKey, createDirs = false)
            ?: return emptyList()
        if (!LocalNotesIo.exists(ref)) return emptyList()
        return runCatching {
            val notesByCall = linkedMapOf<String, ContactCallNote>()
            LocalNotesIo.readLines(context, ref).forEach { line ->
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                val note = json.optString("note").trim()
                if (note.isBlank()) return@forEach
                val callAt = json.optLong("call_at", 0L)
                if (json.optString("type").isNotBlank() &&
                    json.optString("type") != "call_note"
                ) return@forEach
                if (callAt <= 0L && json.optString("type") != "call_note") {
                    return@forEach
                }
                val direction = json.optString("direction")
                val clientNoteId = json.optString("id").ifBlank {
                    clientNoteIdForCall(phoneNumber, callAt, direction)
                }
                val savedAt = json.optLong("at", 0L)
                val candidate = ContactCallNote(
                    note = note,
                    callAt = callAt,
                    savedAt = savedAt,
                    direction = direction,
                    durationSeconds = json.optLong("duration", 0L),
                    clientNoteId = clientNoteId,
                    companyId = json.optString("company_id").trim(),
                )
                val key = when {
                    // LocalNotesIo.sameCall() already treats an exact call timestamp as
                    // authoritative even when one historical row has a blank direction.
                    // Reading must use the same identity rule or both rows become visible.
                    callAt > 0L -> "call:$callAt"
                    clientNoteId.isNotBlank() -> "id:$clientNoteId"
                    else -> "legacy:$savedAt:${direction.ifBlank { "call" }}:${normalizeNote(note)}"
                }
                notesByCall[key] = mergeDuplicate(notesByCall[key], candidate)
            }
            notesByCall.values.sortedByDescending { note ->
                note.callAt.takeIf { it > 0L } ?: note.savedAt
            }
        }.getOrDefault(emptyList())
    }

    private fun mergeDuplicate(current: ContactCallNote?, candidate: ContactCallNote): ContactCallNote {
        current ?: return candidate
        val newer = if (candidate.savedAt >= current.savedAt) candidate else current
        val older = if (newer === candidate) current else candidate
        return newer.copy(
            direction = newer.direction.ifBlank { older.direction },
            durationSeconds = newer.durationSeconds.takeIf { it > 0L } ?: older.durationSeconds,
            clientNoteId = newer.clientNoteId.takeIf {
                it.isNotBlank() && newer.direction.isNotBlank()
            } ?: older.clientNoteId.ifBlank { newer.clientNoteId },
            companyId = newer.companyId.ifBlank { older.companyId },
            serverClientEventId = newer.serverClientEventId.ifBlank { older.serverClientEventId },
        )
    }

    private fun normalizeNote(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase()

    fun appendCallNote(
        context: Context,
        phoneNumber: String,
        note: String,
        direction: String = "",
        callAt: Long = 0L,
        durationSeconds: Long = 0L,
        isUnknownContact: Boolean = false,
        companyId: String = "",
    ): Boolean {
        val phoneKey = PhoneNormalizer.key(phoneNumber)
        val trimmedNote = note.trim()
        if (phoneKey.isBlank() || trimmedNote.isBlank() ||
            !LocalNotesWorkspace.canUseConfiguredFolder(context)
        ) return false
        return runCatching {
            val now = System.currentTimeMillis()
            val record = JSONObject().apply {
                put("v", 2)
                put("type", "call_note")
                put(
                    "id",
                    clientNoteIdForCall(
                        phoneNumber,
                        callAt.takeIf { it > 0L } ?: now,
                        direction,
                    ),
                )
                put("at", now)
                put("phone", phoneNumber)
                put("normalized_phone", phoneKey)
                if (direction.isNotBlank()) put("direction", direction)
                if (callAt > 0L) put("call_at", callAt)
                if (durationSeconds > 0L) put("duration", durationSeconds)
                if (companyId.trim().isNotBlank()) put("company_id", companyId.trim())
                put("note", trimmedNote)
            }
            val ref = LocalNotesIo.callLogRef(context, phoneKey, createDirs = true)
                ?: return false
            if (callAt > 0L && LocalNotesIo.exists(ref)) {
                val keptLines = LocalNotesIo.readLines(context, ref).filterNot { line ->
                    val json = runCatching { JSONObject(line) }.getOrNull()
                        ?: return@filterNot false
                    LocalNotesIo.sameCall(json, callAt, direction)
                }
                LocalNotesIo.writeText(
                    context,
                    ref,
                    (keptLines + record.toString()).joinToString("\n") + "\n",
                )
            } else {
                LocalNotesIo.appendText(context, ref, record.toString() + "\n")
            }
            if (isUnknownContact) {
                LocalGeneralNotesStore.writeUnknownLatestProfile(
                    context,
                    phoneNumber,
                    phoneKey,
                    trimmedNote,
                    now,
                )
            }
            true
        }.getOrDefault(false)
    }

    fun deleteCallNote(
        context: Context,
        phoneNumber: String,
        callAt: Long,
        direction: String,
    ): Boolean {
        val phoneKey = PhoneNormalizer.key(phoneNumber)
        if (phoneKey.isBlank()) return false
        if (callAt <= 0L || !LocalNotesWorkspace.canUseConfiguredFolder(context)) return true
        return runCatching {
            val ref = LocalNotesIo.callLogRef(context, phoneKey, createDirs = false)
                ?: return@runCatching true
            if (!LocalNotesIo.exists(ref)) return@runCatching true
            val keptLines = LocalNotesIo.readLines(context, ref).filterNot { line ->
                val json = runCatching { JSONObject(line) }.getOrNull()
                    ?: return@filterNot false
                LocalNotesIo.sameCall(json, callAt, direction)
            }
            if (keptLines.isEmpty()) {
                LocalNotesIo.delete(ref)
            } else {
                LocalNotesIo.writeText(context, ref, keptLines.joinToString("\n") + "\n")
            }
            LocalGeneralNotesStore.refreshLatestProfileNote(
                context,
                phoneNumber,
                phoneKey,
            )
            true
        }.getOrDefault(false)
    }

    fun storedCallNotes(context: Context): List<LocalStoredCallNote> {
        if (!LocalNotesWorkspace.canUseConfiguredFolder(context)) return emptyList()
        return LocalNotesIo.callLogRefs(context)
            .flatMap { ref -> parseStoredCallNotes(context, ref) }
            .filter { it.note.isNotBlank() && it.phoneKey.isNotBlank() }
    }

    private fun parseStoredCallNotes(
        context: Context,
        ref: LocalNoteFileRef,
    ): List<LocalStoredCallNote> = runCatching {
        LocalNotesIo.readLines(context, ref).mapNotNull { line ->
            val json = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
            val type = json.optString("type")
            val callAt = json.optLong("call_at", 0L)
            if (type.isNotBlank() && type != "call_note") return@mapNotNull null
            if (type.isBlank() && callAt <= 0L) return@mapNotNull null
            val note = json.optString("note").trim()
            if (note.isBlank()) return@mapNotNull null
            val phone = json.optString("phone").ifBlank {
                json.optString("normalized_phone")
            }
            val phoneKey = PhoneNormalizer.key(
                json.optString("normalized_phone").ifBlank { phone },
            )
            if (phoneKey.isBlank()) return@mapNotNull null
            LocalStoredCallNote(
                phone = phone.ifBlank { phoneKey },
                phoneKey = phoneKey,
                note = note,
                noteAt = json.optLong("at", 0L),
                callAt = callAt,
                direction = json.optString("direction"),
                durationSeconds = json.optLong("duration", 0L),
            )
        }
    }.getOrDefault(emptyList())
}
