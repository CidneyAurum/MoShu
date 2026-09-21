package app.moshu.journal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import android.os.Build
import androidx.compose.runtime.CompositionLocalProvider
import app.moshu.journal.MoShuApp
import app.moshu.journal.ui.LocalMoShuApp

val InkBlack = Color(0xFF111214)
val InkSurface = Color(0xFF1A1B1E)
val InkSurfaceHigh = Color(0xFF24262A)
val PaperWhite = Color(0xFFF7F5F0)
val PurePaper = Color(0xFFFFFEFB)
val MistGray = Color(0xFF898A8E)
val Vermilion = Color(0xFFC95447)
val PineGreen = Color(0xFF638673)
val AzureBlue = Color(0xFF6585A8)
val AmberGold = Color(0xFFB8883D)

/**
 * 遮罩上的内容色。图片遮罩与全屏看图在深浅两套配色下都是深底，
 * 用 onSurface 之类会随主题翻转的语义色会让深色模式下的文字看不清。
 */
val OnScrim = Color(0xFFF7F5F0)

val CategoryColors = listOf(PineGreen, AzureBlue, AmberGold)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE17A6E),
    onPrimary = Color(0xFF2A0C08),
    primaryContainer = Color(0xFF4A211C),
    onPrimaryContainer = Color(0xFFFFDAD5),
    background = InkBlack,
    onBackground = Color(0xFFF2EFE9),
    surface = InkSurface,
    onSurface = Color(0xFFF2EFE9),
    surfaceVariant = InkSurfaceHigh,
    onSurfaceVariant = Color(0xFFB9B8B4),
    secondary = Color(0xFF8FB7A0),
    onSecondary = Color(0xFF10271B),
    outline = Color(0xFF3C3E42),
    outlineVariant = Color(0xFF2B2D31),
    error = Color(0xFFFFB4AB),
)

private val LightScheme = lightColorScheme(
    primary = Vermilion,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE2DD),
    onPrimaryContainer = Color(0xFF48130D),
    background = PaperWhite,
    onBackground = InkBlack,
    surface = PurePaper,
    onSurface = InkBlack,
    surfaceVariant = Color(0xFFEDEAE3),
    onSurfaceVariant = Color(0xFF66666A),
    secondary = PineGreen,
    onSecondary = Color.White,
    outline = Color(0xFFD4D0C8),
    outlineVariant = Color(0xFFE5E1D9),
    error = Color(0xFFB3261E),
)

private val MoShuTypography = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
)

private val MoShuShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun MoShuTheme(
    themeMode: String = "system",
    /** 跟随壁纸取色（Material You）。默认关闭以保留品牌配色，仅 Android 12+ 生效。 */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        // 「按时间」用本地小时粗算（19:00–7:00 为夜间）。精确日出日落需要定位权限，
        // 为了换个主题去要位置不合理。
        "auto" -> java.time.LocalTime.now().let { it.hour >= 19 || it.hour < 7 }
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkScheme else LightScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = MoShuTypography,
        shapes = MoShuShapes,
    ) {
        // 统一在这里注入应用依赖，页面不再各自去读单例。
        CompositionLocalProvider(LocalMoShuApp provides MoShuApp.instance) {
            content()
        }
    }
}
