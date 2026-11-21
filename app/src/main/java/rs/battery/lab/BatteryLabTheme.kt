package rs.battery.lab

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Shared blue surfaces include dialog/menu/chip tonal roles, not just the page background. */
private val labColors = darkColorScheme(
    primary = Color(0xFF42CFFF), onPrimary = Color(0xFF002432),
    primaryContainer = Color(0xFF064E73), onPrimaryContainer = Color(0xFFE3F6FF),
    secondary = Color(0xFF8DD7EB), onSecondary = Color(0xFF003541),
    secondaryContainer = Color(0xFF16485C), onSecondaryContainer = Color(0xFFDDF6FF),
    tertiary = Color(0xFF67DEBE), onTertiary = Color(0xFF00382B),
    tertiaryContainer = Color(0xFF155347), onTertiaryContainer = Color(0xFFC5FFEC),
    background = Color(0xFF04111D), onBackground = Color(0xFFF1F7FA),
    surface = Color(0xFF092130), onSurface = Color(0xFFF1F7FA),
    surfaceVariant = Color(0xFF173C50), onSurfaceVariant = Color(0xFFB8D0DC),
    surfaceTint = Color(0xFF42CFFF), outline = Color(0xFF7593A4), outlineVariant = Color(0xFF31556A),
    surfaceDim = Color(0xFF061725), surfaceBright = Color(0xFF244D63),
    surfaceContainerLowest = Color(0xFF04111D), surfaceContainerLow = Color(0xFF092130),
    surfaceContainer = Color(0xFF0D293B), surfaceContainerHigh = Color(0xFF123449),
    surfaceContainerHighest = Color(0xFF1B4055),
    inverseSurface = Color(0xFFDCEBF1), inverseOnSurface = Color(0xFF15303E), inversePrimary = Color(0xFF00658C)
)

@Composable internal fun BatteryLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = labColors, content = content)
}
