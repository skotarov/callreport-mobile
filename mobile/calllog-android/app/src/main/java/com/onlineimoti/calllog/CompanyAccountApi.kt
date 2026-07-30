package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Mobile client for OTP profile authentication and separate company creation. */
internal object CompanyAccountApi {
    private const val AUTH_PATH = "/relationship-manager/api/auth.php"

    data class ProfileUser(
        val name: String = "",
        val email: String = "",
        val phone: String = "",
        val emailVerified: Boolean = false,
        val phoneVerified: Boolean = false,
    ) {
        val profileReady: Boolean get() = emailVerified || phoneVerified
    }

    data class Session(
        val accessToken: String,
        val userName: String,
        val organizationName: String,
        val organizationId: String,
        val userEmail: String = "",
        val userPhone: String = "",
        val emailVerified: Boolean = false,
        val phoneVerified: Boolean = false,
    ) {
        val profileReady: Boolean get() = emailVerified || phoneVerified
        fun user(): ProfileUser = ProfileUser(userName, userEmail, userPhone, emailVerified, phoneVerified)
    }

    data class OtpChallenge(
        val id: String,
        val channel: String,
        val destinationHint: String,
        val expiresAtMs: Long,
        val debugCode: String = "",
    )

    data class RegistrationVerification(
        val user: ProfileUser,
        val profileReady: Boolean,
        val session: Session?,
    )

    fun requestRegistrationOtp(
        context: Context,
        email: String,
        phone: String,
        displayName: String,
        channel: String,
    ): Result<OtpChallenge> = requestJson(
        context,
        JSONObject()
            .put("action", "request_profile_registration_otp")
            .put("email", email.trim())
            .put("phone", phone.trim())
            .put("display_name", displayName.trim())
            .put("channel", channel),
    ).map(::parseChallenge)

    fun verifyRegistrationOtp(
        context: Context,
        challengeId: String,
        code: String,
    ): Result<RegistrationVerification> = requestJson(
        context,
        JSONObject()
            .put("action", "verify_profile_registration_otp")
            .put("challenge_id", challengeId.trim())
            .put("code", code.trim())
            .put("device_name", android.os.Build.MODEL.take(120)),
    ).map { response ->
        val user = parseUser(response.optJSONObject("user"))
        val token = response.optString("access_token").trim()
        RegistrationVerification(
            user = user,
            profileReady = response.optBoolean("profile_ready", false),
            session = if (token.isBlank()) null else parseSession(response),
        )
    }

    fun requestLoginOtp(
        context: Context,
        identifier: String,
        channel: String,
    ): Result<OtpChallenge> = requestJson(
        context,
        JSONObject()
            .put("action", "request_login_otp")
            .put("identifier", identifier.trim())
            .put("channel", channel),
    ).map(::parseChallenge)

    fun verifyLoginOtp(
        context: Context,
        challengeId: String,
        code: String,
    ): Result<Session> = requestJson(
        context,
        JSONObject()
            .put("action", "verify_login_otp")
            .put("challenge_id", challengeId.trim())
            .put("code", code.trim())
            .put("device_name", android.os.Build.MODEL.take(120)),
    ).map(::parseSession)

    fun requestContactOtp(
        context: Context,
        channel: String,
        value: String,
    ): Result<OtpChallenge> = requestJson(
        context,
        JSONObject()
            .put("action", "request_contact_otp")
            .put("channel", channel)
            .put("value", value.trim()),
        authenticated = true,
    ).map(::parseChallenge)

    fun verifyContactOtp(
        context: Context,
        challengeId: String,
        code: String,
    ): Result<ProfileUser> = requestJson(
        context,
        JSONObject()
            .put("action", "verify_contact_otp")
            .put("challenge_id", challengeId.trim())
            .put("code", code.trim()),
        authenticated = true,
    ).map { parseUser(it.optJSONObject("user")) }

    fun refreshProfile(context: Context): Result<Session> = requestJson(
        context,
        JSONObject().put("action", "me"),
        authenticated = true,
    ).map { response ->
        val config = ConfigStore.load(context)
        parseSession(response, config.accessToken)
    }

    fun createCompany(
        context: Context,
        organizationName: String,
        organizationEik: String,
        activationToken: String,
    ): Result<Session> = postSession(
        context,
        JSONObject()
            .put("action", "create_company")
            .put("organization_name", organizationName.trim())
            .put("organization_eik", organizationEik.trim())
            .put("activation_token", activationToken.trim())
            .put("device_name", android.os.Build.MODEL.take(120)),
        authenticated = true,
    )

    /** Backward-compatible password registration for older callers. */
    fun registerProfile(
        context: Context,
        email: String,
        password: String,
        displayName: String,
    ): Result<Session> = postSession(
        context,
        JSONObject()
            .put("action", "register_profile")
            .put("email", email.trim())
            .put("password", password)
            .put("display_name", displayName.trim())
            .put("device_name", android.os.Build.MODEL.take(120)),
    )

