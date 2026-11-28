package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

enum class QingJianAccent(
  val label: String,
  val hex: String,
  val seedColor: Color,
  val lightPrimary: Color,
  val darkPrimary: Color,
  val lightContainer: Color,
  val darkContainer: Color,
) {
  JADE(
    label = "青玉",
    hex = "#1F7A6E",
    seedColor = Color(0xFF1F7A6E),
    lightPrimary = Color(0xFF1F7A6E),
    darkPrimary = Color(0xFF52B8A8),
    lightContainer = Color(0xFFDDF1ED),
    darkContainer = Color(0xFF163D38),
  ),
  INDIGO(
    label = "靛蓝",
    hex = "#2B5EA7",
    seedColor = Color(0xFF2B5EA7),
    lightPrimary = Color(0xFF2B5EA7),
    darkPrimary = Color(0xFF6B9EE8),
    lightContainer = Color(0xFFE1ECF9),
    darkContainer = Color(0xFF1A3152),
  ),
  PINE(
    label = "墨松",
    hex = "#2D6A4F",
    seedColor = Color(0xFF2D6A4F),
    lightPrimary = Color(0xFF2D6A4F),
    darkPrimary = Color(0xFF62B58F),
    lightContainer = Color(0xFFE0F1E8),
    darkContainer = Color(0xFF173A2B),
  ),
  CINNABAR(
    label = "绛砂",
    hex = "#A63A3A",
    seedColor = Color(0xFFA63A3A),
    lightPrimary = Color(0xFFA63A3A),
    darkPrimary = Color(0xFFE07777),
    lightContainer = Color(0xFFF8E3E3),
    darkContainer = Color(0xFF4B1E1E),
  ),
  AMBER(
    label = "琥珀",
    hex = "#9A6324",
    seedColor = Color(0xFF9A6324),
    lightPrimary = Color(0xFF9A6324),
    darkPrimary = Color(0xFFDBA45E),
    lightContainer = Color(0xFFF6EBD9),
    darkContainer = Color(0xFF463015),
  ),
}

/**
 * Genuine Miuix (`top.yukonga.miuix.kmp`) theme root modeled on Mangi-11/Eta `AgentAppTheme.kt`.
 * Provides `MiuixTheme` via `ThemeController` (Spec2025 + Monet custom seed color) and bridges
 * Material3 tokens directly from `MiuixTheme.colorScheme` for shared feature modules.
 */
@Composable
fun QingJianTheme(
  darkTheme: Boolean,
  accent: QingJianAccent,
  content: @Composable () -> Unit,
) {
  val colorSchemeMode = if (darkTheme) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight
  val controller = remember(colorSchemeMode, accent, darkTheme) {
    ThemeController(
      colorSchemeMode = colorSchemeMode,
      keyColor = accent.seedColor,
      colorSpec = ThemeColorSpec.Spec2025,
      paletteStyle = ThemePaletteStyle.TonalSpot,
      isDark = darkTheme,
    )
  }

  MiuixTheme(controller = controller) {
    val miuixColors = MiuixTheme.colorScheme
    val bridgedMaterialScheme = remember(miuixColors, darkTheme, accent) {
      if (darkTheme) {
        darkColorScheme(
          primary = miuixColors.primary,
          onPrimary = miuixColors.onPrimary,
          primaryContainer = miuixColors.primaryContainer,
          onPrimaryContainer = miuixColors.onSurface,
          secondary = miuixColors.onSurfaceSecondary,
          secondaryContainer = miuixColors.secondaryContainer,
          tertiary = Color(0xFFD99B38),
          background = miuixColors.background,
          onBackground = miuixColors.onBackground,
          surface = miuixColors.surfaceContainer,
          onSurface = miuixColors.onSurface,
          surfaceVariant = miuixColors.surfaceContainerHigh,
          onSurfaceVariant = miuixColors.onSurfaceVariantSummary,
          outlineVariant = miuixColors.dividerLine,
          error = miuixColors.error,
          onError = miuixColors.onError,
          errorContainer = Color(0xFF42191C),
          onErrorContainer = Color(0xFFFFD6D9),
        )
      } else {
        lightColorScheme(
          primary = miuixColors.primary,
          onPrimary = miuixColors.onPrimary,
          primaryContainer = miuixColors.primaryContainer,
          onPrimaryContainer = miuixColors.onSurface,
          secondary = miuixColors.onSurfaceSecondary,
          secondaryContainer = miuixColors.secondaryContainer,
          tertiary = Color(0xFFB47818),
          background = miuixColors.background,
          onBackground = miuixColors.onBackground,
          surface = miuixColors.surfaceContainer,
          onSurface = miuixColors.onSurface,
          surfaceVariant = miuixColors.surfaceContainerHigh,
          onSurfaceVariant = miuixColors.onSurfaceVariantSummary,
          outlineVariant = miuixColors.dividerLine,
          error = miuixColors.error,
          onError = miuixColors.onError,
          errorContainer = Color(0xFFFEE4E2),
          onErrorContainer = Color(0xFF7A271A),
        )
      }
    }

    CompositionLocalProvider(
      LocalContentColor provides miuixColors.onBackground,
    ) {
      MaterialTheme(
        colorScheme = bridgedMaterialScheme,
        content = content,
      )
    }
  }
}
