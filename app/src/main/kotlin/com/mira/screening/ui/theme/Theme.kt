package com.mira.screening.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val MiraColors = lightColorScheme(
    primary = MiraTeal,
    onPrimary = MiraSurface,
    primaryContainer = MiraSurfaceVariant,
    onPrimaryContainer = MiraTeal,
    secondary = MiraTerracotta,
    onSecondary = MiraSurface,
    secondaryContainer = MiraPositiveBg,
    onSecondaryContainer = MiraTerracotta,
    background = MiraCream,
    onBackground = MiraInk,
    surface = MiraSurface,
    onSurface = MiraInk,
    surfaceVariant = MiraSurfaceVariant,
    onSurfaceVariant = MiraInkMuted,
    outline = MiraInkSoft,
    outlineVariant = MiraDivider,
    error = MiraError,
    onError = MiraOnError,
    errorContainer = MiraErrorContainer,
    onErrorContainer = MiraOnErrorContainer
)

private val MiraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Status palette accessible via MaterialTheme.miraStatus inside any composable.
 * Kept off the ColorScheme because Material's slots are already overloaded and
 * status colors deserve named clarity at the call site.
 */
data class MiraStatusColors(
    val positiveBg: Color,
    val positiveDot: Color,
    val negativeBg: Color,
    val negativeDot: Color,
    val inconclusiveBg: Color,
    val inconclusiveDot: Color
)

private val DefaultStatusColors = MiraStatusColors(
    positiveBg = MiraPositiveBg,
    positiveDot = MiraPositiveDot,
    negativeBg = MiraNegativeBg,
    negativeDot = MiraNegativeDot,
    inconclusiveBg = MiraInconclusiveBg,
    inconclusiveDot = MiraInconclusiveDot
)

private val LocalMiraStatus = staticCompositionLocalOf { DefaultStatusColors }

val MaterialTheme.miraStatus: MiraStatusColors
    @Composable get() = LocalMiraStatus.current

@Composable
fun MiraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MiraColors,
        typography = MiraTypography,
        shapes = MiraShapes,
        content = content
    )
}
