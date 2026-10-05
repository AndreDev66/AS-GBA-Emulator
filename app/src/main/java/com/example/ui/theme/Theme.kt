package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Tema AS GBA — inspirado en Steam Big Picture.
 *
 * `dynamicColor` desactivado por defecto: el color dinámico del sistema toca
 * los azules del tema y los mezcla con el fondo de pantalla del usuario, rompiendo
 * la paleta cuidadosamente calibrada. Cuando el usuario tiene un fondo llamativo
 * los textos pierden contraste y el cian primario desaparece.
 */

private val DarkColorScheme = darkColorScheme(
    // Primario: cian eléctrico (CTA, tabs activos, FAB)
    primary             = SteamCyan,
    onPrimary           = Color(0xFF001826),
    primaryContainer    = SteamCyanDark,
    onPrimaryContainer  = Color(0xFFB3E5FC),

    // Secundario: verde "activo" / FPS
    secondary           = SteamGreen,
    onSecondary         = Color(0xFF001A0D),
    secondaryContainer  = SteamGreenSoft,
    onSecondaryContainer = Color(0xFFA8F0C6),

    // Terciario: rosa hackrom/favorito
    tertiary            = SteamPink,
    onTertiary          = Color(0xFF1A0010),
    tertiaryContainer   = SteamPinkSoft,
    onTertiaryContainer = Color(0xFFFFB3E0),

    // Fondos y superficies
    background          = SteamBg,
    surface             = SteamSurface1,
    surfaceVariant      = SteamSurface2,
    surfaceTint         = SteamCyan.copy(alpha = 0.04f),

    // Texto sobre superficies
    onBackground        = SteamTextPrimary,
    onSurface           = SteamTextPrimary,
    onSurfaceVariant    = SteamTextSecondary,

    // Bordes y outlines
    outline             = SteamBorder,
    outlineVariant      = SteamBorderActive,

    // Errores
    error               = Color(0xFFCF6679),
    onError             = Color(0xFF1A000A),
)

// Modo claro (heredado, sin uso en esta app pero requerido por Material3)
private val LightColorScheme = lightColorScheme(
    primary             = Color(0xFF0060A0),
    onPrimary           = Color.White,
    primaryContainer    = Color(0xFFCCE8FF),
    onPrimaryContainer  = Color(0xFF001E33),
    secondary           = Color(0xFF2E7D52),
    onSecondary         = Color.White,
    background          = Color(0xFFF4F6FA),
    surface             = Color(0xFFFFFFFF),
    onBackground        = Color(0xFF1A1C22),
    onSurface           = Color(0xFF1A1C22),
)

@Composable
fun ASGbaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else      -> LightColorScheme
    }

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
