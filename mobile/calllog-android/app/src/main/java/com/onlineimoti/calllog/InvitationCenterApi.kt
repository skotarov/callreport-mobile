package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** API for automatic invitation inboxes and company pending invitations. */
internal object InvitationCenterApi {
    private const val PATH = "/relationship-manager/api/invitations.php"
    private const val HANDOFF_PREFS = "relationship_manager_invitation_handoff"
    private const val KEY_PENDING_ROTATION_ID = "pending_rotation_id"

    data class Invitation(
        val id: String,
        val organizationId: String,
        val organizationName: String,
        val targetChannel: String,
        val targetValue: String,
        val role: String,
        val createdAtMs: Long,
        val expiresAtMs: Long,
        val alreadyMember: Boolean,
        val currentRole: String,
    )

    private data class Acceptance(
        val session: CompanyAccountApi.Session,
        val rotationId: String,
    )

    fun listReceived(context: Context): Result<List<Invitation>> = request(
        context,
        JSONObject().put("action", "list_received"),
    ).map(::parseList)

    fun listSent(context: Context, companyId: String): Result<List<Invitation>> = request(
        context,
        JSONObject()
            .put("action", "list_sent")
            .put("company_id", companyId.trim()),
    ).map(::parseList)

    fun create(
        context: Context,
        companyId: String,
        email: String,
        phone: String,
        role: String = "member",
    ): Result<Invitation> = request(
        context,
        JSONObject()
            .put("action", "create")
            .put("company_id", companyId.trim())
            .put("email", email.trim())
            .put("phone", normalizePhone(phone))
            .put("role", role),
    ).map { response -> parseInvitation(response.optJSONObject("invitation")) }

    fun cancel(context: Context, companyId: String, invitationId: String): Result<Unit> = request(
        context,
        JSONObject()
            .put("action", "cancel")
            .put("company_id", companyId.trim())
            .put("invitation_id", invitationId.trim()),
    ).map { Unit }

    fun accept(context: Context, invitationId: String): Result<CompanyAccountApi.Session> = request(
        context,
        JSONObject()
            .put("action", "accept")
            .put("invitation_id", invitationId.trim())
            .put("device_name", android.os.Build.MODEL.take(120)),
    ).map { response ->
        val rotationId = response.optString("rotation_id").trim()
        require(rotationId.isNotBlank()) { "Сървърът не върна потвърждение за смяната на токена." }
        Acceptance(parseSession(response), rotationId)
    }.mapCatching { acceptance ->
        activateRotatedSession(context.applicationContext, acceptance)
    }

    private fun activateRotatedSession(
        context: Context,
        acceptance: Acceptance,
    ): CompanyAccountApi.Session {
        val previousConfig = ConfigStore.load(context)
        val previousSnapshot = CompanySessionStore.loadStored(context)
        val previousSession = previousSnapshot?.let { snapshot ->
            CompanyAccountApi.Session(
                accessToken = previousConfig.accessToken,
                userName = snapshot.userName,
                organizationName = snapshot.organizationName,
                organizationId = snapshot.organizationId,
                userEmail = snapshot.userEmail,
                userPhone = snapshot.userPhone,
                emailVerified = snapshot.emailVerified,
                phoneVerified = snapshot.phoneVerified,
            )
        }

        savePendingRotation(context, acceptance.rotationId)
        try {
            CompanyAccountApi.applySession(context, acceptance.session)
            val newToken = acceptance.session.accessToken.trim()
            check(ConfigStore.load(context).accessToken.trim() == newToken) {
                "Новият access token не беше записан в приложението."
            }
            check(CompanySessionStore.isCurrent(context, newToken)) {
                "Профилната сесия не беше свързана с новия access token."
            }

            val verified = CompanyAccountApi.refreshProfile(context).getOrThrow()
            val activeSession = verified.copy(
                organizationName = acceptance.session.organizationName.ifBlank { verified.organizationName },
                organizationId = acceptance.session.organizationId.ifBlank { verified.organizationId },
            )
            CompanyAccountApi.applySession(context, activeSession)

            val confirmed = requestRaw(
                context,
                JSONObject()
                    .put("action", "confirm_rotation")
                    .put("rotation_id", acceptance.rotationId),
            ).isSuccess
            if (confirmed) clearPendingRotation(context)
            return activeSession
        } catch (error: Throwable) {
            clearPendingRotation(context)
            if (previousSession != null && previousSession.accessToken.isNotBlank()) {
                runCatching { CompanyAccountApi.applySession(context, previousSession) }
                runCatching {
                    requestRaw(
                        context,
                        JSONObject()
                            .put("action", "abort_rotation")
                            .put("rotation_id", acceptance.rotationId),
                    ).getOrThrow()
                }
            }
            throw error
        }
    }

