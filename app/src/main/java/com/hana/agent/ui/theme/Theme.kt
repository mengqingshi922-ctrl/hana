package com.hana.agent.ui.theme

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.hana.agent.settings.FeatureFlags
import com.hana.agent.settings.ThemeMode

/** 动效:时长和曲线。系统关了动画时([HanaTheme.reduceMotion])直接到位。 */
object HanaMotion {
    const val PAGE_MS = 220
    const val SWITCH_MS = 200
    /** 减速曲线:起步快、落地慢。 */
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    fun <T> page(reduceMotion: Boolean): FiniteAnimationSpec<T> = if (reduceMotion) snap() else tween(PAGE_MS, easing = Decelerate)
    fun <T> switch(reduceMotion: Boolean): FiniteAnimationSpec<T> = if (reduceMotion) snap() else tween(SWITCH_MS, easing = Decelerate)
}

/** 系统「移除动画」(动画时长缩放为 0)时为真。 */
fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

val LocalReduceMotion = staticCompositionLocalOf { false }

object HanaTheme {
    val colors: HanaColors
        @Composable @ReadOnlyComposable get() = LocalHanaColors.current

    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

/**
 * 设置里的外观:跟随系统时看系统,一直浅色/深色时照设置。设置一改(设置页或任务里改的)当场换,不重建界面,
 * 所以页面、输入、滚动位置都不丢。
 */
@Composable
fun appearanceDark(): Boolean {
    val context = LocalContext.current
    val flags = remember(context) { FeatureFlags.get(context) }
    val mode by remember(flags) { flags.themeModeChanges() }.collectAsState(flags.themeMode())
    return when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}

/** 动态取色是否开启 (Android 12+ Monet)。 */
@Composable
fun dynamicColorEnabled(): Boolean {
    val context = LocalContext.current
    val flags = remember(context) { FeatureFlags.get(context) }
    val dynamic by remember(flags) { flags.dynamicColorChanges() }.collectAsState(flags.dynamicColor())
    return dynamic
}

/** 叠加 Monet 动态主色，保持背景与表面中性克制。 */
fun HanaColors.withDynamicAccent(primary: Color, primaryContainer: Color): HanaColors = HanaColors(
    bg = this.bg,
    surface = this.surface,
    fill = this.fill,
    ink = this.ink,
    ink2 = this.ink2,
    ink3 = this.ink3,
    hair = this.hair,
    edge = this.edge,
    accent = primary,
    accentSoft = primaryContainer.copy(alpha = 0.35f),
    onAccent = this.onAccent,
    ok = this.ok,
    okSoft = this.okSoft,
    warn = this.warn,
    warnSolid = this.warnSolid,
    warnSoft = this.warnSoft,
    bad = this.bad,
    badSoft = this.badSoft,
    neutralSoft = this.neutralSoft,
    answer = this.answer,
    greySoft = this.greySoft,
    glass = this.glass,
    glassEdge = this.glassEdge,
    cardShadow = this.cardShadow,
    floatShadow = this.floatShadow,
    thumb = this.thumb,
    series = this.series,
    seriesOther = this.seriesOther,
    grid = this.grid,
    isDark = this.isDark,
)

/** 状态栏、导航栏的图标深浅跟界面一致:界面强制浅色而系统是深色时,图标也得是深色的才看得见。 */
fun ComponentActivity.systemBarsFor(dark: Boolean) {
    val transparent = android.graphics.Color.TRANSPARENT
    // 旧系统上导航栏不能全透明,垫一层半透明的底;取值与 enableEdgeToEdge 默认的一致
    val lightScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
    val darkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    enableEdgeToEdge(
        statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
        navigationBarStyle = if (dark) SystemBarStyle.dark(darkScrim) else SystemBarStyle.light(lightScrim, darkScrim),
    )
}

val HanaM3Typography = Typography(
    displayLarge = HanaType.LargeTitle,
    displayMedium = HanaType.LargeTitle.copy(fontSize = 24.sp, lineHeight = 30.sp),
    displaySmall = HanaType.Title.copy(fontSize = 20.sp, lineHeight = 26.sp),
    headlineLarge = HanaType.Title.copy(fontSize = 20.sp, lineHeight = 26.sp),
    headlineMedium = HanaType.Title,
    headlineSmall = HanaType.BodyStrong.copy(fontSize = 16.sp, lineHeight = 22.sp),
    titleLarge = HanaType.Title,
    titleMedium = HanaType.BodyStrong,
    titleSmall = HanaType.Label,
    bodyLarge = HanaType.Body,
    bodyMedium = HanaType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = HanaType.Caption,
    labelLarge = HanaType.Label.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = HanaType.Label,
    labelSmall = HanaType.Hint,
)

/** 深浅色按设置里的外观([appearanceDark]);支持 Monet 动态壁纸取色；同时把 Material 组件的颜色对上。 */
@Composable
fun HanaTheme(
    dark: Boolean = appearanceDark(),
    reduceMotion: Boolean = false,
    dynamicColor: Boolean = dynamicColorEnabled(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val baseColors = if (dark) DarkColors else LightColors
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        baseColors.withDynamicAccent(dynScheme.primary, dynScheme.primaryContainer)
    } else {
        baseColors
    }
    val currentScheme = scheme(colors)
    CompositionLocalProvider(LocalHanaColors provides colors, LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = currentScheme, typography = HanaM3Typography) {
            CompositionLocalProvider(LocalContentColor provides colors.ink, content = content)
        }
    }
}

private fun scheme(c: HanaColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.accent,
        secondary = c.accent, onSecondary = c.onAccent,
        background = c.bg, onBackground = c.ink,
        surface = c.surface, onSurface = c.ink, surfaceVariant = c.fill, onSurfaceVariant = c.ink2,
        surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface, surfaceTint = c.surface,
        outline = c.ink3, outlineVariant = c.hair,
        error = c.bad, onError = c.onAccent,
    )
}

private val LightScheme = scheme(LightColors)
private val DarkScheme = scheme(DarkColors)
