package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

internal data class ContactNoteMoveUiState(
    val canMove: Boolean = false,
    val selectingTarget: Boolean = false,
    val moving: Boolean = false,
    val sourceCompanyId: String = "",
)

internal data class ContactNoteLocalCoordinates(
    val isGeneralNote: Boolean,
    val callAtMs: Long,
    val durationSeconds: Long,
    val direction: String,
)

internal object ContactNoteMovePolicy {
    private const val LOCAL_EVENT_PREFIX = "__relationship_manager_local_note__"

    fun isLocalCompanyId(companyId: String): Boolean =
        companyId == ContactNoteTopicState.LOCAL_COMPANY_ID

    fun localEventId(draft: ContactNoteFormDraft): String = listOf(
        LOCAL_EVENT_PREFIX,
        if (draft.isGeneralNote) "main" else "call",
        draft.callAt.coerceAtLeast(0L).toString(),
        draft.durationSeconds.coerceAtLeast(0L).toString(),
        draft.direction.replace('|', ' ').trim(),
    ).joinToString("|")

    fun isLocalEventId(clientEventId: String): Boolean =
        clientEventId.startsWith("$LOCAL_EVENT_PREFIX|")

    fun localCoordinates(clientEventId: String): ContactNoteLocalCoordinates? {
        if (!isLocalEventId(clientEventId)) return null
        val parts = clientEventId.split('|', limit = 5)
        if (parts.size < 5) return null
        val isGeneral = parts[1] == "main"
        return ContactNoteLocalCoordinates(
            isGeneralNote = isGeneral,
            callAtMs = parts[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            durationSeconds = parts[3].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            direction = parts[4].trim(),
        )
    }

    fun canStart(
        selectedCompanyId: String,
        value: ContactNoteScopeValue,
        currentText: String,
        companyCount: Int,
    ): Boolean {
        if (selectedCompanyId.isBlank() || currentText.isBlank() || companyCount < 1) return false
        if (isLocalCompanyId(selectedCompanyId)) return isLocalEventId(value.serverClientEventId)
        return value.confirmedServer && value.serverClientEventId.isNotBlank()
    }

    fun canTarget(sourceCompanyId: String, targetCompanyId: String): Boolean =
        targetCompanyId.isNotBlank() && targetCompanyId != sourceCompanyId
}

internal data class ContactNoteMoveRequest(
    val moveRequestId: String = UUID.randomUUID().toString(),
    val noteKind: String,
    val sourceCompanyId: String,
    val targetCompanyId: String,
    val sourceClientEventId: String,
    val targetClientEventId: String,
    val phone: String,
    val sourceNote: String,
) {
    val sourceIsLocal: Boolean get() = ContactNoteMovePolicy.isLocalCompanyId(sourceCompanyId)
    val targetIsLocal: Boolean get() = ContactNoteMovePolicy.isLocalCompanyId(targetCompanyId)
    val sourceStorage: String get() = if (sourceIsLocal) "local" else "company"
    val targetStorage: String get() = if (targetIsLocal) "local" else "company"
}

internal data class ContactNoteMoveResult(
    val targetCompanyId: String,
    val targetCompanyName: String,
    val targetClientEventId: String,
    val note: String,
    val mergedIntoExisting: Boolean,
)

internal object ContactNoteMoveClient {
    private const val PATH = "/relationship-manager/api/note_move.php"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    fun move(context: Context, request: ContactNoteMoveRequest): ContactNoteMoveResult {
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        if (!CallReportRemoteAccess.isReady(config)) throw IOException("Няма връзка със сървъра.")

        val preparedLocalText = if (request.targetIsLocal) {
            prepareLocalTarget(appContext, request)
        } else {
            ""
        }
        val sourceLocalId = if (request.sourceIsLocal) {
            ContactNoteLocalMoveReceiptStore.sourceLocalId(appContext, request)
        } else {
            ""
        }

        val result = try {
            requestServerMove(appContext, config.baseUrl, config.accessToken, request, sourceLocalId)
        } catch (error: Throwable) {
            if (request.targetIsLocal) {
                throw IOException(
                    "Локалното копие е запазено, но сървърната бележка още не е изтрита. Опитай преместването отново.",
                    error,
                )
            }
            throw error
        }

        if (request.sourceIsLocal) {
            val deleted = writeLocal(appContext, request, "")
            if (!deleted) {
                throw IOException(
                    "Бележката е записана на сървъра, но локалното копие не се изтри. Опитай преместването отново.",
                )
            }
            ContactNoteLocalMoveReceiptStore.clearSourceLocalId(appContext, request)
        }
        if (request.targetIsLocal) {
            ContactNoteLocalMoveReceiptStore.clearAppliedServerSource(appContext, request)
        }

        return if (request.targetIsLocal) {
            result.copy(
                targetCompanyId = ContactNoteTopicState.LOCAL_COMPANY_ID,
                targetCompanyName = "Локално",
                targetClientEventId = "",
                note = preparedLocalText,
            )
        } else {
            result
        }
    }

