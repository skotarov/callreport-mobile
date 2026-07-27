package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerConnectionTesterInputTest {
    @Test
    fun missingUrlIsRejectedBeforeNetworkRequest() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ServerConnectionTester.test(config(baseUrl = "", accessToken = "token"))
        }
        assertEquals("Липсва Server URL.", error.message)
    }

    @Test
    fun missingTokenIsRejectedBeforeNetworkRequest() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ServerConnectionTester.test(config(baseUrl = "https://example.com", accessToken = ""))
        }
        assertEquals("Липсва access token.", error.message)
    }

    private fun config(baseUrl: String, accessToken: String): AppConfig = AppConfig(
        remoteEnabled = true,
        baseUrl = baseUrl,
        accessToken = accessToken,
        contactGroups = "",
        notifyUnknownContacts = true,
        notifyKnownContacts = false,
        homeCallPageSize = ConfigStore.DEFAULT_HOME_CALL_PAGE_SIZE,
        lookupPath = ConfigStore.DEFAULT_LOOKUP_PATH,
        formPath = ConfigStore.DEFAULT_FORM_PATH,
        historyPath = ConfigStore.DEFAULT_HISTORY_PATH,
        postCallPromptTimeoutSeconds = ConfigStore.DEFAULT_POST_CALL_TIMEOUT_SECONDS,
        useOverlayPopups = false,
        useCustomStartPopup = true,
        useCustomEndPopup = true,
        postCallEndAction = ConfigStore.DEFAULT_POST_CALL_END_ACTION,
        contactLinkMode = ConfigStore.DEFAULT_CONTACT_LINK_MODE,
        showCrmActionButtons = true,
        showBulkContactSyncNotifications = false,
        appLanguage = ConfigStore.DEFAULT_APP_LANGUAGE,
        usePublicNotesFolder = false,
        useCallScreening = false,
        showRmDebugBox = false,
    )
}
