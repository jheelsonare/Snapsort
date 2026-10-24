package com.snapsort.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val Lime = Color(0xFFD2F86B)
internal val Charcoal = Color(0xFF141715)
internal val Panel = Color(0xFF202521)
internal val Muted = Color(0xFFA9B1A7)

private val SnapColors = darkColorScheme(
    primary = Lime,
    onPrimary = Color(0xFF25300E),
    primaryContainer = Color(0xFF344021),
    onPrimaryContainer = Lime,
    secondary = Color(0xFFBACAA1),
    background = Charcoal,
    onBackground = Color(0xFFF3F5EE),
    surface = Charcoal,
    onSurface = Color(0xFFF3F5EE),
    surfaceVariant = Panel,
    onSurfaceVariant = Muted,
    outline = Color(0xFF626B60),
    error = Color(0xFFFFB4A9),
)

@Composable
fun SnapSortTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SnapColors, typography = Typography(), content = content)
}
