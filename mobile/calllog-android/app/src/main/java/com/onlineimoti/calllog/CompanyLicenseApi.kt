package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal object CompanyLicenseApi {
    data class ActivationResult(
        val activationToken: String,
        val expiresAtMs: Long,
        val productId: String,
    )

    data class PaymentOption(
        val id: String,
        val type: String,
        val title: String,
        val subtitle: String,
        val description: String,
        val enabled: Boolean,
        val primary: Boolean,
        val productId: String,
        val url: String,
    )

    data class LicenseStatus(
        val state: String,
        val title: String,
        val message: String,
        val capabilities: List<String>,
        val paymentOptions: List<PaymentOption>,
    ) {
        fun googlePlayOption(): PaymentOption? = paymentOptions.firstOrNull { it.type == "google_play" }
        fun googlePlayEnabled(): Boolean = googlePlayOption()?.enabled != false
    }

    data class PlayDiagnostics(
        val stage: String,
        val responseCode: Int? = null,
        val debugMessage: String = "",
        val productId: String = BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID,
        val fetchedProducts: Int = 0,
        val unfetchedProducts: Int = 0,
        val purchaseCount: Int = 0,
        val hasPurchaseToken: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("stage", stage)
            .put("response_code", responseCode ?: JSONObject.NULL)
            .put("debug_message", debugMessage)
            .put("product_id", productId)
            .put("fetched_products", fetchedProducts)
            .put("unfetched_products", unfetchedProducts)
            .put("purchase_count", purchaseCount)
            .put("has_purchase_token", hasPurchaseToken)
    }

    fun licenseStatus(context: Context, diagnostics: PlayDiagnostics? = null): Result<LicenseStatus> = runCatching {
        val payload = JSONObject().put("action", "license_status")
        diagnostics?.let { payload.put("play", it.toJson()) }
        val response = postJson(context, payload)
        val options = mutableListOf<PaymentOption>()
        val paymentOptions = response.optJSONArray("payment_options")
        if (paymentOptions != null) {
            for (index in 0 until paymentOptions.length()) {
                val item = paymentOptions.optJSONObject(index) ?: continue
                options += PaymentOption(
                    id = item.optString("id").trim(),
                    type = item.optString("type").trim(),
                    title = item.optString("title").trim(),
                    subtitle = item.optString("subtitle").trim(),
                    description = item.optString("description").trim(),
                    enabled = item.optBoolean("enabled", true),
                    primary = item.optBoolean("primary", false),
                    productId = item.optString("product_id").trim(),
                    url = item.optString("url").trim(),
                )
            }
        }
        val capabilities = mutableListOf<String>()
        val capabilityArray = response.optJSONArray("capabilities")
        if (capabilityArray != null) {
            for (index in 0 until capabilityArray.length()) {
                capabilityArray.optString(index).trim().takeIf { it.isNotBlank() }?.let(capabilities::add)
            }
        }
        LicenseStatus(
            state = response.optString("license_state").trim(),
            title = response.optString("title").trim(),
            message = response.optString("message").trim(),
            capabilities = capabilities,
            paymentOptions = options,
        )
    }

    fun verifyPurchase(context: Context, productId: String, purchaseToken: String): Result<ActivationResult> = runCatching {
        require(productId.isNotBlank()) { "Липсва продукт за фирмен лиценз." }
        require(purchaseToken.isNotBlank()) { "Липсва Google Play purchase token." }

        val response = postJson(
            context,
            JSONObject()
                .put("action", "verify_purchase")
                .put("product_id", productId)
                .put("purchase_token", purchaseToken),
        )
        val activationToken = response.optString("activation_token").trim()
        val expiresAtMs = response.optLong("activation_expires_at_ms", 0L)
        val verifiedProductId = response.optString("product_id", productId).trim()
        require(activationToken.isNotBlank() && expiresAtMs > System.currentTimeMillis()) {
            "Сървърът не издаде валиден активиращ код."
        }
        ActivationResult(activationToken, expiresAtMs, verifiedProductId)
    }

    private fun postJson(context: Context, payload: JSONObject): JSONObject {
        val config = ConfigStore.load(context)
        require(config.baseUrl.isNotBlank()) { "Първо задай Server URL в Настройки." }
        if (config.accessToken.isNotBlank()) payload.put("access_token", config.accessToken)

        val request = payload.toString().toByteArray(StandardCharsets.UTF_8)
        val connection = (URL(buildEndpoint(config.baseUrl, config.billingPath, emptyMap())).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (config.accessToken.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.accessToken}")
        }
        try {
            connection.outputStream.use { it.write(request) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val response = JSONObject(responseText.ifBlank { "{}" })
            if (connection.responseCode !in 200..299 || !response.optBoolean("ok", false)) {
                val error = response.optJSONObject("error")
                val errorText = error?.optString("message").orEmpty().ifBlank { response.optString("error") }
                throw IllegalStateException(errorText.ifBlank { "Заявката към billing сървъра не беше успешна." })
            }
            return response
        } finally {
            connection.disconnect()
        }
    }
}
