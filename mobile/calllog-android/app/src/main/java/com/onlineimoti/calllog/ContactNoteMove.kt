package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal data class ContactNoteMoveUiState(
    val canMove: Boolean = false,
    val selectingTarget: Boolean = false,
    val moving: Boolean = false,
    val sourceCompanyId: String = "",
)

internal object ContactNoteMovePolicy {
    fun canStart(
        selectedCompanyId: String,
        value: ContactNoteScopeValue,
        currentText: String,
        companyCount: Int,
    ): Boolean = selectedCompanyId.isNotBlank() &&
        selectedCompanyId != ContactNoteTopicState.LOCAL_COMPANY_ID &&
        value.confirmedServer &&
        value.serverClientEventId.isNotBlank() &&
        currentText.isNotBlank() &&
        companyCount > 1

    fun canTarget(sourceCompanyId: String, targetCompanyId: String): Boolean =
        targetCompanyId.isNotBlank() &&
            targetCompanyId != ContactNoteTopicState.LOCAL_COMPANY_ID &&
            targetCompanyId != sourceCompanyId
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
)

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
        val config = ConfigStore.load(context.applicationContext)
        if (!CallReportRemoteAccess.isReady(config)) throw IOException("Няма връзка със сървъра.")
        val payload = JSONObject().apply {
            put("move_request_id", request.moveRequestId)
            put("note_kind", request.noteKind)
            put("source_company_id", request.sourceCompanyId)
            put("target_company_id", request.targetCompanyId)
            put("source_client_event_id", request.sourceClientEventId)
            if (request.targetClientEventId.isNotBlank()) put("target_client_event_id", request.targetClientEventId)
            put("phone", request.phone)
            put("source_note", request.sourceNote)
        }
        val connection = URL(config.baseUrl.trim().trimEnd('/') + PATH).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            val response = runCatching { JSONObject(body) }.getOrNull()
            if (code !in 200..299 || response?.optBoolean("ok", false) != true) {
                val error = response?.optJSONObject("error")?.optString("message") ?: response?.optString("error")
                throw IOException(error.orEmpty().ifBlank { "Бележката не можа да бъде преместена." })
            }
            return ContactNoteMoveResult(
                targetCompanyId = response.optString("target_company_id").trim(),
                targetCompanyName = response.optString("target_company_name").trim(),
                targetClientEventId = response.optString("target_client_event_id").trim(),
                note = response.optString("note"),
                mergedIntoExisting = response.optBoolean("merged_into_existing", false),
            )
        } finally {
            connection.disconnect()
        }
    }

    fun applyLocalResult(
        context: Context,
        draft: ContactNoteFormDraft,
        sourceCompanyId: String,
        result: ContactNoteMoveResult,
    ) {
        val appContext = context.applicationContext
        if (draft.isGeneralNote) {
            CallReportCompanyGeneralNoteStore.saveOrDelete(appContext, draft.phone, sourceCompanyId, "")
            CallReportCompanyGeneralNoteStore.saveOrDelete(appContext, draft.phone, result.targetCompanyId, result.note)
        }
        if (result.targetClientEventId.isNotBlank()) {
            ServerRecordIndex.markConfirmed(appContext, listOf(result.targetClientEventId))
        }
        HomeCrmCompanyMembershipStore.invalidate(appContext, draft.phone)
    }
}
