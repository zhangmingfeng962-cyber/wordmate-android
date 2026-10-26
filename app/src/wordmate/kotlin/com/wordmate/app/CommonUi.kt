package com.wordmate.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

val Cream = Color(0xFFFAF9F5)
val Teal = Color(0xFF196653)
val PaleTeal = Color(0xFFE9F2EB)
val Ink = Color(0xFF233B33)
val InkMuted = Color(0xFF65736A)
val Apricot = Color(0xFFF4D4AE)

@Composable fun WordMateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Teal, onPrimary = Color.White, secondary = Color(0xFFA56036),
        background = Cream, surface = Cream, surfaceVariant = Color(0xFFF0F0E8), onSurface = Ink,
        onBackground = Ink, primaryContainer = PaleTeal, secondaryContainer = PaleTeal,
        onSecondaryContainer = Teal, outline = Color(0xFFD2D9CF)),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
        content = content)
}
@Composable fun SectionTitle(title: String, description: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium, color = InkMuted)
    }
}
@Composable fun EmptyState(title: String, description: String, icon: ImageVector) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = Teal, modifier = Modifier.size(50.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = InkMuted)
    }
}
@Composable fun Note(text: String, modifier: Modifier = Modifier) {
    Surface(color = PaleTeal, shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, color = Teal)
    }
}

/** Keep dialog action buttons above the IME, even on 360 x 640 dp phones. */
@Composable fun dialogBodyHeight(): Dp {
    val density = LocalDensity.current
    val keyboard = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    return (LocalConfiguration.current.screenHeightDp.dp - keyboard - 190.dp)
        .coerceIn(80.dp, 480.dp)
}
