package com.onlineimoti.calllog

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileEditBindingSourceContractTest {
    @Test
    fun profileEditButtonExistsAndIsWired() {
        val projectRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Android module root not found")
        val layout = File(projectRoot, "app/src/main/res/layout/settings_group_registration.xml").readText()
        val binder = File(projectRoot, "app/src/main/java/com/onlineimoti/calllog/MainSettingsActionBinder.kt").readText()
        assertTrue(layout.contains("registrationEditProfileButton"))
        assertTrue(binder.contains("registrationEditProfileButton.setOnClickListener"))
    }
}
