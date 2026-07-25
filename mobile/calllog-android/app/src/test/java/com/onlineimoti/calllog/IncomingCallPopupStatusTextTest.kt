package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingCallPopupStatusTextTest {
    @Test
    fun longEmptyAndLoadingMessagesBecomeCompact() {
        assertEquals("Чака…", IncomingCallPopupStatusText.compact("… loading"))
        assertEquals("Няма", IncomingCallPopupStatusText.compact("Няма предишни разговори"))
        assertEquals("Няма", IncomingCallPopupStatusText.compact("Няма локални бележки"))
        assertEquals("Няма", IncomingCallPopupStatusText.compact("Няма сървърни бележки"))
        assertEquals("Изключен", IncomingCallPopupStatusText.compact("Сървърът не е настроен"))
        assertEquals("Грешка", IncomingCallPopupStatusText.compact("Сървърът не отговори"))
    }

    @Test
    fun realInformationRemainsVisible() {
        assertEquals(
            "Maxim · Имотна бележка",
            IncomingCallPopupStatusText.compact("Maxim   ·   Имотна бележка"),
        )
    }
}
