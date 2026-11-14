package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val EdgeClawDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = ObsidianBase,
    primaryContainer = DeepTealContainer,
    onPrimaryContainer = ElectricCyan,
    secondary = CobaltAccent,
    onSecondary = Color.White,
    secondaryContainer = CobaltContainer,
    onSecondaryContainer = Color(0xFFBFDBFE),
    tertiary = AmberGuardrail,
    onTertiary = ObsidianBase,
    tertiaryContainer = AmberGuardrailContainer,
    onTertiaryContainer = Color(0xFFFDE68A),
    error = CoralTrash,
    onError = Color.White,
    errorContainer = CoralTrashContainer,
    onErrorContainer = Color(0xFFFCA5A5),
    background = ObsidianBase,
    onBackground = TextPrimaryDark,
    surface = ObsidianSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = TextSecondaryDark,
    outline = ObsidianCardBorder,
    outlineVariant = Color(0xFF1A253A)
)

private val EdgeClawLightColorScheme = lightColorScheme(
    primary = DeepCyanLight,
    onPrimary = Color.White,
    primaryContainer = DeepCyanContainerLight,
    onPrimaryContainer = Color(0xFF001F24),
    secondary = DeepCobaltLight,
    onSecondary = Color.White,
    secondaryContainer = DeepCobaltContainerLight,
    onSecondaryContainer = Color(0xFF0F172A),
    tertiary = Color(0xFFB45309),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF451A03),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = PorcelainBase,
    onBackground = TextPrimaryLight,
    surface = PorcelainSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = PorcelainSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    outline = PorcelainOutline,
    outlineVariant = Color(0xFFE2E8F0)
)

val EdgeClawShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun EdgeClawTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> EdgeClawDarkColorScheme
        else -> EdgeClawLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = EdgeClawTypography,
        shapes = EdgeClawShapes,
        content = content
    )
}
