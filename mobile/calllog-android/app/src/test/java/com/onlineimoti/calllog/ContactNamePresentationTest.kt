package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactNamePresentationTest {
    @Test
    fun pipeSeparatedNameKeepsPrimaryNameAndShowsEachDetailOnItsOwnLine() {
        val presentation = ContactNamePresentation.from("Светльо | автобояджия | препоръчан от Иво")

        assertEquals("Светльо", presentation.primary)
        assertEquals(
            listOf(ContactNameDetail("автобояджия"), ContactNameDetail("препоръчан от Иво")),
            presentation.secondary,
        )
    }

    @Test
    fun blankPipePartsDoNotCreateEmptyLines() {
        val presentation = ContactNamePresentation.from("Светльо |  | автобояджия")

        assertEquals("Светльо", presentation.primary)
        assertEquals(listOf(ContactNameDetail("автобояджия")), presentation.secondary)
    }

    @Test
    fun parenthesizedDetailKeepsBracketsAndUsesItalicOnItsOwnLine() {
        val presentation = ContactNamePresentation.from("Светльо (автобояджия) | София")

        assertEquals("Светльо", presentation.primary)
        assertEquals(
            listOf(
                ContactNameDetail("(автобояджия)", italic = true),
                ContactNameDetail("София"),
            ),
            presentation.secondary,
        )
    }
}
