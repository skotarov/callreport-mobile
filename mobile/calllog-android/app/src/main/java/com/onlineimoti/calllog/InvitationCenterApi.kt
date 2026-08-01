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
    ).map(::parseSession).mapCatching { acceptedSession ->
        val appContext = context.applicationContext
        val currentToken = ConfigStore.load(appContext).accessToken.trim()
        check(currentToken.isNotBlank()) { "Липсва активен профилен токън." }
        check(acceptedSession.accessToken.trim() == currentToken) {
            "Сървърът не запази текущата профилна сесия."
        }

        CompanyAccountApi.applySession(appContext, acceptedSession)
        val verified = CompanyAccountApi.refreshProfile(appContext).getOrThrow()
        val activeSession = verified.copy(
            accessToken = currentToken,
            organizationName = acceptedSession.organizationName.ifBlank { verified.organizationName },
            organizationId = acceptedSession.organizationId.ifBlank { verified.organizationId },
        )
        CompanyAccountApi.applySession(appContext, activeSession)
        activeSession
    }

    private fun request(context: Context, payload: JSONObject): Result<JSONObject> = runCatching {
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
