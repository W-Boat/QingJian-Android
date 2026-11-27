package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class QingJianAccent(
  val label: String,
  val hex: String,
  val lightPrimary: Color,
  val darkPrimary: Color,
  val lightContainer: Color,
  val darkContainer: Color,
) {
  JADE(
    label = "青玉",
    hex = "#1F7A6E",
    lightPrimary = Color(0xFF1F7A6E),
    darkPrimary = Color(0xFF52B8A8),
    lightContainer = Color(0xFFDDF1ED),
    darkContainer = Color(0xFF163D38),
  ),
  INDIGO(
    label = "靛蓝",
    hex = "#2B5EA7",
    lightPrimary = Color(0xFF2B5EA7),
    darkPrimary = Color(0xFF6B9EE8),
    lightContainer = Color(0xFFE1ECF9),
    darkContainer = Color(0xFF1A3152),
  ),
  PINE(
    label = "墨松",
    hex = "#2D6A4F",
    lightPrimary = Color(0xFF2D6A4F),
    darkPrimary = Color(0xFF62B58F),
    lightContainer = Color(0xFFE0F1E8),
    darkContainer = Color(0xFF173A2B),
  ),
  CINNABAR(
    label = "绛砂",
    hex = "#A63A3A",
    lightPrimary = Color(0xFFA63A3A),
    darkPrimary = Color(0xFFE07777),
    lightContainer = Color(0xFFF8E3E3),
    darkContainer = Color(0xFF4B1E1E),
  ),
  AMBER(
    label = "琥珀",
    hex = "#9A6324",
    lightPrimary = Color(0xFF9A6324),
    darkPrimary = Color(0xFFDBA45E),
    lightContainer = Color(0xFFF6EBD9),
    darkContainer = Color(0xFF463015),
  ),
}

@Composable
fun QingJianTheme(
  darkTheme: Boolean,
  accent: QingJianAccent,
  content: @Composable () -> Unit,
) {
  val scheme = if (darkTheme) {
    darkColorScheme(
      primary = accent.darkPrimary,
      onPrimary = Color(0xFF0A1412),
      primaryContainer = accent.darkContainer,
      onPrimaryContainer = Color(0xFFE6EAF0),
      secondary = Color(0xFF9AA5B4),
      secondaryContainer = Color(0xFF232833),
      tertiary = Color(0xFFD99B38),
      background = Color(0xFF0E1014),
      onBackground = Color(0xFFE6EAF0),
      surface = Color(0xFF171A21),
      onSurface = Color(0xFFE6EAF0),
      surfaceVariant = Color(0xFF202530),
      onSurfaceVariant = Color(0xFF9AA4B2),
      outlineVariant = Color(0xFF2B313F),
      error = Color(0xFFE56B6F),
      errorContainer = Color(0xFF42191C),
      onErrorContainer = Color(0xFFFFD6D9),
    )
  } else {
    lightColorScheme(
      primary = accent.lightPrimary,
      onPrimary = Color.White,
      primaryContainer = accent.lightContainer,
      onPrimaryContainer = Color(0xFF11181C),
      secondary = Color(0xFF4B5563),
      secondaryContainer = Color(0xFFEBEEF2),
      tertiary = Color(0xFFB47818),
      background = Color(0xFFF7F8FA),
      onBackground = Color(0xFF1B1F24),
      surface = Color(0xFFFFFFFF),
      onSurface = Color(0xFF1B1F24),
      surfaceVariant = Color(0xFFF0F2F5),
      onSurfaceVariant = Color(0xFF59636E),
      outlineVariant = Color(0xFFE1E5EB),
      error = Color(0xFFB42318),
      errorContainer = Color(0xFFFEE4E2),
      onErrorContainer = Color(0xFF7A271A),
    )
  }

  MaterialTheme(
    colorScheme = scheme,
    content = content,
  )
}
