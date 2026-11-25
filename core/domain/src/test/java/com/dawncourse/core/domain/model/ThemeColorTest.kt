package com.dawncourse.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ThemeColorTest {
    @Test fun newInstallUsesGreenWithoutWallpaperOverride() {
        assertEquals(ThemeColor.DEFAULT_GREEN, AppSettings().themeColor)
        assertFalse(AppSettings().dynamicColor)
    }

    @Test fun oldOrUnknownStoredColorFallsBackToGreen() {
        assertEquals(ThemeColor.DEFAULT_GREEN, ThemeColor.fromStoredName(null))
        assertEquals(ThemeColor.DEFAULT_GREEN, ThemeColor.fromStoredName("unknown-future-color"))
        ThemeColor.entries.forEach { assertEquals(it, ThemeColor.fromStoredName(it.name)) }
    }
}
