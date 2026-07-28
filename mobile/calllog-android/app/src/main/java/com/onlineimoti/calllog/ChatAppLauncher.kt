package com.onlineimoti.calllog

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast

internal class ChatAppLauncher(
    private val activity: Activity,
) {
    fun open(app: ChatApp, phone: String) {
        val normalized = PhoneNormalizer.normalize(phone)
        if (app.requiresPhone() && normalized.isBlank()) {
            Toast.makeText(activity, R.string.chat_invalid_phone, Toast.LENGTH_SHORT).show()
            return
        }
        val opened = when (app) {
            ChatApp.VIBER -> openViber(normalized)
            ChatApp.WHATSAPP -> openWhatsApp(normalized, app.packageNames)
            ChatApp.TELEGRAM -> openTelegram(normalized, app.packageNames)
            ChatApp.MESSAGES -> openMessages(normalized, app.packageNames)
            else -> openInstalledApp(app.packageNames)
        }
        if (!opened) {
            Toast.makeText(
                activity,
                activity.getString(R.string.chat_app_not_available, app.displayName),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun openViber(phone: String): Boolean {
        val packageName = ChatApp.VIBER.packageNames.first()
        val chat = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("viber://chat?number=${Uri.encode(phone)}"),
        ).setPackage(packageName)
        if (start(chat)) return true
        return start(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("viber://add?number=${phone.filter(Char::isDigit)}"),
            ).setPackage(packageName),
        )
    }

    private fun openWhatsApp(phone: String, packages: List<String>): Boolean {
        val digits = phone.filter(Char::isDigit)
        return startForPackages(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")), packages)
    }

    private fun openTelegram(phone: String, packages: List<String>): Boolean {
        val digits = phone.filter(Char::isDigit)
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("tg://resolve?phone=${Uri.encode(digits)}&profile"),
        )
        return startForPackages(intent, packages) || start(intent)
    }

    private fun openMessages(phone: String, packages: List<String>): Boolean {
        return startForPackages(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phone)}")),
            packages,
        )
    }

    private fun openInstalledApp(packages: List<String>): Boolean {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return startForPackages(launcherIntent, packages)
    }

    private fun startForPackages(baseIntent: Intent, packages: List<String>): Boolean {
        packages.forEach { packageName ->
            if (start(Intent(baseIntent).setPackage(packageName))) return true
        }
        return false
    }

    private fun ChatApp.requiresPhone(): Boolean = when (this) {
        ChatApp.VIBER,
        ChatApp.WHATSAPP,
        ChatApp.TELEGRAM,
        ChatApp.MESSAGES,
        -> true
        else -> false
    }

    private fun start(intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
