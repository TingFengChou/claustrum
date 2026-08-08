package com.claustrum.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Full design token set (Material3's ColorScheme doesn't carry steel/warn/surface2). */
@Immutable
data class ClaustrumColors(
    val ground: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val onAccent: Color,
    val steel: Color,
    val warn: Color,
)

val DarkClaustrum = ClaustrumColors(
    ground = Graphite, surface = SurfaceDark, surface2 = Surface2Dark, line = LineDark,
    ink = InkDark, muted = MutedDark, faint = FaintDark,
    accent = TeslaRed, onAccent = OnAccent, steel = Steel, warn = Amber,
)

val LightClaustrum = ClaustrumColors(
    ground = Paper, surface = SurfaceLight, surface2 = Surface2Light, line = LineLight,
    ink = InkLight, muted = MutedLight, faint = FaintLight,
    accent = TeslaRed, onAccent = OnAccent, steel = Steel, warn = Amber,
)

val LocalClaustrumColors = staticCompositionLocalOf { DarkClaustrum }

/** Access design tokens: `ClaustrumTheme.colors.steel`, etc. */
object ClaustrumTheme {
    val colors: ClaustrumColors
        @Composable get() = LocalClaustrumColors.current
}

private fun materialScheme(c: ClaustrumColors, dark: Boolean) =
    if (dark) darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, secondary = c.steel,
        background = c.ground, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
        error = c.accent, outline = c.line,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, secondary = c.steel,
        background = c.ground, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
        error = c.accent, outline = c.line,
    )

@Composable
fun ClaustrumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkClaustrum else LightClaustrum
    CompositionLocalProvider(LocalClaustrumColors provides colors) {
        MaterialTheme(
            colorScheme = materialScheme(colors, darkTheme),
            typography = ClaustrumType,
            content = content,
        )
    }
}