    private fun requestServerMove(
        context: Context,
        baseUrl: String,
        accessToken: String,
        request: ContactNoteMoveRequest,
        sourceLocalId: String,
    ): ContactNoteMoveResult {
        val coordinates = localCoordinates(request)
        val payload = JSONObject().apply {
            put("move_request_id", request.moveRequestId)
            put("note_kind", request.noteKind)
            put("source_storage", request.sourceStorage)
            put("target_storage", request.targetStorage)
            if (!request.sourceIsLocal) {
                put("source_company_id", request.sourceCompanyId)
                put("source_client_event_id", request.sourceClientEventId)
            } else {
                put("source_local_id", sourceLocalId)
            }
            if (!request.targetIsLocal) {
                put("target_company_id", request.targetCompanyId)
                if (request.targetClientEventId.isNotBlank() &&
                    !ContactNoteMovePolicy.isLocalEventId(request.targetClientEventId)) {
                    put("target_client_event_id", request.targetClientEventId)
                }
            }
            put("phone", request.phone)
            put("source_note", request.sourceNote)
            if (request.noteKind == "call") {
                put("occurred_at_ms", coordinates.callAtMs)
                put("direction", coordinates.direction)
                put("duration_seconds", coordinates.durationSeconds)
            }
        }
        val connection = URL(baseUrl.trim().trimEnd('/') + PATH).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Relationship-Manager-Token", accessToken)
            connection.setRequestProperty("X-Callreport-Token", accessToken)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            val response = runCatching { JSONObject(body) }.getOrNull()
            if (code !in 200..299 || response?.optBoolean("ok", false) != true) {
                val error = response?.optJSONObject("error")?.optString("message") ?: response?.optString("error")
                throw IOException(error.orEmpty().ifBlank { "Бележката не можа да бъде преместена." })
            }
            val targetStorage = response.optString("target_storage", request.targetStorage).trim()
            val targetIsLocal = targetStorage == "local" || request.targetIsLocal
            return ContactNoteMoveResult(
                targetCompanyId = if (targetIsLocal) {
                    ContactNoteTopicState.LOCAL_COMPANY_ID
                } else {
                    response.optString("target_company_id", request.targetCompanyId).trim()
                },
                targetCompanyName = if (targetIsLocal) {
                    "Локално"
                } else {
                    response.optString("target_company_name").trim()
                },
                targetClientEventId = if (targetIsLocal) "" else response.optString("target_client_event_id").trim(),
                note = response.optString("note"),
                mergedIntoExisting = response.optBoolean("merged_into_existing", false),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun prepareLocalTarget(context: Context, request: ContactNoteMoveRequest): String {
        val receiptExists = ContactNoteLocalMoveReceiptStore.hasAppliedServerSource(context, request)
        val draft = localDraft(request)
        val existing = ContactNoteScopeTextResolver.cachedValue(
            context,
            draft,
            ContactNoteTopicState.LOCAL_COMPANY_ID,
        ).text
        if (receiptExists) return existing

        val movedBlock = "Преместено от сървъра:\n${request.sourceNote.trim()}"
        val merged = when {
            existing.trim() == movedBlock -> existing.trim()
            existing.contains("\n\n$movedBlock") -> existing
            existing.isBlank() -> movedBlock
            else -> existing.trimEnd() + "\n\n" + movedBlock
        }
        if (merged.length > 4000) {
            throw IOException("Обединеният локален текст е по-дълъг от 4000 знака.")
        }
        if (!writeLocal(context, request, merged)) {
            throw IOException("Локалното копие не можа да бъде записано.")
        }
        ContactNoteLocalMoveReceiptStore.markAppliedServerSource(context, request)
        return merged
    }

    private fun writeLocal(context: Context, request: ContactNoteMoveRequest, text: String): Boolean {
        return ContactNoteFormWorkflow.save(
            context = context,
            draft = localDraft(request),
            noteText = text,
            topicCompanyId = ContactNoteTopicState.LOCAL_COMPANY_ID,
        ).saved
    }

    private fun localDraft(request: ContactNoteMoveRequest): ContactNoteFormDraft {
        val coordinates = localCoordinates(request)
        return ContactNoteFormDraft(
            phone = request.phone,
            title = request.phone,
            direction = coordinates.direction,
            callAt = coordinates.callAtMs,
            durationSeconds = coordinates.durationSeconds,
            actionIssuedAt = coordinates.callAtMs,
            isGeneralNote = request.noteKind == "main" || coordinates.isGeneralNote,
            serverClientEventId = "",
        )
    }

    private fun localCoordinates(request: ContactNoteMoveRequest): ContactNoteLocalCoordinates {
        val localId = when {
            request.sourceIsLocal -> request.sourceClientEventId
            request.targetIsLocal -> request.targetClientEventId
            else -> ""
        }
        val parsed = ContactNoteMovePolicy.localCoordinates(localId)
        if (parsed != null) return parsed
        if (request.noteKind == "main") return ContactNoteLocalCoordinates(true, 0L, 0L, "")
        throw IOException("Липсват данни за локалния разговор.")
    }

    fun applyLocalResult(
        context: Context,
        draft: ContactNoteFormDraft,
        sourceCompanyId: String,
        sourceClientEventId: String,
        result: ContactNoteMoveResult,
    ) {
        val appContext = context.applicationContext
        val sourceIsLocal = ContactNoteMovePolicy.isLocalCompanyId(sourceCompanyId)
        val targetIsLocal = ContactNoteMovePolicy.isLocalCompanyId(result.targetCompanyId)
        if (!sourceIsLocal && sourceClientEventId.isNotBlank()) {
            ServerRecordIndex.markPending(appContext, sourceClientEventId)
        }
        if (draft.isGeneralNote) {
            if (!sourceIsLocal) {
                CallReportCompanyGeneralNoteStore.saveOrDelete(appContext, draft.phone, sourceCompanyId, "")
            }
            if (!targetIsLocal) {
                CallReportCompanyGeneralNoteStore.saveOrDelete(
                    appContext,
                    draft.phone,
                    result.targetCompanyId,
                    result.note,
                )
            }
        } else if (!sourceIsLocal) {
            HomeNotesSnapshotCache.invalidateDeletedNote(
                context = appContext,
                phone = draft.phone,
                isGeneralNote = false,
                callAtMs = draft.callAt,
                direction = draft.direction,
                serverClientEventId = sourceClientEventId,
            )
        }
        if (!targetIsLocal && result.targetClientEventId.isNotBlank()) {
            ServerRecordIndex.markConfirmed(appContext, listOf(result.targetClientEventId))
        }
        HomeCrmCompanyMembershipStore.invalidate(appContext, draft.phone)
    }
}

private object ContactNoteLocalMoveReceiptStore {
    private const val PREFS = "relationship_manager_note_move_receipts"

    fun sourceLocalId(context: Context, request: ContactNoteMoveRequest): String {
        val key = "source_${fingerprint(request.sourceClientEventId + "|" + request.sourceNote)}"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(key, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(key, it).apply()
        }
    }

    fun clearSourceLocalId(context: Context, request: ContactNoteMoveRequest) {
        val key = "source_${fingerprint(request.sourceClientEventId + "|" + request.sourceNote)}"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).apply()
    }

    fun hasAppliedServerSource(context: Context, request: ContactNoteMoveRequest): Boolean {
        val key = appliedKey(request)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, false)
    }

    fun markAppliedServerSource(context: Context, request: ContactNoteMoveRequest) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(appliedKey(request), true)
            .apply()
    }

    fun clearAppliedServerSource(context: Context, request: ContactNoteMoveRequest) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(appliedKey(request))
            .apply()
    }

    private fun appliedKey(request: ContactNoteMoveRequest): String =
        "target_${fingerprint(request.sourceClientEventId + "|" + request.sourceNote)}"

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
