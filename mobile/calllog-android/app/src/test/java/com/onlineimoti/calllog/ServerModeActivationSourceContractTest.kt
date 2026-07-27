package com.onlineimoti.calllog

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServerModeActivationSourceContractTest {
    @Test
    fun connectionTestControlsRemoteAndCrmModes() {
        val projectRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Android module root not found")
        val source = File(projectRoot, "app/src/main/java/com/onlineimoti/calllog/MainActivity.kt").readText()
        assertTrue(source.contains("ConfigStore.save(this, entered.copy(remoteEnabled = false))"))
        assertTrue(source.contains("applyTestedServerMode(config, enabled = status.ok)"))
        assertTrue(source.contains("HomeCrmModeStore.setEnabled(this, enabled)"))
    }
}