    /** Backward-compatible password login for older callers. */
    fun login(context: Context, email: String, password: String): Result<Session> = postSession(
        context,
        JSONObject()
            .put("action", "login_profile")
            .put("email", email.trim())
            .put("password", password)
            .put("device_name", android.os.Build.MODEL.take(120)),
    )

    fun logout(context: Context): Result<Unit> = requestJson(
        context,
        JSONObject().put("action", "logout"),
        authenticated = true,
    ).map { Unit }

    fun applySession(context: Context, session: Session) {
        val current = ConfigStore.load(context)
        ConfigStore.save(
            context,
            current.copy(
                remoteEnabled = true,
                baseUrl = current.baseUrl.trim(),
                accessToken = session.accessToken,
            ),
        )
        CompanySessionStore.save(context, session)
    }

    fun applyProfileUser(context: Context, user: ProfileUser) {
        CompanySessionStore.updateProfile(context, user)
    }

    fun clearSession(context: Context) {
        val current = ConfigStore.load(context)
        ConfigStore.save(
            context,
            current.copy(
                remoteEnabled = false,
                accessToken = "",
            ),
        )
        CompanySessionStore.clear(context)
    }

    private fun postSession(
        context: Context,
        payload: JSONObject,
        authenticated: Boolean = false,
    ): Result<Session> = runCatching {
        val response = requestJson(context, payload, authenticated).getOrThrow()
        if (response.optBoolean("selection_required", false)) {
            val organizations = response.optJSONArray("organizations")
            val selectedId = organizations?.optJSONObject(0)?.optString("id").orEmpty().trim()
            require(selectedId.isNotBlank()) { "Няма достъпна фирма за този профил." }
            val retry = JSONObject(payload.toString()).put("organization_id", selectedId)
            return@runCatching postSession(context, retry, authenticated).getOrThrow()
        }
        parseSession(response)
    }

    private fun requestJson(
        context: Context,
        payload: JSONObject,
        authenticated: Boolean = false,
    ): Result<JSONObject> = runCatching {
        val config = ConfigStore.load(context)
        require(config.baseUrl.isNotBlank()) { "Първо задай Server URL в Настройки." }
        if (authenticated) require(config.accessToken.isNotBlank()) { "Първо влез в профила." }
        val connection = (URL(buildEndpoint(config.baseUrl, AUTH_PATH, emptyMap())).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (authenticated) {
                // Some Apache/FastCGI configurations do not forward Authorization to PHP.
                // Send the same token headers used by the app's other working endpoints.
                setRequestProperty("Authorization", "Bearer ${config.accessToken}")
                setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
                setRequestProperty("X-Callreport-Token", config.accessToken)
            }
        }
        try {
            val body = payload.toString().toByteArray(StandardCharsets.UTF_8)
            connection.outputStream.use { it.write(body) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty().ifBlank { "{}" })
            if (connection.responseCode !in 200..299 || !response.optBoolean("ok", false)) {
                val errorObject = response.optJSONObject("error")
                val message = errorObject?.optString("message").orEmpty()
                    .ifBlank { response.optString("error").trim() }
                    .ifBlank { "Неуспешна заявка към сървъра." }
                throw IllegalStateException(message)
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun parseChallenge(response: JSONObject): OtpChallenge {
        val id = response.optString("challenge_id").trim()
        require(id.isNotBlank()) { "Сървърът не върна валиден код за заявката." }
        return OtpChallenge(
            id = id,
            channel = response.optString("channel").trim(),
            destinationHint = response.optString("destination_hint").trim(),
            expiresAtMs = response.optLong("expires_at_ms", 0L),
            debugCode = response.optString("debug_code").trim(),
        )
    }

    private fun parseUser(user: JSONObject?): ProfileUser = ProfileUser(
        name = user?.optString("display_name").orEmpty().trim(),
        email = user?.optString("email").orEmpty().trim(),
        phone = user?.optString("phone").orEmpty().trim(),
        emailVerified = user?.optBoolean("email_verified", false) ?: false,
        phoneVerified = user?.optBoolean("phone_verified", false) ?: false,
    )

    private fun parseSession(response: JSONObject, fallbackToken: String = ""): Session {
        val accessToken = response.optString("access_token").trim().ifBlank { fallbackToken.trim() }
        require(accessToken.isNotBlank()) { "Сървърът не върна валиден access token." }
        val user = parseUser(response.optJSONObject("user"))
        val organization = response.optJSONObject("organization")
        return Session(
            accessToken = accessToken,
            userName = user.name,
            organizationName = organization?.optString("name").orEmpty().trim(),
            organizationId = organization?.optString("id").orEmpty().trim(),
            userEmail = user.email,
            userPhone = user.phone,
            emailVerified = user.emailVerified,
            phoneVerified = user.phoneVerified,
        )
    }
}
