package com.nazeeltek.savetek.ui.theme

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.nazeeltek.savetek.data.SettingsRepository
import com.nazeeltek.savetek.data.ThemeMode

// ───────────── ألوان الهوية ─────────────

/** الذهبي الأساسي (للخلفيات الداكنة). */
private val Gold = Color(0xFFD4A853)

/** ذهبي أغمق للوضع الفاتح، حتى يبقى واضحاً ومقروءاً على الأبيض. */
private val GoldDeep = Color(0xFF8F6A16)

private val Navy = Color(0xFF1E2A47)
private val NavyLight = Color(0xFF2A3A60)

/** كحلي وذهبي: الهوية الأساسية (الافتراضي). */
private val NavyGoldScheme = darkColorScheme(
    primary = Gold,
    onPrimary = Navy,
    secondary = Gold,
    onSecondary = Navy,
    background = Navy,
    onBackground = Color(0xFFF2F2F2),
    surface = NavyLight,
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF26355A),
    onSurfaceVariant = Color(0xFFC9CED9),
    surfaceContainer = NavyLight,
    surfaceContainerHigh = NavyLight,
    surfaceContainerHighest = Color(0xFF33456F),
    outline = Gold,
    outlineVariant = Color(0x59D4A853),
    error = Color(0xFFFF8A80),
    onError = Navy,
)

/** فاتح: خلفيات بيضاء ونصوص داكنة. */
private val LightScheme = lightColorScheme(
    primary = GoldDeep,
    onPrimary = Color.White,
    secondary = GoldDeep,
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF1B1F2A),
    surface = Color(0xFFF5F1E8),
    onSurface = Color(0xFF1B1F2A),
    surfaceVariant = Color(0xFFEDE6D6),
    onSurfaceVariant = Color(0xFF4A4F5C),
    surfaceContainer = Color(0xFFF5F1E8),
    surfaceContainerHigh = Color(0xFFF5F1E8),
    surfaceContainerHighest = Color(0xFFEDE6D6),
    outline = GoldDeep,
    outlineVariant = Color(0x598F6A16),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

/** داكن: خلفيات سوداء تماماً (مناسب لشاشات AMOLED ويوفّر البطارية). */
private val AmoledScheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color.Black,
    secondary = Gold,
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFB5B5B5),
    surfaceContainer = Color(0xFF0E0E0E),
    surfaceContainerHigh = Color(0xFF161616),
    surfaceContainerHighest = Color(0xFF222222),
    outline = Gold,
    outlineVariant = Color(0x59D4A853),
    error = Color(0xFFFF8A80),
    onError = Color.Black,
)

/** هل المظهر الحالي فاتح؟ ("حسب الجهاز" يتبع وضع الجوال). */
@Composable
fun isLightTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.LIGHT -> true
    ThemeMode.SYSTEM -> !isSystemInDarkTheme()
    ThemeMode.NAVY_GOLD, ThemeMode.DARK -> false
}

@Composable
private fun schemeFor(mode: ThemeMode): ColorScheme = when (mode) {
    ThemeMode.NAVY_GOLD -> NavyGoldScheme
    ThemeMode.LIGHT -> LightScheme
    ThemeMode.DARK -> AmoledScheme
    ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) AmoledScheme else LightScheme
}

/**
 * يطبّق المظهر المختار على الشاشة. يتغيّر فوراً عند تغييره من الإعدادات
 * لأنه يراقب الاختيار المحفوظ باستمرار.
 *
 * @param forceDarkSystemBars لشاشة الفيديو: أيقونات أشرطة النظام فاتحة دائماً فوق الأسود.
 */
@Composable
fun SaveTekTheme(forceDarkSystemBars: Boolean = false, content: @Composable () -> Unit) {
    val mode by SettingsRepository.themeMode.collectAsState()
    val light = isLightTheme(mode) && !forceDarkSystemBars

    // لون أيقونات شريط الحالة وشريط التنقل حسب المظهر
    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(light, activity) {
        val transparent = AndroidColor.TRANSPARENT
        val style = if (light) SystemBarStyle.light(transparent, transparent)
        else SystemBarStyle.dark(transparent)
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }

    MaterialTheme(colorScheme = schemeFor(mode), content = content)
}
