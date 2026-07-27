package com.onlineimoti.calllog

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CompanySessionStoreSourceContractTest {
    @Test
    fun settingsCanReadRememberedProfileWithoutCurrentToken() {
        val projectRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Android module root not found")
        val store = File(projectRoot, "app/src/main/java/com/onlineimoti/calllog/CompanySessionStore.kt").readText()
        val registration = File(projectRoot, "app/src/main/java/com/onlineimoti/calllog/RegistrationActions.kt").readText()
        assertTrue(store.contains("fun loadStored(context: Context): Snapshot?"))
        assertTrue(registration.contains("CompanySessionStore.loadStored(activity)"))
        assertTrue(registration.contains("registrationEditProfileButton"))
    }
}
