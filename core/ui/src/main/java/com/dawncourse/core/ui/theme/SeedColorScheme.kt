package com.dawncourse.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot

/** 手动选色与壁纸取色共用完整的 Material 色阶，避免未覆盖的角色保留默认紫色。 */
fun seedColorScheme(seed: Color, darkTheme: Boolean): ColorScheme {
    val scheme = SchemeTonalSpot(Hct.fromInt(seed.toArgb()), darkTheme, 0.0)
    return lightColorScheme(
        primary = Color(scheme.primary), onPrimary = Color(scheme.onPrimary),
        primaryContainer = Color(scheme.primaryContainer), onPrimaryContainer = Color(scheme.onPrimaryContainer),
        inversePrimary = Color(scheme.inversePrimary),
        secondary = Color(scheme.secondary), onSecondary = Color(scheme.onSecondary),
        secondaryContainer = Color(scheme.secondaryContainer), onSecondaryContainer = Color(scheme.onSecondaryContainer),
        tertiary = Color(scheme.tertiary), onTertiary = Color(scheme.onTertiary),
        tertiaryContainer = Color(scheme.tertiaryContainer), onTertiaryContainer = Color(scheme.onTertiaryContainer),
        background = Color(scheme.background), onBackground = Color(scheme.onBackground),
        surface = Color(scheme.surface), onSurface = Color(scheme.onSurface),
        surfaceVariant = Color(scheme.surfaceVariant), onSurfaceVariant = Color(scheme.onSurfaceVariant),
        surfaceTint = Color(scheme.surfaceTint), inverseSurface = Color(scheme.inverseSurface),
        inverseOnSurface = Color(scheme.inverseOnSurface),
        error = Color(scheme.error), onError = Color(scheme.onError),
        errorContainer = Color(scheme.errorContainer), onErrorContainer = Color(scheme.onErrorContainer),
        outline = Color(scheme.outline), outlineVariant = Color(scheme.outlineVariant), scrim = Color(scheme.scrim),
        surfaceBright = Color(scheme.surfaceBright), surfaceDim = Color(scheme.surfaceDim),
        surfaceContainer = Color(scheme.surfaceContainer), surfaceContainerHigh = Color(scheme.surfaceContainerHigh),
        surfaceContainerHighest = Color(scheme.surfaceContainerHighest), surfaceContainerLow = Color(scheme.surfaceContainerLow),
        surfaceContainerLowest = Color(scheme.surfaceContainerLowest),
        primaryFixed = Color(scheme.primaryFixed), primaryFixedDim = Color(scheme.primaryFixedDim),
        onPrimaryFixed = Color(scheme.onPrimaryFixed), onPrimaryFixedVariant = Color(scheme.onPrimaryFixedVariant),
        secondaryFixed = Color(scheme.secondaryFixed), secondaryFixedDim = Color(scheme.secondaryFixedDim),
        onSecondaryFixed = Color(scheme.onSecondaryFixed), onSecondaryFixedVariant = Color(scheme.onSecondaryFixedVariant),
        tertiaryFixed = Color(scheme.tertiaryFixed), tertiaryFixedDim = Color(scheme.tertiaryFixedDim),
        onTertiaryFixed = Color(scheme.onTertiaryFixed), onTertiaryFixedVariant = Color(scheme.onTertiaryFixedVariant),
    )
}