    private fun request(context: Context, payload: JSONObject): Result<JSONObject> {
        if (payload.optString("action") !in setOf("confirm_rotation", "abort_rotation")) {
            confirmPendingRotation(context.applicationContext)
        }
        return requestRaw(context, payload)
    }

    private fun confirmPendingRotation(context: Context) {
        val rotationId = pendingRotationId(context)
        if (rotationId.isBlank()) return
        val confirmed = requestRaw(
            context,
            JSONObject()
                .put("action", "confirm_rotation")
                .put("rotation_id", rotationId),
        ).isSuccess
        if (confirmed) clearPendingRotation(context)
    }

    private fun requestRaw(context: Context, payload: JSONObject): Result<JSONObject> = runCatching {
        val config = ConfigStore.load(context)
        require(config.baseUrl.isNotBlank()) { "Първо задай Server URL в Настройки." }
        require(config.accessToken.isNotBlank()) { "Първо влез в профила." }
        val connection = (URL(buildEndpoint(config.baseUrl, PATH, emptyMap())).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.accessToken}")
            setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
            setRequestProperty("X-Callreport-Token", config.accessToken)
        }
        try {
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty().ifBlank { "{}" })
            if (connection.responseCode !in 200..299 || !response.optBoolean("ok", false)) {
                val message = response.optJSONObject("error")?.optString("message").orEmpty()
                    .ifBlank { "Операцията с поканата не беше успешна." }
                throw IllegalStateException(message)
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun savePendingRotation(context: Context, rotationId: String) {
        val saved = context.getSharedPreferences(HANDOFF_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_ROTATION_ID, rotationId.trim())
            .commit()
        check(saved) { "Смяната на access token не можа да бъде запазена." }
    }

    private fun pendingRotationId(context: Context): String =
        context.getSharedPreferences(HANDOFF_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_ROTATION_ID, "")
            .orEmpty()
            .trim()

    private fun clearPendingRotation(context: Context) {
        context.getSharedPreferences(HANDOFF_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_ROTATION_ID)
            .apply()
    }

    private fun parseList(response: JSONObject): List<Invitation> {
        val items = response.optJSONArray("invitations") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                runCatching { parseInvitation(item) }.getOrNull()?.let(::add)
            }
        }
    }

    private fun parseInvitation(item: JSONObject?): Invitation {
        require(item != null) { "Сървърът не върна валидна покана." }
        val id = item.optString("id").trim()
        require(id.isNotBlank()) { "Поканата няма валиден идентификатор." }
        return Invitation(
            id = id,
            organizationId = item.optString("organization_id").trim(),
            organizationName = item.optString("organization_name").trim(),
            targetChannel = item.optString("target_channel").trim(),
            targetValue = item.optString("target_value").trim(),
            role = item.optString("role").trim(),
            createdAtMs = item.optLong("created_at_ms", 0L),
            expiresAtMs = item.optLong("expires_at_ms", 0L),
            alreadyMember = item.optBoolean("already_member", false),
            currentRole = item.optString("current_role").trim(),
        )
    }

    private fun parseSession(response: JSONObject): CompanyAccountApi.Session {
        val accessToken = response.optString("access_token").trim()
        require(accessToken.isNotBlank()) { "Сървърът не върна валиден access token." }
        val user = response.optJSONObject("user")
        val organization = response.optJSONObject("joined_organization")
            ?: response.optJSONObject("organization")
        return CompanyAccountApi.Session(
            accessToken = accessToken,
            userName = user?.optString("display_name").orEmpty().trim(),
            organizationName = organization?.optString("name").orEmpty().trim(),
            organizationId = organization?.optString("id").orEmpty().trim(),
            userEmail = user?.optString("email").orEmpty().trim(),
            userPhone = user?.optString("phone").orEmpty().trim(),
            emailVerified = user?.optBoolean("email_verified", false) ?: false,
            phoneVerified = user?.optBoolean("phone_verified", false) ?: false,
        )
    }

    private fun normalizePhone(value: String): String {
        if (value.isBlank()) return ""
        return PhoneNormalizer.normalize(value).ifBlank { value.trim() }
    }
}
