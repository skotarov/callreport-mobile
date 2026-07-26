package com.onlineimoti.calllog

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Creates and restores a PIN-encrypted server settings backup file. */
internal object ServerSettingsBackupStore {
    private const val FILE_NAME = "callreport-server-settings.json"
    private const val APP_NAME = "Call Report"
    private const val VERSION = 2
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private val random = SecureRandom()

    sealed interface RestoreResult {
        data class Restored(val config: AppConfig) : RestoreResult
        data class Failed(val message: String) : RestoreResult
    }

    fun suggestedFileName(): String = FILE_NAME

    fun createEncryptedJson(config: AppConfig, pin: String): String {
        requireValidPin(pin)
        val plainText = serverSettingsJson(config).toString().toByteArray(StandardCharsets.UTF_8)
        val salt = randomBytes(SALT_BYTES)
        val iv = randomBytes(IV_BYTES)
        val encrypted = Cipher.getInstance(CIPHER).run {
            init(Cipher.ENCRYPT_MODE, derivedKey(pin, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(plainText)
        }

        return JSONObject()
            .put("v", VERSION)
            .put("app", APP_NAME)
            .put("created_at", System.currentTimeMillis())
            .put("kdf", KDF)
            .put("iterations", ITERATIONS)
            .put("cipher", CIPHER)
            .put("salt", encode(salt))
            .put("iv", encode(iv))
            .put("ciphertext", encode(encrypted))
            .toString(2)
    }

    fun restoreEncryptedJson(currentConfig: AppConfig, content: String, pin: String): RestoreResult {
        return runCatching {
            requireValidPin(pin)
            val archive = JSONObject(content)
            require(archive.optString("app") == APP_NAME) { "Неподдържан архивен файл." }
            require(archive.optInt("v", 0) == VERSION) { "Неподдържана версия на архивния файл." }
            require(archive.optString("kdf") == KDF) { "Неподдържан начин за защита на архива." }
            require(archive.optString("cipher") == CIPHER) { "Неподдържан начин за защита на архива." }
            require(archive.optInt("iterations", 0) == ITERATIONS) { "Неподдържана версия на архивния файл." }

            val salt = decode(archive.requiredString("salt"))
            val iv = decode(archive.requiredString("iv"))
            val ciphertext = decode(archive.requiredString("ciphertext"))
            require(salt.size == SALT_BYTES && iv.size == IV_BYTES && ciphertext.isNotEmpty()) {
                "Повреден архивен файл."
            }

            val plainText = Cipher.getInstance(CIPHER).run {
                init(Cipher.DECRYPT_MODE, derivedKey(pin, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(ciphertext)
            }
            val settings = JSONObject(plainText.toString(StandardCharsets.UTF_8))
            RestoreResult.Restored(
                currentConfig.copy(
                    // Restored credentials must pass Connect again before CRM/server mode is enabled.
                    remoteEnabled = false,
                    baseUrl = settings.stringOrCurrent("base_url", currentConfig.baseUrl),
                    accessToken = settings.stringOrCurrent("access_token", currentConfig.accessToken),
                    lookupPath = settings.stringOrCurrent("lookup_path", currentConfig.lookupPath),
                    formPath = settings.stringOrCurrent("form_path", currentConfig.formPath),
                    historyPath = settings.stringOrCurrent("history_path", currentConfig.historyPath),
                    historyLookupPath = settings.stringOrCurrent("history_lookup_path", currentConfig.historyLookupPath),
                    syncPath = settings.stringOrCurrent("sync_path", currentConfig.syncPath),
                    syncEditPath = settings.stringOrCurrent("sync_edit_path", currentConfig.syncEditPath),
                    companyPhasePath = settings.stringOrCurrent("company_phase_path", currentConfig.companyPhasePath),
                    companyDestinationsPath = settings.stringOrCurrent("company_destinations_path", currentConfig.companyDestinationsPath),
                    contactsSharedLookupPath = settings.stringOrCurrent("contacts_shared_lookup_path", currentConfig.contactsSharedLookupPath),
                    profileCrmContactsPath = settings.stringOrCurrent("profile_crm_contacts_path", currentConfig.profileCrmContactsPath),
                    companyUsersPath = settings.stringOrCurrent("company_users_path", currentConfig.companyUsersPath),
                    authPath = settings.stringOrCurrent("auth_path", currentConfig.authPath),
                    invitationsPath = settings.stringOrCurrent("invitations_path", currentConfig.invitationsPath),
                    billingPath = settings.stringOrCurrent("billing_path", currentConfig.billingPath),
                ),
            )
        }.getOrElse {
            RestoreResult.Failed("Грешен PIN или повреден архивен файл.")
        }
    }

    private fun serverSettingsJson(config: AppConfig): JSONObject {
        return JSONObject()
            .put("remote_enabled", config.remoteEnabled)
            .put("base_url", config.baseUrl.trim())
            .put("access_token", config.accessToken.trim())
            .put("lookup_path", config.lookupPath.trim())
            .put("form_path", config.formPath.trim())
            .put("history_path", config.historyPath.trim())
            .put("history_lookup_path", config.historyLookupPath.trim())
            .put("sync_path", config.syncPath.trim())
            .put("sync_edit_path", config.syncEditPath.trim())
            .put("company_phase_path", config.companyPhasePath.trim())
            .put("company_destinations_path", config.companyDestinationsPath.trim())
            .put("contacts_shared_lookup_path", config.contactsSharedLookupPath.trim())
            .put("profile_crm_contacts_path", config.profileCrmContactsPath.trim())
            .put("company_users_path", config.companyUsersPath.trim())
            .put("auth_path", config.authPath.trim())
            .put("invitations_path", config.invitationsPath.trim())
            .put("billing_path", config.billingPath.trim())
    }

    private fun derivedKey(pin: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private fun requireValidPin(pin: String) {
        require(pin.length == 4 && pin.all(Char::isDigit)) { "PIN кодът трябва да е 4 цифри." }
    }

    private fun JSONObject.requiredString(key: String): String = optString(key).trim().also {
        require(it.isNotBlank()) { "Повреден архивен файл." }
    }

    private fun JSONObject.stringOrCurrent(key: String, currentValue: String): String {
        if (!has(key) || isNull(key)) return currentValue
        return optString(key).trim()
    }
}
