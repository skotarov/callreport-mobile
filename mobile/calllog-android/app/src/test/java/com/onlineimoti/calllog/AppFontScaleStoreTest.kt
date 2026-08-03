package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class AppFontScaleStoreTest {
    @Test
    fun exposesFourOrderedProfilesWithNormalAsDefault() {
        assertEquals(1.0f, AppFontScaleStore.VERY_SMALL, 0.0f)
        assertEquals(1.15f, AppFontScaleStore.SMALL, 0.0f)
        assertEquals(1.3f, AppFontScaleStore.NORMAL, 0.0f)
        assertEquals(1.45f, AppFontScaleStore.LARGE, 0.0f)
        assertEquals(AppFontScaleStore.NORMAL, AppFontScaleStore.DEFAULT, 0.0f)
    }

    @Test
    fun preservesEveryPreviouslyStoredMultiplierAtItsNewLabelLevel() {
        assertEquals(AppFontScaleStore.VERY_SMALL, AppFontScaleStore.normalize(1.0f), 0.0f)
        assertEquals(AppFontScaleStore.SMALL, AppFontScaleStore.normalize(1.15f), 0.0f)
        assertEquals(AppFontScaleStore.NORMAL, AppFontScaleStore.normalize(1.3f), 0.0f)
        assertEquals(AppFontScaleStore.LARGE, AppFontScaleStore.normalize(1.45f), 0.0f)
    }
}
