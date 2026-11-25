package com.dawncourse.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.dawncourse.core.domain.model.ThemeColor
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedColorSchemeTest {
    @Test
    fun darkForestWallpaperHasReadableButtonAndMatchingContainer() {
        // 对应深绿壁纸的低亮度主色，走实际壁纸主题生成入口。
        val scheme = seedColorScheme(Color(0xFF3F5940), true)
        val a = scheme.primary.luminance()
        val b = scheme.onPrimary.luminance()
        val contrast = (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
        assertTrue("深色按钮文字对比度不足：$contrast", contrast >= 4.5f)
        assertTrue("绿色主题仍用了紫色容器", scheme.primaryContainer.green > scheme.primaryContainer.blue)
    }

    @Test
    fun everyPresetHasReadableForegroundsInBothModes() {
        for (color in ThemeColor.entries) for (dark in listOf(false, true)) {
            val scheme = seedColorScheme(Color(color.argb), dark)
            val roles = listOf(
                scheme.primary to scheme.onPrimary,
                scheme.primaryContainer to scheme.onPrimaryContainer,
                scheme.secondary to scheme.onSecondary,
                scheme.secondaryContainer to scheme.onSecondaryContainer,
                scheme.tertiary to scheme.onTertiary,
                scheme.tertiaryContainer to scheme.onTertiaryContainer,
                scheme.surface to scheme.onSurface,
                scheme.surfaceVariant to scheme.onSurfaceVariant,
                scheme.error to scheme.onError,
            )
            roles.forEachIndexed { index, (background, foreground) ->
                val a = background.luminance()
                val b = foreground.luminance()
                val ratio = (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
                assertTrue("$color dark=$dark role=$index contrast=$ratio", ratio >= 4.5f)
            }
        }
    }
}
