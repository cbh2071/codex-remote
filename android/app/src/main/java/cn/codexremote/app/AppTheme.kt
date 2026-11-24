package cn.codexremote.app

import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat

internal enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色")
}

private val day = lightColorScheme(
    primary = Color(0xFF173D31), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EDDC), onPrimaryContainer = Color(0xFF173D31),
    secondary = Color(0xFF365B7A), secondaryContainer = Color(0xFFE2EBF4), onSecondaryContainer = Color(0xFF254361),
    tertiary = Color(0xFF81450F), tertiaryContainer = Color(0xFFFFE9CF), onTertiaryContainer = Color(0xFF542C07),
    background = Color(0xFFF3F5EF), onBackground = Color(0xFF1D2A23),
    surface = Color(0xFFF3F5EF), onSurface = Color(0xFF1D2A23), onSurfaceVariant = Color(0xFF4F5D54),
    surfaceContainerLowest = Color.White, surfaceContainer = Color(0xFFEBEFE8), surfaceContainerHigh = Color(0xFFE2E8E1),
    outlineVariant = Color(0xFFCAD4CC)
)
private val night = darkColorScheme(
    primary = Color(0xFFA0D8B3), onPrimary = Color(0xFF103822),
    primaryContainer = Color(0xFF244333), onPrimaryContainer = Color(0xFFCAF0D6),
    secondary = Color(0xFFAFCAE7), secondaryContainer = Color(0xFF263C50), onSecondaryContainer = Color(0xFFD5E9FF),
    tertiary = Color(0xFFF2BF82), tertiaryContainer = Color(0xFF4C3521), onTertiaryContainer = Color(0xFFFFDDB5),
    background = Color(0xFF121614), onBackground = Color(0xFFE2E8E3),
    surface = Color(0xFF121614), onSurface = Color(0xFFE2E8E3), onSurfaceVariant = Color(0xFFB8C4BB),
    surfaceContainerLowest = Color(0xFF1A201D), surfaceContainer = Color(0xFF222925),
    surfaceContainerHigh = Color(0xFF2A322D), surfaceContainerHighest = Color(0xFF343D37),
    surfaceVariant = Color(0xFF38423B), outline = Color(0xFF89978D), outlineVariant = Color(0xFF434E46)
)
private val appTypography = Typography(
    titleLarge = Typography().titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
    headlineLarge = Typography().headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
)
internal val appColors: ColorScheme @Composable get() = MaterialTheme.colorScheme

@Composable
internal fun AppTheme(mode: ThemeMode, window: Window, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) night else day
    SideEffect {
        @Suppress("DEPRECATION")
        window.statusBarColor = colors.surface.toArgb()
        @Suppress("DEPRECATION")
        window.navigationBarColor = colors.surface.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, typography = appTypography, content = content)
}

@Composable
internal fun ThemeSetting(repo: Repository) {
    val mode by repo.themeMode.collectAsState()
    Text("外观", style = MaterialTheme.typography.titleSmall)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ThemeMode.entries.forEachIndexed { index, option ->
            SegmentedButton(selected = mode == option, onClick = { repo.selectTheme(option) },
                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)) {
                Text(option.label)
            }
        }
    }
}
