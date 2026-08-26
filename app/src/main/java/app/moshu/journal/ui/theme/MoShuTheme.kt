package app.moshu.journal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

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
fun MoShuTheme(themeMode: String = "system", content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = MoShuTypography,
        shapes = MoShuShapes,
        content = content,
    )
}
